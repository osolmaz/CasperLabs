package io.casperlabs.comm.gossiping

// A copy of https://github.com/monix/monix/blob/3.0.0/monix-tail/shared/src/main/scala/monix/tail/internal/IterantFromReactivePublisher.scala
// until https://github.com/monix/monix/issues/1180 is fixed.

import cats.effect.Async
import monix.execution.atomic.Atomic
import monix.execution.atomic.PaddingStrategy.LeftRight128
import monix.execution.rstreams.SingleAssignSubscription
import monix.tail.Iterant
import monix.tail.Iterant.{Last, Next, NextBatch, Scope}
import monix.tail.batches.Batch
import org.reactivestreams.{Publisher, Subscriber, Subscription}
import scala.annotation.tailrec
import scala.collection.immutable.Queue

private[gossiping] object IterantFromReactivePublisher {

  /**
    * Implementation for `Iterant.fromReactivePublisher`.
    */
  def apply[F[_], A](pub: Publisher[A], requestCount: Int, eagerBuffer: Boolean)(
      implicit F: Async[F]
  ): Iterant[F, A] =
    if (requestCount < 1) {
      Iterant.raiseError(new IllegalArgumentException("requestSize must be greater than 1"))
    } else {
      val acquire =
        F.delay {
          val out = new IterantSubscriber[F, A](requestCount, eagerBuffer)
          pub.subscribe(out)
          out
        }

      Scope[F, IterantSubscriber[F, A], A](acquire, _.start, (out, _) => F.delay(out.cancel()))
    }

  private final class IterantSubscriber[F[_], A](bufferSize: Int, eagerBuffer: Boolean)(
      implicit F: Async[F]
  ) extends Subscriber[A] {

    private[this] val sub   = SingleAssignSubscription()
    private[this] val state = Atomic.withPadding(Uninitialized: State[F, A], LeftRight128)

    def start: F[Iterant[F, A]] =
      F.async { cb =>
        if (initialize()) {
          sub.request(
            // Requesting unlimited?
            if (bufferSize < Int.MaxValue) bufferSize.toLong
            else Long.MaxValue
          )
        }
        // Go, go, go
        take(cb)
      }

    private def initialize(): Boolean =
      state.compareAndSet(Uninitialized, Empty(bufferSize))

    private[this] val generate: (Int => F[Iterant[F, A]]) = {
      if (eagerBuffer) {
        val task = F.async[Iterant[F, A]](take)
        toReceive => { if (toReceive == 0) sub.request(bufferSize.toLong); task }
      } else { toReceive =>
        F.async { cb =>
          if (toReceive == 0) sub.request(bufferSize.toLong); take(cb)
        }
      }
    }

    private def decrementToReceive(toReceive: Int, n: Int): Int =
      if (bufferSize < Int.MaxValue) {
        val value = toReceive - n
        if (value < 0)
          throw new IllegalArgumentException("Received more events than requested")
        else
          value
      } else {
        toReceive
      }

    private def updateToReceive(toReceive: Int): Int =
      if (toReceive == 0) bufferSize
      else toReceive

    @tailrec def onNext(a: A): Unit =
      state.get match {
        case Uninitialized =>
          initialize()
          onNext(a)

        case current @ Enqueue(queue, length, toReceive) =>
          if (!state.compareAndSet(current, Enqueue(queue.enqueue(a), length + 1, toReceive)))
            onNext(a)

        case current @ Take(cb, toReceive) =>
          val toReceive2 = decrementToReceive(toReceive, 1)
          if (!state.compareAndSet(current, Empty(updateToReceive(toReceive2))))
            onNext(a)
          else
            cb(Right(Next(a, generate(toReceive2))))

        case Canceled =>
          () // was canceled, ignore event

        case Stop(_) =>
          // TODO:
          throw new IllegalStateException("onComplete/onError after onNext is not allowed")
      }

    @tailrec private def finish(fa: Iterant[F, A]): Unit =
      state.get match {
        case Uninitialized =>
          initialize()
          finish(fa)

        case current @ Enqueue(queue, length, _) =>
          val update: Iterant[F, A] = length match {
            case 0 => fa
            case 1 =>
              val elem = queue.dequeue._1
              if (fa == Iterant.empty) Last(elem)
              else Next(elem, F.pure(fa))
            case _ =>
              NextBatch[F, A](Batch.fromSeq(queue), F.pure(fa))
          }

          if (!state.compareAndSet(current, Stop(update))) {
            finish(fa)
          }

        case current @ Take(cb, _) =>
          if (state.compareAndSet(current, Stop(fa)))
            cb(Right(fa))
          else
            finish(fa)

        case Canceled =>
          () // was canceled, ignore event

        case Stop(_) =>
          throw new IllegalStateException("was already completed")
      }

    def onError(ex: Throwable): Unit =
      finish(Iterant.raiseError(ex))

    def onComplete(): Unit =
      finish(Iterant.empty)

    @tailrec private def take(cb: Either[Throwable, Iterant[F, A]] => Unit): Unit =
      state.get match {
        case Uninitialized =>
          initialize()
          take(cb)

        case current @ Enqueue(queue, length, toReceive) =>
          if (length == 0) {
            val update = Take(cb, toReceive)
            if (!state.compareAndSet(current, update)) take(cb)
          } else {
            val toReceive2 = decrementToReceive(toReceive, length)
            if (state.compareAndSet(current, Empty(updateToReceive(toReceive2)))) {
              val stream = length match {
                case 1 => Next(queue.dequeue._1, generate(toReceive2))
                case _ => NextBatch(Batch.fromSeq(queue), generate(toReceive2))
              }
              cb(Right(stream))
            } else {
              take(cb) // retry
            }
          }

        case Stop(fa) =>
          cb(Right(fa))

        case Canceled =>
          () // canceled, ignore event

        case Take(_, _) =>
          cb(Left(new IllegalStateException("Back-pressure contract violation!")))
      }

    def onSubscribe(s: Subscription): Unit =
      sub := s

    def cancel(): Unit =
      state.getAndSet(Canceled) match {
        case Canceled | Stop(_) => ()
        case _                  => sub.cancel()
      }
  }

  private sealed abstract class State[+F[_], +A]

  private case object Uninitialized extends State[Nothing, Nothing]

  private final case class Stop[F[_], A](fa: Iterant[F, A]) extends State[F, A]

  private final case class Enqueue[F[_], A](queue: Queue[A], length: Int, toReceive: Int)
      extends State[F, A]

  private final case class Take[F[_], A](cb: Either[Nothing, Iterant[F, A]] => Unit, toReceive: Int)
      extends State[F, A]

  private case object Canceled extends State[Nothing, Nothing]

  private def Empty[F[_], A](toReceive: Int): State[F, A] =
    Enqueue(Queue.empty, 0, toReceive)

}
