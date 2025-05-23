package com.sksamuel.pulsar4s

import org.apache.pulsar.client.api
import org.apache.pulsar.client.api.TypedMessageBuilder
import org.apache.pulsar.client.api.transaction.Transaction

import java.util.concurrent.TimeUnit
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

trait AsyncHandler[F[_]] {

  def transform[A, B](f: F[A])(fn: A => Try[B]): F[B]
  def failed(e: Throwable): F[Nothing]

  def createProducer[T](builder: api.ProducerBuilder[T]): F[Producer[T]]
  def createConsumer[T](builder: api.ConsumerBuilder[T]): F[Consumer[T]]
  def createReader[T](builder: api.ReaderBuilder[T]): F[Reader[T]]

  @deprecated("Use send(builder) instead", "2.8.0")
  def send[T](t: T, producer: api.Producer[T]): F[MessageId] = send(producer.newMessage().value(t))

  def send[T](builder: TypedMessageBuilder[T]): F[MessageId]
  def receive[T](consumer: api.Consumer[T]): F[ConsumerMessage[T]]
  def receiveBatch[T](consumer: api.Consumer[T]): F[Vector[ConsumerMessage[T]]]

  def close(producer: api.Producer[_]): F[Unit]
  def close(consumer: api.Consumer[_]): F[Unit]
  def close(reader: api.Reader[_]): F[Unit]
  def close(client: api.PulsarClient): F[Unit]

  def flush(producer: api.Producer[_]): F[Unit]

  def seekAsync(consumer: api.Consumer[_], messageId: MessageId): F[Unit]
  def seekAsync(consumer: api.Consumer[_], timestamp: Long): F[Unit]

  def seekAsync(reader: api.Reader[_], messageId: MessageId): F[Unit]
  def seekAsync(reader: api.Reader[_], timestamp: Long): F[Unit]
  def nextAsync[T](reader: api.Reader[T]): F[ConsumerMessage[T]]
  def hasMessageAvailable(reader: api.Reader[_]): F[Boolean]

  def unsubscribeAsync(consumer: api.Consumer[_]): F[Unit]

  def getLastMessageId[T](consumer: api.Consumer[T]): F[MessageId]

  def acknowledgeAsync[T](consumer: api.Consumer[T], messageId: MessageId): F[Unit]
  def acknowledgeAsync[T](consumer: api.Consumer[T], messageId: MessageId, txn: Transaction): F[Unit]
  def acknowledgeCumulativeAsync[T](consumer: api.Consumer[T], messageId: MessageId): F[Unit]
  def acknowledgeCumulativeAsync[T](consumer: api.Consumer[T], messageId: MessageId, txn: Transaction): F[Unit]
  def negativeAcknowledgeAsync[T](consumer: api.Consumer[T], messageId: MessageId): F[Unit]
  def reconsumeLaterAsync[T](consumer: api.Consumer[T], message: ConsumerMessage[T], delayTime: Long, unit: TimeUnit): F[Unit]

  def withTransaction[E, A](
    builder: api.transaction.TransactionBuilder,
    action: TransactionContext => F[Either[E, A]]
  ): F[Either[E, A]]
  def startTransaction(builder: api.transaction.TransactionBuilder): F[TransactionContext]
  def commitTransaction(txn: Transaction): F[Unit]
  def abortTransaction(txn: Transaction): F[Unit]
}

object AsyncHandler {
  def apply[F[_] : AsyncHandler]: AsyncHandler[F] = implicitly[AsyncHandler[F]]
  implicit def handler(implicit ec: ExecutionContext): AsyncHandler[Future] = new FutureAsyncHandler
}
