package org.example

import scala.collection.mutable.ListBuffer
import akka.actor.{Props, ActorRef, Actor}
import scala.reflect._
import akka.pattern._
import scala.concurrent.ExecutionContext.Implicits.global
import akka.util.Timeout
import scala.concurrent.duration._

object Helper {
  implicit class RichBoolean(b: Boolean) {def toOption[T](x: => T) = if (b) Some(x) else None }
  implicit class RichInt(i: Int) {def expected[T] = expecting[T](i) _}
  val timeout = 5 seconds
  implicit val askTimeout = Timeout(timeout)
  case class expecting[T](override val transactionSize: Int)(val acc: Seq[T], val votes: Seq[Vote[T]]) extends Merge[T]
  case class default[T] private[example](acc: Seq[T], votes: Seq[Vote[T]]) extends Merge[T]
  type GetMerge[T] = (Seq[T], Seq[Vote[T]]) => Merge[T]
}
import Helper._

trait Merge[T] { //see Helper.default for reference implementation
  def acc: Seq[T] //data accumulated at the moment
  def votes: Seq[Vote[T]] //votes accumulated at the moment
  def isFull = votes.size == transactionSize //all parts of chunked transaction received
  def mergeVotes = votes forall (_.isCommit) toOption Commit[T] _ getOrElse Rollback[T] _
  private[example] def transactionSize = acc.size //expected transaction size
  private[example] def apply = isFull toOption mergeVotes
}

case class ReqSeq[T](tid: String, data: Seq[T])(implicit val merge: GetMerge[T] = default[T] _)
case class Req[T](req: T, tid: String)
case class Process[T](rs: ReqSeq[T])

trait Result
object Success extends Result
object Failure extends Result

trait Vote[T] { def r: Req[T]; def isCommit: Boolean }
case class Commit[T](r: Req[T]) extends Vote[T] { def isCommit = true }
case class Rollback[T](r: Req[T]) extends Vote[T] { def isCommit = false }
case class Ack[T](o: Vote[T])

abstract class Coordinator[T, TA: ClassTag] extends Actor {
  def transactor(id: String) = context.child(id) getOrElse context.actorOf(Props(classTag[TA].runtimeClass), id)
  def receive = {
    case rs: ReqSeq[T] => transactor(rs.tid) ? Process(rs) pipeTo sender
  }
}

abstract class Transactor[T, P <: Processor[T]: ClassTag] extends Actor {
  var transact: ReqSeq[T] = _
  var parent: ActorRef = _ //we need it to correlate with last request, context.parent cannot be used
  val acc = ListBuffer[T]()

  def process(r: Req[T]) =  context.actorOf(Props[P]) ! r

  def commit = parent ! Success
  def rollback = parent ! Failure
  def tid = self.path.name

  val stat = scala.collection.mutable.Map[ActorRef, Vote[T]]() // voting

  def receive = {
    case p: Process[T] =>
      context.system.scheduler.scheduleOnce(timeout, self, "Timeout")
      transact = p.rs; parent = sender; acc ++= p.rs.data
      for (r <- p.rs.data) process(Req(r, tid))
    case r: Vote[T] =>
      stat += sender -> r
      for (d <- transact.merge(acc, stat.map(_._2).toSeq).apply; (actor, req) <- stat) actor ! d(req.r)
    case a: Ack[T] =>
      stat -= sender
      stat.isEmpty toOption (a.o.isCommit toOption commit getOrElse rollback)
    case "Timeout" =>
      stat.nonEmpty toOption { rollback; for ((actor, v) <- stat) actor ! Rollback(v.r) }
      context stop self
  }
}

trait Processor[T] extends Actor {
  def process(r: Req[T]): Vote[T]
  def complete(t: T)
  def rollback(t: T)
  def receive = {
    case r: Req[T] => sender ! process(r)
    case o: Commit[T] => complete(o.r.req); sender ! Ack(o)
    case o: Rollback[T] => rollback(o.r.req); sender ! Ack(o)
  }
}