package volsunga

import java.util.UUID

final case class SagaID(id: String) extends AnyVal

final case class TransactionID(id: UUID) extends AnyVal

final case class TransactionState[E, A](lastValue: A, lastError: Option[E])

trait Codec[A] {
  def decode(array: Array[Byte]): A
  def encode(value: A):           Array[Byte]
}

trait SagaPersistence[F[_]] {

  def initialize[A: Codec](sagaID: SagaID, initialValue: A): F[TransactionID]

  def setValue[A: Codec](transactionID: TransactionID, newValue: A): F[Unit]

  def setError[E: Codec](transactionID: TransactionID, newError: E): F[Unit]

  def finish(transactionID: TransactionID): F[Unit]

  def unfinished[E: Codec, A: Codec](sagaID: SagaID): fs2.Stream[F, (TransactionID, TransactionState[E, A])]
}

trait SagaMaterializer[F[_]] {

  // TODO: use saga persistence when constructing this

  // TODO: store IDs of processed sagas/transaction and filter them out

  // TODO: create a stream of transaction states to retry

  // TODO: create a method A => F[B]

  def materialize[E: Codec, A: Codec, B](sagaID: SagaID, saga: Saga[F, E, A, B]): (A => F[B], fs2.Stream[F, B])
}
