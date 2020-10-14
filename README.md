# Volsunga

DSL for building orchestration-based Sagas with Cats and FS2.

## Motivation

Project was inspired by [Akka DDD](https://github.com/pawelkaczor/akka-ddd) but
I also based some ideas on my [Useless](https://github.com/MateuszKubuszok/useless) library experiment.

The idea is that we should be able to describe our Saga as a finite state machine,
with ADTs and pattern matching used to describe the states, and some way of persistence
of the stages of computation to let us resume transaction if JVM crashes or terminates.

## Example

Each saga must have a distinct ID so that code handling coordination could be paired
with persisted data.

```scala
def sagaID: SagaID // must be consistent between restarts
```

Then we can draw on paper saga a finite state machine. Then we can model it as ADT:

```scala
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
```

and describe transitions as functions

```scala
def requestPayment(metadata: String): F[Either[PaymentError, PaymentState.RequestAccepted]]
def handleRequestError(error: PaymentError): F[PaymentState.RequestErred]
def awaitCallback(metadata: String): F[Either[PaymentError, PaymentState.SuccessCallback]]
def handleFailedPayment(error: PaymentError): F[PaymentState.FailureCallback]
def retryOrRequestUserAction(metadata: String): F[Either[PaymentError, PaymentState]]
def sendInvoice(metadata: String): F[Either[PaymentError, PaymentState.InvoiceSent]]
def sendUserAction(metadata: String): F[Either[PaymentError, PaymentState.RequestedUserAction]]
```

Next, we can add some `PaymentError` to help with error information persistence
and recovery and some `PaymentResult` to output the results of computation (if necessary).

The only missing pieces are implicitly supplied implementation of `Codecs` and
`SagaMaterializer` and we are able to describe our orchestration:

```scala
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
```

And viola! We can create a new transaction by running:

```scala
executor.start(initialState)
```

and resume interrupted transaction by calling

```scala
executor.resume
```
