package org.example

import scala.collection.mutable.ListBuffer
import akka.actor.{Props, ActorRef, Actor}
import scala.reflect._

case class ReqSeq[T](tid: String, rs: Seq[T])
case class Req[T](req: T)
abstract class Coordinator[T, TA: ClassTag] extends Actor {
  def transactor(tid: String, rs: ReqSeq[T]) = context.actorOf(Props(classTag[TA].runtimeClass, rs), tid)
  def receive = {
    case ReqSeq(tid, rs) => //TODO ask forward
  }
}

object Process
trait Result
object Success extends Result
object Failure extends Result
case class Commit[T](r: Req[T])
case object Ack
abstract class Transactor[T, P <: Processor[T]: ClassTag] extends Actor { //TODO add timeout
  def rs: ReqSeq[T]
  def processor(tid: String) = context.actorOf(Props[P])
  def commit
  def all(l: ListBuffer[Result]): Option[Result]

  val stat = ListBuffer[Result]()
  val ack = ListBuffer[Ack.type]()
  def receive = {
    case Process => //send info to new actors
    case Success => //add stats
    case Failure => //add stats
    case Ack => //add ack
  }
}

trait Processor[T] extends Actor {
  def process(t: T): Result
  def complete(t: T)
  def receive = {
    case r: Req[T] => //send success or failure
    case a: Commit[T] => complete(a.r.req)
  }
}

