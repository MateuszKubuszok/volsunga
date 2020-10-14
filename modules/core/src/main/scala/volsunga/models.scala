package volsunga

import cats.syntax.all._
import cats.Applicative

import scala.concurrent.duration.FiniteDuration

sealed trait Transition[F[_], Err, State] extends Product with Serializable {

  def delayHandle(delay: FiniteDuration): Transition[F, Err, State]

  def handleWith(handler: Err => F[State]): Transition[F, Err, State]
}

object Transition {

  private[volsunga] final case class Impl[F[_], Err, State](
    run:    F[Either[Err, State]],
    delay:  Option[FiniteDuration],
    handle: Option[Err => F[State]]
  ) extends Transition[F, Err, State] {

    override def delayHandle(delay: FiniteDuration): Transition[F, Err, State] = copy(delay = Some(delay))

    override def handleWith(handler: Err => F[State]): Transition[F, Err, State] = copy(handle = Some(handler))
  }

  def apply[F[_], Err, State](run: F[Either[Err, State]]): Transition[F, Err, State] =
    Impl(run = run, delay = None, handle = None)

  def loopState[F[_]: Applicative, Err, State](state: State): Transition[F, Err, State] =
    apply(state.asRight[Err].pure[F])
}

sealed trait Saga[F[_], Err, State, Out] extends Product with Serializable {

  def materializeAs(sagaID: SagaID)(
    implicit materializer:  SagaMaterializer[F],
    eCodec:                 Codec[Err],
    aCodec:                 Codec[State]
  ): SagaExecutor[F, State, Out] =
    materializer.materialize(sagaID, this)
}

object Saga {

  private[volsunga] final case class Impl[F[_], Err, State, Out](
    dispatch: State => Transition[F, Err, State],
    isDone:   PartialFunction[State, Out]
  ) extends Saga[F, Err, State, Out]

  def apply[F[_], Err, State, Out](
    dispatch: State => Transition[F, Err, State]
  )(isDone:   PartialFunction[State, Out]): Saga[F, Err, State, Out] =
    Impl(dispatch = dispatch, isDone = isDone)
}
