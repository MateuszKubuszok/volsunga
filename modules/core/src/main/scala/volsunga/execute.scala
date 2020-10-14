package volsunga

import java.util.UUID

import cats.Monad
import cats.effect.Timer
import cats.syntax.all._

final case class SagaID(id: String) extends AnyVal

final case class TransactionID(id: UUID) extends AnyVal

final case class TransactionState[Err, State](lastValue: State, lastErr: Option[Err])

trait Codec[A] {
  def decode(array: Array[Byte]): A

  def encode(value: A): Array[Byte]
}

/** Persists stages of a saga computation. Should follow the rules documented next to each method. */
trait SagaPersistence[F[_]] {

  /** Creates transaction that should be tracked.
    *
    * Should store initial value so that it could be retrieved as persisted stage of computation.
    */
  def initialize[State: Codec](sagaID: SagaID, initialValue: State): F[TransactionID]

  /** Update state of ongoing transaction with success.
    *
    * Should set the current state to the passed value and clear last error in storage.
    */
  def setValue[State: Codec](transactionID: TransactionID, newValue: State): F[Unit]

  /** Update state of ongoing computation with failure.
    *
    *  Should leave the state of computation and set last error to passed value.
    */
  def setError[Err: Codec](transactionID: TransactionID, newErr: Err): F[Unit]

  /** Mark transaction as finished.
    *
    * Should prevent transaction from ever appearing in unfinished stream.
    */
  def finish(transactionID: TransactionID): F[Unit]

  /** Stream unfinished transactions.
    *
    * Should stream all transaction that were not finished. Transaction marked as finished must never appear here.
    * This implementations isn't responsible for filtering out the transactions that were started but don't require
    * resume.
    */
  def unfinished[Err: Codec, State: Codec](
    sagaID: SagaID
  ): fs2.Stream[F, (TransactionID, TransactionState[Err, State])]
}

class SagaMaterializer[F[_]: Monad: Timer](implicit persistence: SagaPersistence[F]) {

  def materialize[Err: Codec, State: Codec, Out](
    sagaID: SagaID,
    saga:   Saga[F, Err, State, Out]
  ): SagaExecutor[F, State, Out] =
    new SagaExecutor.Impl[F, Err, State, Out](sagaID, saga, persistence)
}

trait SagaExecutor[F[_], State, Out] {

  /** Starts execution of a new transaction. */
  def start(a: State): F[Out]

  /** Resumes execution of transactions that were interrupted by e.g. crash of the system. */
  def resume: fs2.Stream[F, Out]
}

object SagaExecutor {

  private[volsunga] final class Impl[F[_]: Monad: Timer, Err: Codec, State: Codec, Out](
    sagaID:      SagaID,
    saga:        Saga[F, Err, State, Out],
    persistence: SagaPersistence[F]
  ) extends SagaExecutor[F, State, Out] {

    private val Saga.Impl(dispatch, isDone) = saga

    // TODO: store new ids somewhere so what they would be skipped from the resume stream
    // TODO: and remove them once processing is done

    private def progress(txID: TransactionID, from: TransactionState[Err, State]): F[Out] = from.tailRecM[F, Out] {
      case TransactionState(s, errorOpt) =>
        val Transition.Impl(run, delayOpt, handleOpt) = dispatch(s)
        errorOpt match {
          case Some(error) =>
            for {
              _ <- delayOpt.fold(Monad[F].unit)(Timer[F].sleep)
              newValue <- handleOpt.fold(s.pure[F])(_(error))
              _ <- persistence.setValue(txID, newValue)
            } yield TransactionState(newValue, None).asLeft[Out]
          case None =>
            isDone.lift(s) match {
              case Some(out) =>
                persistence.finish(txID) >> out.asRight[TransactionState[Err, State]].pure[F]
              case None =>
                run.flatMap {
                  case Left(err) =>
                    persistence.setError(txID, err) >> TransactionState(s, err.some).asLeft[Out].pure[F]
                  case Right(newS) =>
                    persistence.setValue(txID, newS) >> TransactionState(newS, none[Err]).asLeft[Out].pure[F]
                }
            }
        }
    }

    def start(a: State): F[Out] =
      for {
        transactionID <- persistence.initialize[State](sagaID, a)
        result <- progress(transactionID, TransactionState(a, None))
      } yield result

    def resume: fs2.Stream[F, Out] = persistence.unfinished[Err, State](sagaID).evalMap((progress _).tupled)
  }
}
