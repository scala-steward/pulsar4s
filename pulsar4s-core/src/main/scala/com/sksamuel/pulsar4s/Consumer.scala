package com.sksamuel.pulsar4s

import com.sksamuel.exts.Logging
import org.apache.pulsar.client.api.ConsumerStats
import org.apache.pulsar.client.api.transaction.Transaction

import java.io.Closeable
import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/**
  * Operations on the consumer that may be used in a transactional context.
  */
trait TransactionalConsumerOps[T] {
  final def acknowledgeAsync[F[_] : AsyncHandler](message: ConsumerMessage[T]): F[Unit] =
    acknowledgeAsync(message.messageId)

  def acknowledgeAsync[F[_] : AsyncHandler](messageId: MessageId): F[Unit]

  final def acknowledgeCumulativeAsync[F[_] : AsyncHandler](message: ConsumerMessage[T]): F[Unit] =
    acknowledgeCumulativeAsync(message.messageId)

  def acknowledgeCumulativeAsync[F[_] : AsyncHandler](messageId: MessageId): F[Unit]
}

trait Consumer[T] extends Closeable with TransactionalConsumerOps[T] {

  /**
    * Receives a single message.
    * This calls blocks until a message is available.
    */
  def receive: Try[ConsumerMessage[T]]

  /**
    * Receive a single message waiting up to the given duration
    * if necessary. If no message is received within the duration
    * then None is returned.
    */
  def receive(duration: FiniteDuration): Try[Option[ConsumerMessage[T]]]

  def receiveAsync[F[_] : AsyncHandler]: F[ConsumerMessage[T]]

  def receiveBatchAsync[F[_]: AsyncHandler]: F[Vector[ConsumerMessage[T]]]

  def stats: ConsumerStats

  def subscription: Subscription

  def topic: Topic

  /**
    * Return true if the topic was terminated and this consumer has already consumed all the messages in the topic.
    *
    * Please note that this does not simply mean that the consumer is caught up with the last message published by
    * producers, rather the topic needs to be explicitly "terminated".
    */
  def hasReachedEndOfTopic: Boolean

  def redeliverUnacknowledgedMessages(): Unit

  def seek(messageId: MessageId): Unit
  def seek(timestamp: Long): Unit
  def seek(timestamp: Instant): Unit =
    seek(timestamp.toEpochMilli)

  def seekEarliest(): Unit = seek(MessageId.earliest)
  def seekLatest(): Unit = seek(MessageId.latest)

  def seekAsync[F[_] : AsyncHandler](messageId: MessageId): F[Unit]
  def seekAsync[F[_] : AsyncHandler](timestamp: Long): F[Unit]
  def seekAsync[F[_] : AsyncHandler](timestamp: Instant): F[Unit] =
    seekAsync[F](timestamp.toEpochMilli)

  def getLastMessageId(): MessageId

  def getLastMessageIdAsync[F[_] : AsyncHandler]: F[MessageId]

  def close(): Unit
  def closeAsync[F[_] : AsyncHandler]: F[Unit]

  def acknowledge(message: ConsumerMessage[T]): Unit = acknowledge(message.messageId)
  def acknowledge(messageId: MessageId): Unit

  def negativeAcknowledge(message: ConsumerMessage[T]): Unit = negativeAcknowledge(message.messageId)
  def negativeAcknowledge(messageId: MessageId): Unit

  def acknowledgeCumulative(message: ConsumerMessage[T]): Unit
  def acknowledgeCumulative(messageId: MessageId): Unit

  final def negativeAcknowledgeAsync[F[_] : AsyncHandler](message: ConsumerMessage[T]): F[Unit] =
    negativeAcknowledgeAsync(message.messageId)

  def negativeAcknowledgeAsync[F[_] : AsyncHandler](messageId: MessageId): F[Unit]

  def reconsumeLater(message: ConsumerMessage[T], delayTime: Long, unit: TimeUnit): Unit

  def reconsumeLaterAsync[F[_] : AsyncHandler](message: ConsumerMessage[T], delayTime: Long, unit: TimeUnit): F[Unit]

  def unsubscribe(): Unit
  def unsubscribeAsync[F[_] : AsyncHandler]: F[Unit]

  /**
    * Get an instance of `TransactionalConsumerOps` that provides transactional operations on the consumer.
    */
  def tx(implicit ctx: TransactionContext): TransactionalConsumerOps[T]
}

class DefaultConsumer[T](consumer: JConsumer[T]) extends Consumer[T] with Logging {

  override def receive: Try[ConsumerMessage[T]] = Try {
    logger.trace("About to block until a message is received..")
    val msg = consumer.receive()
    ConsumerMessage.fromJava(msg)
  }

  override def receive(duration: FiniteDuration): Try[Option[ConsumerMessage[T]]] = Try {
    logger.trace(s"About to block for duration $duration or until a message is received..")
    val msg = consumer.receive(duration.toMillis.toInt, TimeUnit.MILLISECONDS)
    Option(msg).map(ConsumerMessage.fromJava)
  }

  override def receiveAsync[F[_] : AsyncHandler]: F[ConsumerMessage[T]] = implicitly[AsyncHandler[F]].receive(consumer)

  override def receiveBatchAsync[F[_] : AsyncHandler]: F[Vector[ConsumerMessage[T]]] =
    implicitly[AsyncHandler[F]].receiveBatch(consumer)

  override def acknowledge(messageId: MessageId): Unit = consumer.acknowledge(messageId)
  override def acknowledgeCumulative(message: ConsumerMessage[T]): Unit = consumer.acknowledgeCumulative(message.messageId)
  override def acknowledgeCumulative(messageId: MessageId): Unit = consumer.acknowledgeCumulative(messageId)

  override def acknowledgeAsync[F[_] : AsyncHandler](messageId: MessageId): F[Unit] =
    implicitly[AsyncHandler[F]].acknowledgeAsync(consumer, messageId)

  override def acknowledgeCumulativeAsync[F[_] : AsyncHandler](messageId: MessageId): F[Unit] =
    implicitly[AsyncHandler[F]].acknowledgeCumulativeAsync(consumer, messageId)

  override def negativeAcknowledge(messageId: MessageId): Unit = consumer.negativeAcknowledge(messageId)
  override def negativeAcknowledgeAsync[F[_]: AsyncHandler](messageId: MessageId): F[Unit] =
    implicitly[AsyncHandler[F]].negativeAcknowledgeAsync(consumer, messageId)

  override def reconsumeLater(message: ConsumerMessage[T], delayTime: Long, unit: TimeUnit): Unit =
    message match {
      case consumerMessage: ConsumerMessageWithValueTry[T] =>
        consumer.reconsumeLater(consumerMessage.getBaseMessage(), delayTime, unit)
      case _ =>
        throw new UnsupportedOperationException(
          "Only ConsumerMessageWithValueTry is supported for reconsumeLater operation"
        )
    }

  override def reconsumeLaterAsync[F[_]: AsyncHandler](message: ConsumerMessage[T], delayTime: Long, unit: TimeUnit): F[Unit] =
    implicitly[AsyncHandler[F]].reconsumeLaterAsync(consumer, message, delayTime, unit)

  override def stats: ConsumerStats = consumer.getStats
  override def subscription: Subscription = Subscription(consumer.getSubscription)
  override def topic: Topic = Topic(consumer.getTopic)

  override def hasReachedEndOfTopic: Boolean = consumer.hasReachedEndOfTopic

  override def redeliverUnacknowledgedMessages(): Unit = consumer.redeliverUnacknowledgedMessages()

  override def seek(messageId: MessageId): Unit = consumer.seek(messageId)

  override def seek(timestamp: Long): Unit = consumer.seek(timestamp)

  override def seekAsync[F[_] : AsyncHandler](messageId: MessageId): F[Unit] =
    implicitly[AsyncHandler[F]].seekAsync(consumer, messageId)

  override def seekAsync[F[_] : AsyncHandler](timestamp: Long): F[Unit] =
    implicitly[AsyncHandler[F]].seekAsync(consumer, timestamp)

  override def getLastMessageId(): MessageId = consumer.getLastMessageId()

  override def getLastMessageIdAsync[F[_] : AsyncHandler]: F[MessageId] = implicitly[AsyncHandler[F]].getLastMessageId(consumer)

  override def close(): Unit = {
    logger.info("Closing consumer")
    consumer.close()
  }

  override def closeAsync[F[_] : AsyncHandler]: F[Unit] = implicitly[AsyncHandler[F]].close(consumer)

  override def unsubscribe(): Unit = consumer.unsubscribe()
  override def unsubscribeAsync[F[_] : AsyncHandler]: F[Unit] = implicitly[AsyncHandler[F]].unsubscribeAsync(consumer)

  override def tx(implicit ctx: TransactionContext): TransactionalConsumerOps[T] =
    new DefaultTransactionalConsumer[T](consumer, ctx.transaction)
}

class DefaultTransactionalConsumer[T](consumer: JConsumer[T], transaction: Transaction) extends TransactionalConsumerOps[T] {
  override def acknowledgeAsync[F[_] : AsyncHandler](messageId: MessageId): F[Unit] =
    implicitly[AsyncHandler[F]].acknowledgeAsync(consumer, messageId, transaction)

  override def acknowledgeCumulativeAsync[F[_] : AsyncHandler](messageId: MessageId): F[Unit] =
    implicitly[AsyncHandler[F]].acknowledgeCumulativeAsync(consumer, messageId, transaction)
}
