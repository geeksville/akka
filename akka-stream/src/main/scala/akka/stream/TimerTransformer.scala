/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.collection.immutable
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import akka.actor.ActorContext
import akka.actor.Cancellable

/**
 * [[Transformer]] with support for scheduling named timer events.
 */
abstract class TimerTransformer[-T, +U] extends Transformer[T, U] {
  import TimerTransformer._
  private val timers = mutable.Map[String, (Int, Cancellable)]()
  private val timerIdGen = Iterator from 1

  private var context: Option[ActorContext] = None
  // when scheduling before `start` we must queue the operations
  private var queued = List.empty[Queued]

  /**
   * INTERNAL API
   */
  private[akka] final def start(ctx: ActorContext): Unit = {
    context = Some(ctx)
    queued.reverse.foreach {
      case QueuedSchedule(timerName, interval)  ⇒ schedulePeriodically(timerName, interval)
      case QueuedScheduleOnce(timerName, delay) ⇒ scheduleOnce(timerName, delay)
      case QueuedCancelTimer(timerName)         ⇒ cancelTimer(timerName)
    }
    queued = Nil
  }

  /**
   * INTERNAL API
   */
  private[akka] final def stop(): Unit = {
    timers.foreach { case (_, (_, c)) ⇒ c.cancel() }
    timers.clear()
  }

  /**
   * Schedule named timer to call [[#onTimer]] periodically with the given interval.
   * Any existing timer with the same name will automatically be canceled before
   * adding the new timer.
   */
  def schedulePeriodically(timerName: String, interval: FiniteDuration): Unit =
    context match {
      case Some(ctx) ⇒
        cancelTimer(timerName)
        val id = timerIdGen.next()
        val c = ctx.system.scheduler.schedule(interval, interval, ctx.self,
          Scheduled(timerName, id, repeating = true))(ctx.dispatcher)
        timers(timerName) = (id, c)
      case None ⇒
        queued = QueuedSchedule(timerName, interval) :: queued
    }

  /**
   * Schedule named timer to call [[#onTimer]] after given delay.
   * Any existing timer with the same name will automatically be canceled before
   * adding the new timer.
   */
  def scheduleOnce(timerName: String, delay: FiniteDuration): Unit =
    context match {
      case Some(ctx) ⇒
        cancelTimer(timerName)
        val id = timerIdGen.next()
        val c = ctx.system.scheduler.scheduleOnce(delay, ctx.self,
          Scheduled(timerName, id, repeating = false))(ctx.dispatcher)
        timers(timerName) = (id, c)
      case None ⇒
        queued = QueuedScheduleOnce(timerName, delay) :: queued
    }

  /**
   * Cancel named timer, ensuring that the [[#onTimer]] is not subsequently called.
   * @param name of the timer to cancel
   */
  def cancelTimer(timerName: String): Unit =
    timers.get(timerName).foreach {
      case (_, c) ⇒
        c.cancel()
        timers -= timerName
    }

  /**
   * Inquire whether the named timer is still active. Returns true unless the
   * timer does not exist, has previously been canceled or if it was a
   * single-shot timer that was already triggered.
   */
  final def isTimerActive(timerName: String): Boolean = timers contains timerName

  /**
   * INTERNAL API
   */
  private[akka] def onScheduled(scheduled: Scheduled): immutable.Seq[U] = {
    val Id = scheduled.timerId
    timers.get(scheduled.timerName) match {
      case Some((Id, _)) ⇒
        if (!scheduled.repeating) timers -= scheduled.timerName
        onTimer(scheduled.timerName)
      case _ ⇒ Nil // already canceled, or re-scheduled
    }
  }

  /**
   * Will be called when the scheduled timer is triggered.
   * @param name of the scheduled timer
   */
  def onTimer(timerName: String): immutable.Seq[U]
}

/**
 * INTERNAL API
 */
private[akka] object TimerTransformer {
  case class Scheduled(timerName: String, timerId: Int, repeating: Boolean)

  sealed trait Queued
  case class QueuedSchedule(timerName: String, interval: FiniteDuration) extends Queued
  case class QueuedScheduleOnce(timerName: String, delay: FiniteDuration) extends Queued
  case class QueuedCancelTimer(timerName: String) extends Queued
}

