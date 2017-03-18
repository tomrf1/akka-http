package akka.http.caching

import java.util.concurrent.{ CompletableFuture, Executor, TimeUnit }

import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.concurrent.Future
import com.github.benmanes.caffeine.cache.{ AsyncCacheLoader, AsyncLoadingCache, Caffeine }
import akka.http.caching.LruCache.toJavaMappingFunction

import scala.compat.java8.FutureConverters._
import scala.compat.java8.FunctionConverters._

object LruCache {

  /**
   * Creates a new [[akka.http.caching.ExpiringLruCache]] or
   * [[akka.http.caching.SimpleLruCache]] instance depending on whether
   * a non-zero and finite timeToLive and/or timeToIdle is set or not.
   */
  def apply[V](
    defaultLoader:   Any ⇒ Future[V],
    maxCapacity:     Int             = 500,
    initialCapacity: Int             = 16,
    timeToLive:      Duration        = Duration.Inf,
    timeToIdle:      Duration        = Duration.Inf): Cache[V] = {

    val loader = new AsyncCacheLoader[Any, V] {
      def asyncLoad(k: Any, e: Executor) = defaultLoader(k).toJava.toCompletableFuture
    }

    if (timeToLive.isFinite() || timeToIdle.isFinite())
      new ExpiringLruCache[V](maxCapacity, initialCapacity, timeToLive, timeToIdle, loader)
    else
      new SimpleLruCache[V](maxCapacity, initialCapacity, loader)
  }

  def toJavaMappingFunction[V](genValue: () ⇒ Future[V]) =
    asJavaBiFunction[Any, Executor, CompletableFuture[V]]((k, e) ⇒ genValue().toJava.toCompletableFuture)
}

trait LruCache[V] extends Cache[V] {

  private[caching] val store: AsyncLoadingCache[Any, V]

  def get(key: Any): Option[Future[V]] = Option(store.getIfPresent(key)).map(_.toScala)

  def apply(key: Any, genValue: () ⇒ Future[V]): Future[V] = store.get(key, toJavaMappingFunction(genValue)).toScala

  def remove(key: Any): Option[Future[V]] = Option(store.synchronous().asMap().remove(key)).map(Future.successful)

  def clear(): Unit = store.synchronous().invalidateAll()

  def keys: Set[Any] = store.synchronous().asMap().keySet().asScala.toSet

  def size: Int = store.synchronous().asMap().size()
}

final class SimpleLruCache[V](val maxCapacity: Int, val initialCapacity: Int,
                              defaultLoader: AsyncCacheLoader[Any, V]) extends LruCache[V] {
  require(maxCapacity >= 0, "maxCapacity must not be negative")
  require(initialCapacity <= maxCapacity, "initialCapacity must be <= maxCapacity")

  private[caching] val store = Caffeine.newBuilder().asInstanceOf[Caffeine[Any, V]]
    .initialCapacity(initialCapacity)
    .maximumSize(maxCapacity)
    .buildAsync[Any, V](defaultLoader)
}

final class ExpiringLruCache[V](maxCapacity: Long, initialCapacity: Int,
                                timeToLive: Duration, timeToIdle: Duration,
                                defaultLoader: AsyncCacheLoader[Any, V]) extends LruCache[V] {
  require(
    !timeToLive.isFinite || !timeToIdle.isFinite || timeToLive > timeToIdle,
    s"timeToLive($timeToLive) must be greater than timeToIdle($timeToIdle)")

  private[caching] def ttl: Caffeine[Any, V] ⇒ Caffeine[Any, V] = { builder ⇒
    if (timeToLive.isFinite) builder.expireAfterWrite(timeToLive.toMillis, TimeUnit.MILLISECONDS)
    else builder
  }

  private[caching] def tti: Caffeine[Any, V] ⇒ Caffeine[Any, V] = { builder ⇒
    if (timeToIdle.isFinite) builder.expireAfterAccess(timeToIdle.toMillis, TimeUnit.MILLISECONDS)
    else builder
  }

  private[caching] val builder = Caffeine.newBuilder().asInstanceOf[Caffeine[Any, V]]
    .initialCapacity(initialCapacity)
    .maximumSize(maxCapacity)

  private[caching] val store = (ttl andThen tti)(builder).buildAsync[Any, V](defaultLoader)
}
