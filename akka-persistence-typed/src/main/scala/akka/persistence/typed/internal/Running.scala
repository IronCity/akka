/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import scala.annotation.tailrec
import scala.collection.immutable
import scala.util.Failure
import scala.util.Success

import akka.Done
import akka.actor.UnhandledMessage
import akka.actor.typed.Behavior
import akka.actor.typed.Signal
import akka.actor.typed.internal.PoisonPill
import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.Behaviors
import akka.annotation.InternalApi
import akka.persistence.JournalProtocol._
import akka.persistence._
import akka.persistence.journal.Tagged
import akka.persistence.typed.Callback
import akka.persistence.typed.EventRejectedException
import akka.persistence.typed.SideEffect
import akka.persistence.typed.Stop
import akka.persistence.typed.UnstashAll
import akka.persistence.typed.scaladsl.Effect

/**
 * INTERNAL API
 *
 * Conceptually fourth (of four) -- also known as 'final' or 'ultimate' -- form of EventSourcedBehavior.
 *
 * In this phase recovery has completed successfully and we continue handling incoming commands,
 * as well as persisting new events as dictated by the user handlers.
 *
 * This behavior operates in two phases (also behaviors):
 * - HandlingCommands - where the command handler is invoked for incoming commands
 * - PersistingEvents - where incoming commands are stashed until persistence completes
 *
 * This is implemented as such to avoid creating many EventSourced Running instances,
 * which perform the Persistence extension lookup on creation and similar things (config lookup)
 *
 * See previous [[ReplayingEvents]].
 * TODO rename
 */
@InternalApi
private[akka] object Running {

  final case class RunningState[State](
    seqNr:              Long,
    state:              State,
    receivedPoisonPill: Boolean
  ) {

    def nextSequenceNr(): RunningState[State] =
      copy(seqNr = seqNr + 1)

    def updateLastSequenceNr(persistent: PersistentRepr): RunningState[State] =
      if (persistent.sequenceNr > seqNr) copy(seqNr = persistent.sequenceNr) else this

    def applyEvent[C, E](setup: BehaviorSetup[C, E, State], event: E): RunningState[State] = {
      val updated = setup.eventHandler(state, event)
      copy(state = updated)
    }
  }

  def apply[C, E, S](setup: BehaviorSetup[C, E, S], state: RunningState[S]): Behavior[InternalProtocol] =
    new Running(setup.setMdc(MDC.RunningCmds)).handlingCommands(state)
}

// ===============================================

/** INTERNAL API */
@InternalApi private[akka] class Running[C, E, S](
  override val setup: BehaviorSetup[C, E, S])
  extends JournalInteractions[C, E, S] with StashManagement[C, E, S] {
  import InternalProtocol._
  import Running.RunningState

  private val runningCmdsMdc = MDC.create(setup.persistenceId, MDC.RunningCmds)
  private val persistingEventsMdc = MDC.create(setup.persistenceId, MDC.PersistingEvents)
  private val storingSnapshotMdc = MDC.create(setup.persistenceId, MDC.StoringSnapshot)

  def handlingCommands(state: RunningState[S]): Behavior[InternalProtocol] = {

    def onCommand(state: RunningState[S], cmd: C): Behavior[InternalProtocol] = {
      val effect = setup.commandHandler(state.state, cmd)
      applyEffects(cmd, state, effect.asInstanceOf[EffectImpl[E, S]]) // TODO can we avoid the cast?
    }

    @tailrec def applyEffects(
      msg:         Any,
      state:       RunningState[S],
      effect:      Effect[E, S],
      sideEffects: immutable.Seq[SideEffect[S]] = Nil
    ): Behavior[InternalProtocol] = {
      if (setup.log.isDebugEnabled && !effect.isInstanceOf[CompositeEffect[_, _]])
        setup.log.debug(
          s"Handled command [{}], resulting effect: [{}], side effects: [{}]",
          msg.getClass.getName, effect, sideEffects.size)

      effect match {
        case CompositeEffect(eff, currentSideEffects) ⇒
          // unwrap and accumulate effects
          applyEffects(msg, state, eff, currentSideEffects ++ sideEffects)

        case Persist(event) ⇒
          // apply the event before persist so that validation exception is handled before persisting
          // the invalid event, in case such validation is implemented in the event handler.
          // also, ensure that there is an event handler for each single event
          val newState = state.applyEvent(setup, event)

          val eventToPersist = adaptEvent(event)

          val newState2 = internalPersist(newState, eventToPersist)

          val shouldSnapshotAfterPersist = setup.snapshotWhen(newState2.state, event, newState2.seqNr)

          persistingEvents(newState2, numberOfEvents = 1, shouldSnapshotAfterPersist, sideEffects)

        case PersistAll(events) ⇒
          if (events.nonEmpty) {
            // apply the event before persist so that validation exception is handled before persisting
            // the invalid event, in case such validation is implemented in the event handler.
            // also, ensure that there is an event handler for each single event
            var seqNr = state.seqNr
            val (newState, shouldSnapshotAfterPersist) = events.foldLeft((state, false)) {
              case ((currentState, snapshot), event) ⇒
                seqNr += 1
                val shouldSnapshot = snapshot || setup.snapshotWhen(currentState.state, event, seqNr)
                (currentState.applyEvent(setup, event), shouldSnapshot)
            }

            val eventsToPersist = events.map(adaptEvent)

            val newState2 = internalPersistAll(eventsToPersist, newState)

            persistingEvents(newState2, events.size, shouldSnapshotAfterPersist, sideEffects)

          } else {
            // run side-effects even when no events are emitted
            tryUnstashOne(applySideEffects(sideEffects, state))
          }

        case _: PersistNothing.type ⇒
          tryUnstashOne(applySideEffects(sideEffects, state))

        case _: Unhandled.type ⇒
          import akka.actor.typed.scaladsl.adapter._
          setup.context.system.toUntyped.eventStream.publish(
            UnhandledMessage(msg, setup.context.system.toUntyped.deadLetters, setup.context.self.toUntyped))
          tryUnstashOne(applySideEffects(sideEffects, state))

        case _: Stash.type ⇒
          stashUser(IncomingCommand(msg))
          tryUnstashOne(applySideEffects(sideEffects, state))
      }
    }

    def adaptEvent(event: E): Any = {
      val tags = setup.tagger(event)
      val adaptedEvent = setup.eventAdapter.toJournal(event)
      if (tags.isEmpty)
        adaptedEvent
      else
        Tagged(adaptedEvent, tags)
    }

    setup.setMdc(runningCmdsMdc)

    Behaviors.receiveMessage[InternalProtocol] {
      case IncomingCommand(c: C @unchecked) ⇒ onCommand(state, c)
      case SnapshotterResponse(r) ⇒
        setup.log.warning("Unexpected SnapshotterResponse {}", r)
        Behaviors.unhandled
      case _ ⇒ Behaviors.unhandled
    }.receiveSignal {
      case (_, PoisonPill) ⇒
        if (isInternalStashEmpty && !isUnstashAllInProgress) Behaviors.stopped
        else handlingCommands(state.copy(receivedPoisonPill = true))
    }

  }

  // ===============================================

  def persistingEvents(
    state:                      RunningState[S],
    numberOfEvents:             Int,
    shouldSnapshotAfterPersist: Boolean,
    sideEffects:                immutable.Seq[SideEffect[S]]
  ): Behavior[InternalProtocol] = {
    setup.setMdc(persistingEventsMdc)
    new PersistingEvents(state, numberOfEvents, shouldSnapshotAfterPersist, sideEffects)
  }

  /** INTERNAL API */
  @InternalApi private[akka] class PersistingEvents(
    var state:                  RunningState[S],
    numberOfEvents:             Int,
    shouldSnapshotAfterPersist: Boolean,
    var sideEffects:            immutable.Seq[SideEffect[S]])
    extends AbstractBehavior[InternalProtocol] {

    private var eventCounter = 0

    override def onMessage(msg: InternalProtocol): Behavior[InternalProtocol] = {
      msg match {
        case JournalResponse(r)                ⇒ onJournalResponse(r)
        case in: IncomingCommand[C @unchecked] ⇒ onCommand(in)
        case SnapshotterResponse(r) ⇒
          setup.log.warning("Unexpected SnapshotterResponse {}", r)
          Behaviors.unhandled
        case RecoveryTickEvent(_)  ⇒ Behaviors.unhandled
        case RecoveryPermitGranted ⇒ Behaviors.unhandled
      }
    }

    def onCommand(cmd: IncomingCommand[C]): Behavior[InternalProtocol] = {
      if (state.receivedPoisonPill) {
        if (setup.settings.logOnStashing) setup.log.debug(
          "Discarding message [{}], because actor is to be stopped", cmd)
        Behaviors.unhandled
      } else {
        stashInternal(cmd)
        this
      }
    }

    final def onJournalResponse(
      response: Response): Behavior[InternalProtocol] = {
      setup.log.debug("Received Journal response: {}", response)

      def onWriteResponse(p: PersistentRepr): Behavior[InternalProtocol] = {
        state = state.updateLastSequenceNr(p)
        eventCounter += 1

        // only once all things are applied we can revert back
        if (eventCounter < numberOfEvents) this
        else {
          if (shouldSnapshotAfterPersist && state.state != null) {
            internalSaveSnapshot(state)
            storingSnapshot(state, sideEffects)
          } else
            tryUnstashOne(applySideEffects(sideEffects, state))
        }
      }

      response match {
        case WriteMessageSuccess(p, id) ⇒
          if (id == setup.writerIdentity.instanceId)
            onWriteResponse(p)
          else this

        case WriteMessageRejected(p, cause, id) ⇒
          if (id == setup.writerIdentity.instanceId) {
            throw new EventRejectedException(setup.persistenceId, p.sequenceNr, cause)
          } else this

        case WriteMessageFailure(p, cause, id) ⇒
          if (id == setup.writerIdentity.instanceId)
            throw new JournalFailureException(setup.persistenceId, p.sequenceNr, p.payload.getClass.getName, cause)
          else this

        case WriteMessagesSuccessful ⇒
          // ignore
          this

        case WriteMessagesFailed(_) ⇒
          // ignore
          this // it will be stopped by the first WriteMessageFailure message; not applying side effects

        case _ ⇒
          // ignore all other messages, since they relate to recovery handling which we're not dealing with in Running phase
          Behaviors.unhandled
      }
    }

    override def onSignal: PartialFunction[Signal, Behavior[InternalProtocol]] = {
      case PoisonPill ⇒
        // wait for journal responses before stopping
        state = state.copy(receivedPoisonPill = true)
        this
    }

  }

  // ===============================================

  def storingSnapshot(
    state:       RunningState[S],
    sideEffects: immutable.Seq[SideEffect[S]]
  ): Behavior[InternalProtocol] = {
    setup.setMdc(storingSnapshotMdc)

    def onCommand(cmd: IncomingCommand[C]): Behavior[InternalProtocol] = {
      if (state.receivedPoisonPill) {
        if (setup.settings.logOnStashing) setup.log.debug(
          "Discarding message [{}], because actor is to be stopped", cmd)
        Behaviors.unhandled
      } else {
        stashUser(cmd)
        storingSnapshot(state, sideEffects)
      }
    }

    def onSnapshotterResponse(response: SnapshotProtocol.Response): Unit = {
      response match {
        case SaveSnapshotSuccess(meta) ⇒
          setup.onSnapshot(meta, Success(Done))
        case SaveSnapshotFailure(meta, ex) ⇒
          setup.onSnapshot(meta, Failure(ex))

        // FIXME not implemented
        case DeleteSnapshotFailure(_, _)  ⇒ ???
        case DeleteSnapshotSuccess(_)     ⇒ ???
        case DeleteSnapshotsFailure(_, _) ⇒ ???
        case DeleteSnapshotsSuccess(_)    ⇒ ???

        // ignore LoadSnapshot messages
        case _                            ⇒
      }
    }

    Behaviors.receiveMessage[InternalProtocol] {
      case cmd: IncomingCommand[C] @unchecked ⇒
        onCommand(cmd)
      case SnapshotterResponse(r) ⇒
        onSnapshotterResponse(r)
        tryUnstashOne(applySideEffects(sideEffects, state))
      case _ ⇒
        Behaviors.unhandled
    }.receiveSignal {
      case (_, PoisonPill) ⇒
        // wait for snapshot response before stopping
        storingSnapshot(state.copy(receivedPoisonPill = true), sideEffects)
    }

  }

  // --------------------------

  def applySideEffects(effects: immutable.Seq[SideEffect[S]], state: RunningState[S]): Behavior[InternalProtocol] = {
    var behavior: Behavior[InternalProtocol] = handlingCommands(state)
    val it = effects.iterator

    // if at least one effect results in a `stop`, we need to stop
    // manual loop implementation to avoid allocations and multiple scans
    while (it.hasNext) {
      val effect = it.next()
      behavior = applySideEffect(effect, state, behavior)
    }

    if (state.receivedPoisonPill && isInternalStashEmpty && !isUnstashAllInProgress)
      Behaviors.stopped
    else
      behavior
  }

  def applySideEffect(
    effect:   SideEffect[S],
    state:    RunningState[S],
    behavior: Behavior[InternalProtocol]): Behavior[InternalProtocol] = {
    effect match {
      case _: Stop.type @unchecked ⇒
        Behaviors.stopped

      case _: UnstashAll.type @unchecked ⇒
        unstashAll()
        behavior

      case callback: Callback[_] ⇒
        callback.sideEffect(state.state)
        behavior

      case _ ⇒
        throw new IllegalArgumentException(s"Unsupported side effect detected [${effect.getClass.getName}]")
    }
  }

}

