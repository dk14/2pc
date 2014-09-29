package org.example

import akka.actor._
import scala.reflect._
import akka.pattern._
import Helper._

abstract class Coordinator[T, TA: ClassTag] extends Actor {
  import context.dispatcher
  private def transactor(id: String) = context.child(id) getOrElse context.actorOf(Props(classTag[TA].runtimeClass), id)
  final def receive = {
    case rs: ReqSeq[T] => import rs.askTimeout
      transactor(rs.tid) ? Process(rs) pipeTo sender
  }
}

abstract class Transactor[T, P <: Processor[T]: ClassTag] extends TransactorLike[T,P] {
  private def process(r: Req[T]) = context.actorOf(Props[P]) ! r
  final def receive = {
    case p: Process[T] =>
      scheduleTimeouts(parent == null, p.rs.merging(acc, votes))
      transact = p.rs; parent = sender; acc ++= p.rs.data
      for (r <- p.rs.data) process(Req(tid, r))
    case v: Vote[T] =>
      stat += sender -> v
      for (newVote <- transact.merging(acc, votes).apply; (actor, vote) <- stat) actor ! newVote(vote.req)
    case a: Ack[T] =>
      stat -= sender
      if (stat.isEmpty) a.vote.isCommit toOption commit getOrElse rollback
    case "Timeout" =>
      if (stat.nonEmpty) { for ((actor, vote) <- stat) actor ! Rollback(vote.req); rollback }
      context stop self
  }
}

trait Processor[T] extends ProcessorLike[T] {
  import context.dispatcher
  final def receive = {
    case r: Req[T] =>  process(r) pipeTo sender
    case o: Commit[T] => complete(o.req) map (_ => Ack(o)) pipeTo sender; context stop self
    case o: Rollback[T] => rollback(o.req) map (_ => Ack(o)) pipeTo sender; context stop self
  }
}