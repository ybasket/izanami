package izanami.commons

import java.util.concurrent.atomic.AtomicReference

import akka.actor.{ActorSystem, Cancellable}
import akka.event.Logging
import akka.http.scaladsl.util.FastFuture
import izanami.{IzanamiDispatcher, SmartCacheStrategy}
import izanami.Strategy._
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

class SmartCacheStrategyHandler[T](
    dispatcher: IzanamiDispatcher,
    initialPatterns: Seq[String],
    fetchData: Seq[String] => Future[Seq[(String, T)]],
    config: SmartCacheStrategy,
    fallback: Map[String, T],
    onValueUpdated: Seq[CacheEvent[T]] => Unit
)(implicit system: ActorSystem) {

  import dispatcher._
  private val cache: TrieMap[String, T] = TrieMap[String, T](fallback.toSeq: _*)
  private val patternsRef               = new AtomicReference[Seq[String]](initialPatterns)
  private val pollingScheduler          = new AtomicReference[Option[Cancellable]](None)
  private val log                       = Logging(system, this.getClass.getSimpleName)

  private def patterns = patternsRef.get()

  init()

  def init(): Unit = {
    val scheduler: Option[Cancellable] = config match {
      case CacheWithPollingStrategy(_, pollingInterval, _) =>
        Some(
          system.scheduler
            .schedule(pollingInterval, pollingInterval, new Runnable {
              override def run(): Unit =  { refreshCache() }
            })
        )

      case CacheWithSseStrategy(_, Some(pollingInterval), _) =>
        Some(
          system.scheduler
            .schedule(pollingInterval, pollingInterval, new Runnable {
              override def run(): Unit =  { refreshCache() }
            })
        )
      case _ => None
    }
    pollingScheduler.set(scheduler)
    log.info(s"Initializing smart cache actor with strategy $config and patterns $patterns")
    fetchData(patterns).onComplete {
      case Failure(e) =>
        log.error(e, "Error initializing smart cache retrying in 5 seconds")
        system.scheduler
          .scheduleOnce(5.seconds, new Runnable {
            override def run(): Unit =  { refreshCache() }
          })
      case Success(r) =>
        setValues(r, None, triggerEvent = true)
    }
  }

  def refreshCache(): Unit = {
    log.debug(s"Refresh cache for patterns $patterns")
    val keysAndPatterns: Seq[String] = resolvePatterns(patterns, cache.keys.toSeq)

    fetchData(keysAndPatterns).onComplete {

      case Failure(e) =>
        log.error(e, "Error refreshing cache")

      case Success(values) =>
        setValues(values, None, triggerEvent = true)
    }
  }

  def get(key: String): Future[Option[T]] =
    if (PatternsUtil.matchOnePattern(patterns)(key)) {
      FastFuture.successful(cache.get(key))
    } else {
      val value: Option[T] = cache.get(key)
      value match {
        case Some(_) =>
          FastFuture.successful(value)
        case None =>
          val fResult: Future[Seq[(String, T)]] = fetchData(Seq(key))
          fResult.map(r => r.find(_._1 == key).map(_._2))
      }
    }

  def getByPattern(pattern: String): Future[Seq[T]] =
    if (patterns.contains(pattern)) {
      def matchP = PatternsUtil.matchPattern(pattern) _
      val values: Seq[T] = cache
        .filter {
          case (k, _) => matchP(k)
        }
        .values
        .toSeq
      FastFuture.successful(values)
    } else {
      val futureValues: Future[Seq[(String, T)]] = fetchData(List(pattern))
      patternsRef.set(patterns :+ pattern)

      // Update internal cache
      futureValues.onComplete {
        case Success(r) =>
          setValues(r, None, triggerEvent = true)
        case Failure(e) =>
          log.error(e, "Error updating cache while searching for a new pattern {}", pattern)
      }

      futureValues.map(s => s.map(_._2))
    }

  def setValues(values: Seq[(String, T)], eventId: Option[Long], triggerEvent: Boolean): Unit = {
    log.debug("Updating cache with values {}", values)
    val valuesToUpdate: Seq[(String, T)] = values
      .asInstanceOf[Seq[(String, T)]]
      .filter(v => PatternsUtil.matchOnePattern(patterns)(v._1))

    if (triggerEvent) {
      val updates: Seq[CacheEvent[T]] = cache.collect {
        case (k, v) if valuesToUpdate.exists {
              case (k1, v2) => k1 == k && v2 != v
            } =>
          val newValue: (String, T) = valuesToUpdate.find(_._1 == k).get
          ValueUpdated[T](k, newValue._2, v)
      }.toSeq
      val creates: Seq[CacheEvent[T]] = valuesToUpdate.collect {
        case (k, v) if !cache.contains(k) =>
          ValueCreated(k, v)
      }
      val events: Seq[CacheEvent[T]] = updates ++ creates
      onValueUpdated(events)
    }
    valuesToUpdate.foreach {
      case (k, v) => cache.put(k, v)
    }
  }

  def removeValues(keys: Seq[String], eventId: Option[Long], triggerEvent: Boolean): Unit = {
    if (triggerEvent) {
      val deletes: Seq[CacheEvent[T]] = cache.collect {
        case (k, v) if keys.contains(k) =>
          ValueDeleted[T](k, v)
      }.toSeq
      onValueUpdated(deletes)
    }
    keys.foreach(cache.remove)
  }

  private def resolvePatterns(existingPatterns: Seq[String], keys: Seq[String]): Seq[String] =
    keys.filterNot(PatternsUtil.matchOnePattern(existingPatterns)) ++ existingPatterns

}

object PatternsUtil {

  def matchPattern(pattern: String)(key: String): Boolean = {
    val regex: String = buildPattern(pattern)
    key.matches(regex)
  }

  def buildPattern(str: String) = s"^${str.replaceAll("\\*", ".*")}$$"

  def matchOnePattern(existingPatterns: Seq[String])(str: String): Boolean =
    existingPatterns.exists(p => matchPattern(p)(str))

}
