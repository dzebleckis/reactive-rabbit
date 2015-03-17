package io.scalac.amqp.impl

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

import akka.actor.ActorSystem
import akka.stream.ActorFlowMaterializer
import akka.stream.scaladsl.{Source, Sink}

import java.io.IOException
import java.util.UUID

import io.scalac.amqp._

import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Span, Millis}


class RabbitConnectionSpec extends FlatSpec with Matchers with ScalaFutures with BeforeAndAfterAll {
  implicit override val patienceConfig =
    PatienceConfig(timeout = Span(500, Millis), interval = Span(50, Millis))

  val connection = Connection()
  implicit val system = ActorSystem()
  implicit val mat = ActorFlowMaterializer()

  override def afterAll() = system.shutdown()


  "queueDeclare" should "declare server-named, exclusive, auto-delete, non-durable queue" in {
    connection.queueDeclare().futureValue should have (
      'durable (false),
      'exclusive (true),
      'autoDelete (true)
    )
  }

  it should "declare queue with given name" in {
    val name = UUID.randomUUID().toString
    connection.queueDeclare(Queue(name = name, durable = false, exclusive = true)).futureValue should have (
      'queue (name)
    )
  }

  it should "do nothing if queue already exists" in {
    val name = UUID.randomUUID().toString
    val queue = Queue(
      name = name,
      durable = false,
      exclusive = true,
      xMessageTtl = 10.seconds,
      xExpires = 1.minute,
      xMaxLength = Some(1),
      xDeadLetterExchange = Some(Queue.XDeadLetterExchange(name = "", routingKey = Some("foo"))))

    connection.queueDeclare(queue)
      .flatMap(_ ⇒ connection.queueDeclare(queue))
      .futureValue shouldBe Queue.DeclareOk(
        queue = name,
        messageCount = 0,
        consumerCount = 0
      )
  }

  it should "return number of consumers" in {
    val queue = Queue(name = UUID.randomUUID().toString, exclusive = true)
    connection.queueDeclare(queue).flatMap { _ ⇒
      Source(connection.consume(queue.name)).to(Sink.ignore).run()
      connection.queueDeclare(queue)
    }.futureValue should have (
      'consumerCount (1)
    )
  }

  "queueDeclarePassive" should "do nothing if queue exists" in {
    whenReady(
      connection.queueDeclare().flatMap(queue ⇒
        connection.queueDeclarePassive(queue.name).map(_ -> queue))) {
      case (ok, queue) ⇒ ok.queue shouldBe queue.name
    }
  }

  "queueDelete" should "delete queue" in {
    connection.queueDeclare().flatMap(queue ⇒
      connection.queueDelete(queue.name).map(_ -> queue)).flatMap {
      case (ok, queue) ⇒ connection.queueDeclarePassive(queue.name)
        .map(_ ⇒ false)
        .recover {
          case e: IOException ⇒ true
        }
    }.futureValue shouldBe true
  }

  "exchangeDeclare" should "declare an exchange" in {
    val name = UUID.randomUUID().toString
    (for {
      _        <- connection.exchangeDeclare(Exchange(name = name, `type` = Fanout, durable = false, autoDelete = true))
      _        <- connection.exchangeDeclarePassive(name)
      queue    <- connection.queueDeclare()
      _        <- connection.queuePurge(queue.name)
      _        <- connection.queueBind(queue.name, name, "foo")
      unbindOk <- connection.queueUnbind(queue.name, name, "foo")
      _        <- connection.queueDelete(queue.name)
      deleteOk <- connection.exchangeDelete(name)
    } yield (deleteOk)).futureValue shouldBe Exchange.DeleteOk()
  }

}
