package kvstore

import scala.collection.mutable
import akka.actor.{Cancellable, Props, Actor, ActorRef}
import scala.concurrent.duration._
import akka.event.LoggingReceive

object Replicator {
  case class Replicate(key: String, valueOption: Option[String], id: Long)
  case class Replicated(key: String, id: Long)
  
  case class Snapshot(key: String, valueOption: Option[String], seq: Long)
  case class SnapshotAck(key: String, seq: Long)

  def props(replica: ActorRef): Props = Props(new Replicator(replica))
  
  case class ReplicationContext(id: Long, originator: ActorRef, schedule: Cancellable)
}

class Replicator(val replica: ActorRef) extends Actor {
  import Replicator._
  import context.dispatcher

  private val replications = mutable.Map.empty[Long, ReplicationContext]

  def receive = replicator(0)

  override def postStop() {
    replications.values foreach { case ReplicationContext(_, _, schedule) =>
      schedule.cancel()
    }
  }

  private def replicator(sequenceCounter: Long): Receive = LoggingReceive {
    case Replicate(key, valueOption, id) =>
      val schedule = context.system.scheduler.schedule(0.milliseconds, 100.milliseconds, replica, Snapshot(key, valueOption, sequenceCounter))
      replications(sequenceCounter) = ReplicationContext(id, sender, schedule)
      context.become(replicator(sequenceCounter + 1))

    case SnapshotAck(key, seq) =>
      if (replications.contains(seq)) {
        val ReplicationContext(id, originator, schedule) = replications(seq)
        originator ! Replicated(key, id)
        schedule.cancel()

        replications -= seq
      }
  }
}
