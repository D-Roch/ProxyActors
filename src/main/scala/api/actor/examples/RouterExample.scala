/*
 * Copyright 2013 API Technologies, LLC
 *
 * Distributed under the terms of the modified BSD license. See the LICENSE file
 * for further details.
 */

package api.actor.examples

import scala.annotation.tailrec
import api.actor._

object RouterExample extends App {
  // Router interface - a trait is only required for a router, not an actor
  trait Worker {
    def doWork()
  }

  // Typed actor POSO - this is the only thing we pass to the actor creation method
  class MyWorker extends Worker {
    var counter = 0

    def doWork() {
      def fib(n: Int): Int = {
        @tailrec
        def _fib(n: Int, b: Int, a: Int): Int = n match {
          case 0 => a
          case _ => _fib(n - 1, a + b, b)
        }

        _fib(n, 1, 0)
      }

      // We do this just to soak up a little time
      fib(100000000)
      counter += 1
    }
  }

  // Create one actor per logical CPU thread
  val actors = allCoresContext.proxyActors[MyWorker](totalCores)
  // Create a router to load balance these actors
  val router = proxyRouter[Worker](actors)

  // Load up the work!
  for (_ <- 1 to 1000) router.doWork()

  actors.foreach { actor => println(s"I worked ${actor.counter} time(s)") }

  // Signal that our actors are no longer needed - when the last actor of a
  // context is finished the thread pool will be automatically shutdown
  // Also, this blocks until all tasks are finished
  actorsFinished(actors)
}