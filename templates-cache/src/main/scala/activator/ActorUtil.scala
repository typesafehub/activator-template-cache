package activator

import akka.actor._
import scala.util.control.NonFatal

trait ForwardingExceptions {
  self: Actor =>

  // This method is necessary so futures are immediately killed on error, rather than waiting for a timeout.
  // i.e. it's a fast path.
  def forwardingExceptionsToFutures(f: Receive): Receive =
    new Receive() {
      def isDefinedAt(x: Any) = f.isDefinedAt(x)
      def apply(x: Any): Unit =
        try f(x)
        catch {
          case NonFatal(ex) =>
            sender ! Status.Failure(ex)
            throw ex
        }
    }
}
