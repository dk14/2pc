package org.example

import scala.collection.mutable.ListBuffer
import akka.actor.{Props, ActorRef, Actor}
import scala.reflect._
import akka.pattern._
import akka.util.Timeout
import scala.concurrent.ExecutionContext.Implicits.global

case class ReqSeq[T](tid: String, rs: Seq[T])
case class Req[T](req: T)

object Process
trait Result
object Success extends Result
object Failure extends Result

trait Operation[T] { def r: Req[T]; def isCommit: Boolean }
case class Commit[T](r: Req[T]) extends Operation[T] { def isCommit = true}
case class Rollback[T](r: Req[T]) extends Operation[T] { def isCommit = false}
case object Ack
import akka.util.Timeout
import scala.concurrent.duration._

abstract class Coordinator[T, TA: ClassTag] extends Actor {
  val timeout = 5 seconds
  implicit val askTimeout = Timeout(timeout)
  def transactor(rs: ReqSeq[T]) = context.actorOf(Props(classTag[TA].runtimeClass, rs), rs.tid)
  def receive = {
    case rs: ReqSeq[T] => transactor(rs) ? Process pipeTo sender
  }
}

abstract class Transactor[T, P <: Processor[T]: ClassTag] extends Actor {
  def rs: ReqSeq[T]
  var parent: ActorRef = _
  def process(tid: String, r: Req[T]) = context.actorOf(Props[P]) ! r
  def commit = parent ! Success
  def rollback = parent ! Failure

  def decision  = //analyze stats
    if (rs.rs.size != votes.size) None
    else if (votes.forall(_._2.isCommit)) Some(Commit[T](_))
    else Some(Rollback[T](_))

  val votes = ListBuffer[(ActorRef, Operation[T])]() //voting
  val ack = ListBuffer[Ack.type]() //before final acknowledgment
  def receive = {
    case Process =>
      context.system.scheduler.scheduleOnce(5 seconds, self, "Timeout")
      for (r <- rs.rs) process(rs.tid, Req(r)); parent = sender
    case r: Operation[T] => votes += sender -> r
      for (d <- decision; (proc, req) <- votes) proc ! d(req.r)
    case Ack => ack += Ack; if (ack.size == rs.rs.size)
      if (decision.get(null).isCommit) commit else rollback
    case "Timeout" => parent ! Failure; context stop self
  }
}

trait Processor[T] extends Actor {
  def process(r: Req[T]): Operation[T]
  def complete(t: T)
  def rollback(t: T)
  def receive = {
    case r: Req[T] => sender ! process(r)
    case a: Commit[T] => complete(a.r.req); sender ! Ack
    case a: Rollback[T] => rollback(a.r.req); sender ! Ack
  }
}

