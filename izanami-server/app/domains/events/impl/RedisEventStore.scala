package domains.events.impl

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.{Done, NotUsed}
import domains.Domain.Domain
import domains.events.Events.IzanamiEvent
import domains.events.{EventLogger, EventStore}
import env.RedisEventsConfig
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands
import io.lettuce.core.pubsub.{RedisPubSubListener, StatefulRedisPubSubConnection}
import libs.streams.CacheableQueue
import play.api.Logger
import play.api.libs.json.{JsError, JsResult, JsSuccess, Json}
import store.redis.RedisWrapper

import scala.compat.java8.FutureConverters._
import scala.concurrent.Future
import scala.util.Failure

class RedisEventStore(client: RedisWrapper, config: RedisEventsConfig, system: ActorSystem) extends EventStore {

  import EventLogger._
  import system.dispatcher

  implicit private val s   = system
  implicit private val mat = ActorMaterializer()

  logger.info(s"Starting redis event store")

  private val connection: StatefulRedisConnection[String, String]             = client.connection
  private val connectionPubSub: StatefulRedisPubSubConnection[String, String] = client.connectPubSub()
  private val channel: RedisPubSubAsyncCommands[String, String]               = connectionPubSub.async()

  private val queue = CacheableQueue[IzanamiEvent](500, queueBufferSize = 500)

  connectionPubSub.addListener(new RedisPubSubListener[String, String] {
    private def publishMessage(message: String) = {
      val json                           = Json.parse(message)
      val result: JsResult[IzanamiEvent] = json.validate[IzanamiEvent]
      result match {
        case JsSuccess(e, _) =>
          logger.debug(s"Receiving new event $e from Redis topic")
          queue.offer(e).onComplete {
            case Failure(e) => Logger.error(s"Error publishing event to queue", e)
            case r          => Logger.debug(s"Event published to queue $r")
          }
        case JsError(errors) =>
          logger.error(s"Error deserializing event of type ${json \ "type"} : $errors")
      }
    }

    override def message(channel: String, message: String): Unit =
      publishMessage(message)

    override def message(pattern: String, channel: String, message: String): Unit =
      publishMessage(message)

    override def subscribed(channel: String, count: Long): Unit = {}

    override def psubscribed(pattern: String, count: Long): Unit = {}

    override def unsubscribed(channel: String, count: Long): Unit = {}

    override def punsubscribed(pattern: String, count: Long): Unit = {}
  })

  connectionPubSub.async().subscribe(config.topic)

  override def publish(event: IzanamiEvent): Future[Done] = {
    logger.debug(s"Publishing event $event to Redis topic izanamiEvents")

    val publish = connection.async().publish(config.topic, Json.stringify(event.toJson)).toScala.map(_ => Done)
    publish.onComplete {
      case Failure(e) =>
        logger.error(s"Error publishing event to Redis", e)
      case _ =>
    }
    publish
  }

  override def events(domains: Seq[Domain],
                      patterns: Seq[String],
                      lastEventId: Option[Long]): Source[IzanamiEvent, NotUsed] =
    lastEventId match {
      case Some(_) =>
        queue.sourceWithCache
          .via(dropUntilLastId(lastEventId))
          .filter(eventMatch(patterns, domains))
      case None =>
        queue.rawSource
          .filter(eventMatch(patterns, domains))
    }

  override def close() = {}

  override def check(): Future[Unit] = connection.async().get("test").toScala.map(_ => ())
}
