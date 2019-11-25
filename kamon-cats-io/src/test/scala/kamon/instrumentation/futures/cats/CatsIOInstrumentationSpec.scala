package kamon.instrumentation.futures.cats

import java.util.concurrent.Executors

import cats.effect.IO.Async
import cats.effect.{Async, ContextShift, IO, Timer}
import kamon.Kamon
import kamon.tag.Lookups.plain
import kamon.context.Context
import kamon.tag.Lookups
import org.scalatest.{Matchers, OptionValues, WordSpec}
import org.scalatest.concurrent.{Eventually, PatienceConfiguration, ScalaFutures}

import scala.concurrent.ExecutionContext.global
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

class CatsIoInstrumentationSpec extends WordSpec with ScalaFutures with Matchers with PatienceConfiguration
    with OptionValues with Eventually {

  // NOTE: We have this test just to ensure that the Context propagation is working, but starting with Kamon 2.0 there
  //       is no need to have explicit Runnable/Callable instrumentation because the instrumentation brought by the
  //       kamon-executors module should take care of all non-JDK Runnable/Callable implementations.

  def store(tag: String, value: String) = {
    println(s"storing on ${Thread.currentThread().getId}")
    Kamon.storeContext(Context.of(tag, value))
  }
  def get(tag: String): Option[String] = {
    val value = Kamon.currentContext().getTag(Lookups.option(tag))
    println(s"getting from ${Thread.currentThread().getId}  ${value}")
    value
  }


  "Instrumentation" should {
    "propagate context" which {

      "chain IOs" in {
        val passThroughTag = for {
          _   <- IO(store("tag", "value"))
          tag <- IO(get("tag"))
        } yield tag
        passThroughTag.unsafeRunSync() shouldBe Some("value")
      }

      "chain IOs with shift" in {
        val newEc: ExecutionContext =
          ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())

        val passThroughTag = for {
          _   <- IO(store("tag1", "value1"))
          _   <- IO.shift(newEc)
          tag <- IO(get("tag1"))
        } yield tag
        passThroughTag.unsafeRunSync() shouldBe Some("value1")
      }

      "chain IOs with multiple shifts" in {
        val newEc: ExecutionContext =
          ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor())
        val newEc2: ExecutionContext =
          ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor())

        val passThroughTag = for {
          _   <- IO(store("tag1", "value1"))
          _   <- IO.shift(newEc)
          _   <- IO(get("tag1"))
          _   <- IO.shift(newEc2)
          tag <- IO(get("tag1"))
        } yield tag
        passThroughTag.unsafeRunSync() shouldBe Some("value1")
      }


      "async into IO" in {
         val asyncio: IO[Unit] = IO.async(cb => {
           store("tag3", "value3")
           cb(Right(()))
         })

        val passThroughTag = for {
          _ <- asyncio
          tag <- IO(get("tag3"))
        } yield tag


        passThroughTag.unsafeRunSync() shouldBe Some("value3")
      }

      "from IO into async" in {

        val passThroughTag = for {
          _   <- IO(store("tag4", "value4"))
          tag <- IO.async[Option[String]](cb => {
            cb(Right(get("tag4")))
          })
        } yield tag

        passThroughTag.unsafeRunSync() shouldBe Some("value4")
      }

      //TODO, when future is unpacked, it should apply it to surrounding IO
      "Future -> IO" in {
        val fExecutor = ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor())

        def futureWithContext = Future {
          store("tag5", "value5")
        }(fExecutor)

        val passThroughTag = for {
          _   <- IO.fromFuture(IO(futureWithContext))
          tag <- IO(get("tag5"))
        } yield tag

        passThroughTag.unsafeRunSync() shouldBe Some("value5")
      }

      "IO -> Future" in {
        val fExecutor = ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor())

        def futureOfContextTagValue = Future {
          get("tag6")
        }(fExecutor)

        val passThroughTag = for {
          _   <- IO(store("tag6", "value6"))
          tag <- IO.fromFuture(IO(futureOfContextTagValue))
        } yield tag

        passThroughTag.unsafeRunSync() shouldBe Some("value6")
      }


      "sleep" in {
        val timer = IO.timer(ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor()))
        val passThroughTag = for {
          _   <- IO(store("tag", "value"))
          _   <- IO.sleep(1.milli)(timer)
          tag <- IO(get("tag"))
        } yield tag
        passThroughTag.unsafeRunSync() shouldBe Some("value")
      }

    }
  }




}