package io.casperlabs.casper

import cats._
import cats.implicits._
import cats.data.WriterT
import io.casperlabs.crypto.Keys.PublicKeyBS
import java.time.Instant
import shapeless.tag.@@
import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration
import org.apache.commons.math3.util.ArithmeticUtils

package highway {
  sealed trait TimestampTag
  sealed trait TicksTag
}

package object highway {

  /** Time since Unix epoch in milliseconds. */
  type Timestamp = Long @@ TimestampTag
  def Timestamp(t: Long) = t.asInstanceOf[Timestamp]

  /** Ticks since Unix epoch in the Highway specific time unit. */
  type Ticks = Long @@ TicksTag
  object Ticks {
    def apply(t: Long) = t.asInstanceOf[Ticks]

    /** Calculate round length as 2^exp */
    def roundLength(exponent: Int) = Ticks(ArithmeticUtils.pow(2L, exponent))
  }

  implicit class InstantOps(val a: Instant) extends AnyVal {
    def plus(b: FiniteDuration) =
      a.plus(b.length, b.unit.toChronoUnit)

    def minus(b: FiniteDuration) =
      a.minus(b.length, b.unit.toChronoUnit)
  }

  /** Models a state transition of an era, returning the domain events that
    * were raised during the operation. For example a round, or handling the
    * a message may have created a new era, which is now persisted in the
    * database, but scheduling has not been started for it (as we may be in
    * playback mode).
    */
  type HighwayLog[F[_], T] = WriterT[F, Vector[HighwayEvent], T]

  object HighwayLog {
    def liftF[F[_]: Applicative, T](value: F[T]): HighwayLog[F, T] =
      WriterT.liftF(value)

    def unit[F[_]: Applicative]: HighwayLog[F, Unit] =
      ().pure[HighwayLog[F, *]]

    def tell[F[_]: Applicative](events: HighwayEvent*) =
      WriterT.tell[F, Vector[HighwayEvent]](events.toVector)
  }

  type LeaderFunction = Ticks => PublicKeyBS
}
