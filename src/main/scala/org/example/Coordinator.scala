package org.example

import akka.actor._
import scala.reflect._
import akka.pattern._
import Helper._

abstract class Coordinator[T, TA: ClassTag] extends Actor {
  import context.dispatcher
  private def transactor(id: String) = context.child(id) getOrElse context.actorOf(Props(classTag[TA].runtimeClass), id)
  final def receive = {
    case rs: ReqSeq[T] => import rs.askTimeout; transactor(rs.tid) ? Process(rs) pipeTo sender
  }
}

abstract class Transactor[T, P <: Processor[T]: ClassTag] extends TransactorLike[T] {
  def process(id: String) = context.child(id) getOrElse context.actorOf(Props(classTag[P].runtimeClass), id)
  final def receive = {
    case p: Process[T] =>
      scheduleTimeouts(parent == null, p.rs.merging(acc, votes))
      transact = p.rs; parent = sender
      for ((r, i) <- p.rs.data.zipWithIndex) process(p.rs.tid) ! Req(tid, r, acc.size + i)
      acc ++= p.rs.data
    case v: Vote[T] =>
      stat += sender -> v
      for (newVote <- transact.merging(acc, votes).apply; (actor, vote) <- stat) actor ! newVote(vote.req)
    case a: Ack[T] =>
      stat -= stat.find(_._2.req == a.vote.req).get
      if (stat.isEmpty) { a.vote.isCommit toOption commit getOrElse rollback; self ! PoisonPill }
    case "Timeout" =>
      if (stat.nonEmpty) { for ((actor, vote) <- stat) actor ! Rollback(vote.req); rollback }; self ! PoisonPill
  }
}

trait Processor[T] extends ProcessorLike[T] {
  import context.dispatcher
  final def receive = {
    case r: Req[T] => process(r) pipeTo sender
    case o: Commit[T] => complete(o.req) map (_ => Ack(o)) pipeTo sender
    case o: Rollback[T] => rollback(o.req) map (_ => Ack(o)) pipeTo sender
  }
}