package volsunga

import cats.Monad
import cats.syntax.all._

sealed trait PaymentState extends Product with Serializable
object PaymentState {

  final case class Scheduled(metadata: String) extends PaymentState

  final case class RequestAccepted(metadata: String) extends PaymentState

  final case class RequestErred(metadata: String) extends PaymentState

  final case class SuccessCallback(metadata: String) extends PaymentState

  final case class FailureCallback(metadata: String) extends PaymentState

  final case class InvoiceSent(metadata: String) extends PaymentState

  final case class RequestedUserAction(metadata: String) extends PaymentState
}

final case class PaymentError(metadata: String)

sealed trait PaymentResult extends Product with Serializable
object PaymentResult {

  final case class Invoiced(metadata: String) extends PaymentResult

  final case class Failure(metadata: String) extends PaymentResult
}

abstract class ExampleSaga[F[_]: Monad](
  implicit materializer: SagaMaterializer[F],
  stateCodec:            Codec[PaymentState],
  errorCodec:            Codec[PaymentError]
) {

  protected def sagaID: SagaID

  protected def requestPayment(metadata: String): F[Either[PaymentError, PaymentState.RequestAccepted]]

  protected def handleRequestError(error: PaymentError): F[PaymentState.RequestErred]

  protected def awaitCallback(metadata: String): F[Either[PaymentError, PaymentState.SuccessCallback]]

  protected def handleFailedPayment(error: PaymentError): F[PaymentState.FailureCallback]

  protected def retryOrRequestUserAction(metadata: String): F[Either[PaymentError, PaymentState]]

  protected def sendInvoice(metadata: String): F[Either[PaymentError, PaymentState.InvoiceSent]]

  protected def sendUserAction(metadata: String): F[Either[PaymentError, PaymentState.RequestedUserAction]]

  lazy val executor: SagaExecutor[F, PaymentState, PaymentResult] = Saga[F, PaymentError, PaymentState, PaymentResult] {
    case PaymentState.Scheduled(metadata) =>
      Transition {
        requestPayment(metadata).widen[Either[PaymentError, PaymentState]]
      }.handleWith(handleRequestError(_).widen)
    case PaymentState.RequestAccepted(metadata) =>
      Transition {
        awaitCallback(metadata).widen[Either[PaymentError, PaymentState]]
      }.handleWith(handleFailedPayment(_).widen)
    case PaymentState.RequestErred(metadata) =>
      Transition {
        retryOrRequestUserAction(metadata).widen[Either[PaymentError, PaymentState]]
      }
    case PaymentState.SuccessCallback(metadata) =>
      Transition {
        sendInvoice(metadata).widen[Either[PaymentError, PaymentState]]
      }
    case PaymentState.FailureCallback(metadata) =>
      Transition {
        retryOrRequestUserAction(metadata).widen[Either[PaymentError, PaymentState]]
      }
    case terminal @ PaymentState.InvoiceSent(_) =>
      Transition.loopState(terminal)
    case terminal @ PaymentState.RequestedUserAction(_) =>
      Transition.loopState(terminal)
  } {
    case PaymentState.InvoiceSent(metadata)         => PaymentResult.Invoiced(metadata)
    case PaymentState.RequestedUserAction(metadata) => PaymentResult.Failure(metadata)
  }.materializeAs(sagaID)
}
