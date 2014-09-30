package org

import akka.util.Timeout
import scala.concurrent.duration._
import scala.reflect.ClassTag
import akka.actor._
import scala.collection.mutable.ListBuffer
import akka.pattern._
import scala.concurrent.Future

/**
 * Created by user on 9/29/14.
 */
package object example {
  object Helper {
    implicit class RichBoolean(b: Boolean) {def toOption[T](x: => T) = if (b) Some(x) else None }
    implicit class RichInt(i: Int) {def expected[T] = expecting[T](i) _}
    case class expecting[T](override val transactionSize: Int)(val acc: Seq[T], val votes: Seq[Vote[T]]) extends Merging[T]
    case class default[T] private[example](acc: Seq[T], votes: Seq[Vote[T]]) extends Merging[T]
    type GetMerge[T] = (Seq[T], Seq[Vote[T]]) => Merging[T]
    val defaultTimeout = 5 seconds
    object implic {implicit val defaultAskTimeout = Timeout(Helper.defaultTimeout)}
  }
  import Helper._

  trait Merging[T] { //see Helper.default for reference implementation
    def acc: Seq[T] //data accumulated at the moment
    def votes: Seq[Vote[T]] //votes accumulated at the moment
    def isFull = votes.size == transactionSize //all parts of chunked transaction received
    def mergeVotes = votes forall (_.isCommit) toOption Commit[T] _ getOrElse Rollback[T] _
    private[example] def transactionSize = acc.size //expected transaction size
    private[example] def apply = isFull toOption mergeVotes
    def chunkTimeout = None
    def transactionTimeout = defaultTimeout
  }


  case class ReqSeq[T](tid: String, data: Seq[T])
                      (implicit val merging: GetMerge[T] = default[T] _, val askTimeout: Timeout = Timeout(defaultTimeout))
  case class Req[T](tid: String, body: T, seqNumber: Int)
  case class Process[T](rs: ReqSeq[T])

  trait Result
  object Success extends Result
  object Failure extends Result

  trait Vote[T] { def req: Req[T]; def isCommit: Boolean }
  case class Commit[T](req: Req[T]) extends Vote[T] { def isCommit = true }
  case class Rollback[T](req: Req[T]) extends Vote[T] { def isCommit = false }
  case class Ack[T](vote: Vote[T])

  abstract class TransactorLike[T] extends Actor {
    import context.dispatcher
    private[example] var transact: ReqSeq[T] = _
    private[example] var parent: ActorRef = _ //we need it to correlate with last request, it's not context.parent

    private[example] val acc = ListBuffer[T]()

    def commit = parent ! Success
    def rollback = parent ! Failure

    final def tid = self.path.name

    val orderBySeqNumber = Ordering.by[(ActorRef, Vote[T]), Int](_._2.req.seqNumber)
    private[example] val stat = scala.collection.mutable.SortedSet[(ActorRef, Vote[T])]()(orderBySeqNumber)
    private[example] def votes = stat.map(_._2).toSeq // voting

    def scheduleTimeouts(isFirstChunk: Boolean, merging: Merging[T]) = {
      if(isFirstChunk) context.system.scheduler.scheduleOnce(merging.transactionTimeout, self, "Timeout")
      merging.chunkTimeout foreach (context.system.scheduler.scheduleOnce(_, self, "Timeout"))
    }
  }

  trait ProcessorLike[T] extends Actor {
    def process(r: Req[T]): Future[Vote[T]]
    def complete(t: Req[T]): Future[Unit]
    def rollback(t: Req[T]): Future[Unit]
  }

}