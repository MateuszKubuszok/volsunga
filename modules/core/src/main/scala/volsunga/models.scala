package volsunga

import scala.concurrent.duration.FiniteDuration

sealed trait Operation[F[_], E, A] extends Product with Serializable {

  def delayHandle(delay: FiniteDuration): Operation[F, E, A]

  def handleWith(handler: E => F[A]): Operation[F, E, A]
}

object Operation {

  private[volsunga] final case class Impl[F[_], E, A](
    run:    F[Either[E, A]],
    delay:  Option[FiniteDuration],
    handle: Option[E => F[A]]
  ) extends Operation[F, E, A] {

    override def delayHandle(delay: FiniteDuration): Operation[F, E, A] = copy(delay = Some(delay))

    override def handleWith(handler: E => F[A]): Operation[F, E, A] = copy(handle = Some(handler))
  }

  def apply[F[_], E, A](run: F[Either[E, A]]): Operation[F, E, A] = Impl(run = run, delay = None, handle = None)
}

sealed trait Saga[F[_], E, A, B] extends Product with Serializable {

  def materializeAs(sagaID: SagaID)(
    implicit materializer:  SagaMaterializer[F],
    eCodec:                 Codec[E],
    aCodec:                 Codec[A]
  ): (A => F[B], fs2.Stream[F, B]) =
    materializer.materialize(sagaID, this)
}

object Saga {

  private[volsunga] final case class Impl[F[_], E, A, B](
    dispatch: A => Operation[F, E, A],
    isDone:   PartialFunction[A, B]
  ) extends Saga[F, E, A, B]

  def apply[F[_], E, A, B](dispatch: A => Operation[F, E, A])(isDone: PartialFunction[A, B]): Saga[F, E, A, B] =
    Impl(dispatch = dispatch, isDone = isDone)
}
