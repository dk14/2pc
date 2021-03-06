package org.example

import org.scalatest.{BeforeAndAfterAll, BeforeAndAfter, FlatSpec, Matchers}
import scala.concurrent._
import akka.actor.{ActorSystem, Props}
import akka.pattern._
import akka.util.Timeout
import scala.concurrent.ExecutionContext.Implicits.global
import scala.reflect._

/**
 * Created by user on 9/28/14.
 */
case class Task(s: Result, isComplete: Promise[Result] = Promise[Result]())

class CoordinatorImpl extends Coordinator[Task, TransactorImpl]

class TransactorImpl extends Transactor[Task, ProcessorImpl]

class ProcessorImpl extends Processor[Task] {
  def process(r: Req[Task]) = Future { if (r.body.s == Success) Commit(r) else Rollback(r) }
  def complete(t: Req[Task]) = Future { t.body.isComplete.success(Success) }
  def rollback(t: Req[Task]) = Future { t.body.isComplete.success(Failure) }
}

class CoordinatorTest extends FlatSpec with Matchers with BeforeAndAfterAll {
  val system = ActorSystem("testSystem")

  val coordinator = system.actorOf(Props(classOf[CoordinatorImpl]), "coordinator")

  import Helper._
  import Helper.implic.defaultAskTimeout

  "coordinator" should "complete transaction" in {
    val request = ReqSeq("100800", Seq(Task(Success), Task(Success), Task(Success)))
    val result = coordinator ? request
    Await.result(result, defaultTimeout) should be (Success)
    val tasks = Future.sequence(request.data.map(_.isComplete.future))
    Await.result(tasks, defaultTimeout) should be (Seq(Success, Success, Success))
  }

  "coordinator" should "rollback transaction" in {
    val request = ReqSeq("100900", Seq(Task(Success), Task(Success), Task(Failure)))
    val result = coordinator ? request
    Await.result(result, defaultTimeout) should be (Failure)
    val tasks = Future.sequence(request.data.map(_.isComplete.future))
    Await.result(tasks, defaultTimeout) should be (Seq(Failure, Failure, Failure))
  }

  "coordinator" should "rollback chunked transaction" in {
    implicit val expectations = 5.expected[Task]
    val chunk1 = ReqSeq("1001000", Seq(Task(Failure), Task(Success), Task(Success)))
    val chunk2 = ReqSeq("1001000", Seq(Task(Success), Task(Success)))
    coordinator ! chunk1
    val result = coordinator ? chunk2
    Await.result(result, defaultTimeout) should be (Failure)
    val tasks = Future.sequence(chunk1.data.map(_.isComplete.future))
    Await.result(tasks, defaultTimeout) should be (Seq(Failure, Failure, Failure))
  }

  override def afterAll = system.shutdown()

}
