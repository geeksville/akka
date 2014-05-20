/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.util.LinkedList
import akka.stream.MaterializerSettings
import akka.stream.TimerTransformer

/**
 * INTERNAL API
 */
private[akka] class TimerTransformerProcessorsImpl(
  _settings: MaterializerSettings,
  transformer: TimerTransformer[Any, Any])
  extends TransformProcessorImpl(_settings, transformer) {
  import TimerTransformer._

  override def preStart(): Unit = {
    super.preStart()
    transformer.start(context)
  }

  override def postStop(): Unit = {
    super.postStop()
    transformer.stop()
  }

  val schedulerInputs: Inputs = new DefaultInputTransferStates {
    val queue = new LinkedList[Any]

    override def dequeueInputElement(): Any = queue.removeFirst()

    override def subreceive: SubReceive = new SubReceive({
      case s: Scheduled ⇒
        transformer.onScheduled(s) foreach { elem ⇒
          queue.add(elem)
        }
        pump()
    })

    override def cancel(): Unit = ()
    override def isClosed: Boolean = false
    override def inputsDepleted: Boolean = false
    override def inputsAvailable: Boolean = !queue.isEmpty
  }

  override def receive = super.receive orElse schedulerInputs.subreceive

  object NeedsInputAndDemandOrCompletionOrScheduled extends TransferState {
    def isReady = {
      ((primaryInputs.inputsAvailable || schedulerInputs.inputsAvailable || transformer.isComplete) &&
        primaryOutputs.demandAvailable) || primaryInputs.inputsDepleted
    }
    def isCompleted = false
  }

  private val runningPhase: TransferPhase = TransferPhase(NeedsInputAndDemandOrCompletionOrScheduled) { () ⇒
    if (primaryInputs.inputsDepleted || (transformer.isComplete && !schedulerInputs.inputsAvailable)) {
      emits = transformer.onTermination(errorEvent)
      emitAndThen(completedPhase)
    } else if (schedulerInputs.inputsAvailable) {
      emits = List(schedulerInputs.dequeueInputElement())
      emitAndThen(running)
    } else {
      val e = primaryInputs.dequeueInputElement()
      emits = transformer.onNext(e)
      emitAndThen(running)
    }
  }

  override def running: TransferPhase = runningPhase

}
