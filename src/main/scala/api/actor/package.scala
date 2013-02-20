/**
 * Created by IntelliJ IDEA.
 * User: scott
 * Date: 2/14/13
 * Time: 11:37 PM
 */

package api

import scala.concurrent._
import scala.concurrent.duration.Duration
import scala.reflect.{ClassTag, classTag}
import java.lang.reflect.Method
import java.util.concurrent.{ExecutorService, Executor, Executors}
import net.sf.cglib.proxy.{MethodProxy, MethodInterceptor, Enhancer}

package object actor {
  // *** Thread Pools ***
  lazy val sameThread = ExecutionContext.fromExecutor(new Executor {
    def execute(command: Runnable) { command.run() }
  })

  def singleThreadPool =
    ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor)

  def fixedThreadPool(qty: Int) =
    ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(qty))

  def allCoresThreadPool = fixedThreadPool(Runtime.getRuntime.availableProcessors)

  def cachedThreadPool =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())

  // *** Contexts ***
  class ActorContext(private val ec: ExecutionContext) {
    def shutdown() {
      // We only actually shutdown an ec if it supports it
      ec match {
        case e: ExecutionContextExecutorService => e.shutdown()
        case _                                  =>
      }
    }

    def proxyActor[T: ClassTag](args: Seq[(Any,Class[_])] = Seq.empty): T = {
      implicit val context = ec
      api.actor.proxyActor[T](args)
    }
  }

  def actorContext(ec: ExecutionContext) = new ActorContext(ec)

  // TODO: Some Executors are also an ExecutorContext (after scala wraps them)
  // Look into converting the below into an implicit conversion
  def actorContext(e: Executor) = new ActorContext(e match {
    case es: ExecutorService  => ExecutionContext.fromExecutorService(es)
    case e:  Executor         => ExecutionContext.fromExecutor(e)
  })

  lazy val sameThreadContext = actorContext(sameThread: ExecutionContext)

  def singleThreadContext = actorContext(singleThreadPool: ExecutionContext)

  def fixedThreadContext(qty: Int) = actorContext(fixedThreadPool(qty): ExecutionContext)

  def allCoresContext = actorContext(allCoresThreadPool: ExecutionContext)

  def cachedThreadContext = actorContext(cachedThreadPool: ExecutionContext)

  // *** Method Interception ***
  private class Intercepter(implicit ec: ExecutionContext) extends MethodInterceptor {
    def intercept(obj:        AnyRef,
                  method:     Method,
                  args:       Array[AnyRef],
                  methProxy:  MethodProxy): AnyRef = {
      if (!Thread.holdsLock(obj)) {
        val returnType = method.getReturnType
        // We proxy the actual future object of the callee with our own
        val promise: Promise[AnyRef] =
          if (returnType == classOf[Future[AnyRef]]) Promise() else null

        val fut = future {
          // We synchronize on the called object to make sure that the
          // called object never allows more than one caller at a time
          val retVal = obj.synchronized { methProxy.invokeSuper(obj, args) }

          if (promise != null)
            // Our promise mimics the result of the actual future
            promise.completeWith(retVal.asInstanceOf[Future[AnyRef]])
          else retVal
        }

        // Fire and forget for Unit returning methods
        if (returnType == Void.TYPE) null
        // Return our proxy future for Future returning methods
        else if (promise != null) promise.future
        // Block until the computation done for anything else
        else Await.result(fut, Duration.Inf)
      // If omitted, call superclass method inline in this thread
      } else obj.synchronized { methProxy.invokeSuper(obj, args) }
    }
  }

  // *** Proxy Creation ***
  def proxyActor[T](args: Seq[(Any,Class[_])] = Seq.empty)
                   (implicit context: ExecutionContext, tag: ClassTag[T]): T = {
    val enhancer = new Enhancer
    // We don't need it and keeps proxy identity a bit more private
    enhancer.setUseFactory(false)
    enhancer.setSuperclass(classTag[T].runtimeClass)
    enhancer.setInterceptDuringConstruction(true)
    // Each instance of each extended class gets own intercepter instance
    enhancer.setCallback(new Intercepter)
    val (arg, types) = args.unzip
    //NOTE: Enhancer has a builtin cache to prevent rebuilding the class and
    // all calls up to this point looked pretty cheap
    enhancer.create(types.toArray,
      arg.toArray.asInstanceOf[Array[AnyRef]]).asInstanceOf[T]
  }
}