package org.example

import org.scalatest.{FlatSpec, Matchers}
import scala.concurrent._
import akka.actor.{ActorSystem, Props}
import scala.collection.mutable.ListBuffer
import akka.pattern._
import org.example
import akka.util.Timeout

/**
 * Created by user on 9/28/14.
 */
class CoordinatorTest extends FlatSpec with Matchers {
  val system = ActorSystem("testSystem")
  case class Task(s: Result, isComplete: Promise[Unit] = Promise[Unit]())

  class CoordinatorImpl() extends Coordinator[Task, TransactorImpl]

  class TransactorImpl(val rs: ReqSeq[Task]) extends Transactor[Task, ProcessorImpl] {
    def all(candidates: ListBuffer[Result]) = ???
    def commit = {sender ! Success}
    def rollback = {sender ! Failure}
  }

  class ProcessorImpl extends Processor[Task] {
    def process(t: Task) = t.s
    def complete(t: Task) = t.isComplete.success(())
    def rollback(t: Task) = t.isComplete.failure(new RuntimeException("!"))
  }

  val coordinator = system.actorOf(Props(classOf[CoordinatorImpl], null), "coordinator")

  import scala.concurrent.duration._
  val timeout = 5 seconds
  implicit val askTimeout = Timeout(timeout)

  "transactor" should "complete transaction" in {
    val request = ReqSeq("100500", Seq(Task(Success), Task(Success), Task(Success)))
    val result = coordinator ? request
    Await.result(result, timeout) should be (Success)

  }

  "transactor" should "rollback transaction" in {
    val request = ReqSeq("100500", Seq(Task(Success), Task(Success), Task(Failure)))
    val result = coordinator ? request
    Await.result(result, timeout) should be (Failure)
  }
}
