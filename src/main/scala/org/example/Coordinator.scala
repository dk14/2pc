package org.example

import scala.collection.mutable.ListBuffer
import akka.actor.{Props, ActorRef, Actor}
import scala.reflect._
import akka.pattern._
import akka.util.Timeout
import scala.concurrent.ExecutionContext.Implicits.global

trait ReqSeqLike[T] {def tid: String; def rs: Seq[T];  def count: Int = rs.size}
case class ReqSeq[T](tid: String, rs: Seq[T]) extends ReqSeqLike[T]
case class Req[T](req: T)

case class Process[T](rs: ReqSeq[T])
trait Result
object Success extends Result
object Failure extends Result

trait Operation[T] { def r: Req[T]; def isCommit: Boolean }
case class Commit[T](r: Req[T]) extends Operation[T] { def isCommit = true }
case class Rollback[T](r: Req[T]) extends Operation[T] { def isCommit = false }
case object Ack
import akka.util.Timeout
import scala.concurrent.duration._

abstract class Coordinator[T, TA: ClassTag] extends Actor {
  val timeout = 5 seconds
  implicit val askTimeout = Timeout(timeout)
  def transactor(id: String) = context.child(id).getOrElse(context.actorOf(Props(classTag[TA].runtimeClass), id))
  def receive = {
    case rs: ReqSeq[T] => transactor(rs.tid) ? Process(rs) pipeTo sender
  }
}

abstract class Transactor[T, P <: Processor[T]: ClassTag] extends Actor {
  val rs =  ListBuffer[T]()
  var expectedCount: Int = _
  var parent: ActorRef = _ //we need it to correlate with last request, context.parent cannot be used

  def process(r: Req[T]) =  context.actorOf(Props[P]) ! r
  def commit = parent ! Success
  def rollback = parent ! Failure
  def tid = self.path.name

  def decision  = //analyze votes
    if (votes.size != expectedCount) None
    else if (votes.forall(_._2.isCommit)) Some(Commit[T](_))
    else Some(Rollback[T](_))

  val votes = ListBuffer[(ActorRef, Operation[T])]() // voting
  val ack = ListBuffer[Ack.type]() // before final acknowledgment
  def receive = {
    case p: Process[T] =>
      context.system.scheduler.scheduleOnce(2 seconds, self, "Timeout")
      expectedCount = p.rs.count; parent = sender; rs ++= p.rs.rs
      for (r <- p.rs.rs) process(Req(r))
    case r: Operation[T] =>
      votes += sender -> r
      for (d <- decision; (proc, req) <- votes) { proc ! d(req.r)}
    case Ack => ack += Ack; if (ack.size == expectedCount)
      if (decision.get(null).isCommit) commit else rollback
    case "Timeout" => if (ack.size != expectedCount) context.parent ! Failure; context stop self
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

