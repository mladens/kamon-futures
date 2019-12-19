package kamon.instrumentation.futures.cats

import cats.effect.IO
import kamon.Kamon
import kamon.instrumentation.context.HasContext
import kanela.agent.api.instrumentation.InstrumentationBuilder
import kanela.agent.libs.net.bytebuddy.asm.Advice

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Left, Right, Success}


class IOInstrumentation extends InstrumentationBuilder{
    onType("cats.effect.internals.IOFromFuture$")
    .intercept(method("apply"), classOf[IOInterceptor])
}


class IOInterceptor
object IOInterceptor {

  val immediate = new ExecutionContext {
    def execute(r: Runnable): Unit = r.run()
    def reportFailure(e: Throwable): Unit = ??? //TODO
  }

  /*When constructing IO from a future value apply future's context to the current thread
  * If its not yet completed, should wrap cb wtih context from future*/
  def wrapApply[A](@Advice.Argument(0) f: Future[A]): IO[A] = {

    f.value match {
      case Some(result) =>
        result match {
          case s@Success(a) => {
            //TODO not liking it, we should bundle it with IO (even pure) but unsure about context entry-point into the IO
            Kamon.storeContext(s.asInstanceOf[HasContext].context)
            IO.pure(a)
          }
          case Failure(e) => IO.raiseError(e)
        }
      case _ =>
        IO.async { cb =>
          f.onComplete(r => {
            val fRes = r match {
              case Success(a) => Right(a)
              case Failure(e) => Left(e)
            }
            cb(fRes) //TODO this case here
            /*Kamon.runWithContextTag("tag5","value5") {
              println(s"Wrapping with ${Kamon.currentContext().getTag(Lookups.plain("tag5"))}")
              cb(fRes)
            }*/
          })(immediate)
        }
    }
  }

}
