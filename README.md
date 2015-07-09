2pc
==

This simple library allows you to create your own distributed transaction coordinator :
    
    case class Message(...)
    
    class CoordinatorImpl extends Coordinator[Message, TransactorImpl]

    class TransactorImpl extends Transactor[Message, ProcessorImpl] { //2nd phase
        override def complete = ...
        override def rollback = ...
        //def tid - transaction id (and also name of the actor)
    }

    class ProcessorImpl extends Processor[Task] { //1st phase
        def process(r: Req[Message]) = Future{...} //should return Commit or Rollback vote
        def complete(r: Req[Message]) = Future{...}
        def rollback(r: Req[Message]) = Future{...}
    }
    
where 

- Message - is a message participating in transaction
- Coordinator - is a supervisor
- Transactor - implements 2PC protocol
- Processor - is an actor which actually do the job

Just implement complete/rollback to adopt this to your underlying system like JDBC, JMS or some other messaging protocol. 

To send set of messages, participating in one distributed transaction, use:
  
      val coordinator = system.actorOf(Props(classOf[CoordinatorImpl]), "coordinator")
      val request = ReqSeq(tid, Seq(msg1, ..., msgN))
      coordinator ! request

where

- tid - is unique id which correlates messages in XA transaction

If you want to reduce data from processors to some actor - just point them to one tid-scoped actor

     class Receiver() extends Actor { ... }
     
     //in your supervisor
     context.child(tid) getOrElse context.actorOf(Props[Receiver], tid)
     
     //in your message
     case class Message(a: ActorRef, e: Option[Throwable], ...)
     
     //in processor (data with same tid will arrive sequentially)
     def process(r: Req[Message]) = r.req.a ? res
     
     
     
     def complete(m: Req[Message]) = m.body.a ! Done
     def rollback(m: Req[Message]) = m.body.a ! m.e //or m.a ! PoisonPill
     
     
Some protocols may send data chunk by chunk. You can use chunked transaction to have every chunk processed immediately:

    import Helper._
    import Helper.implic.defaultAskTimeout
    implicit val expectations = 5.expected[Message]
    val chunk1 = ReqSeq("100500", Seq(msg1, msg2, msg3))   
    coordinator ! chunk1
    coordinator ! <something_else>
    val chunk2 = ReqSeq("100500", Seq(msg4, msg5))
    val result = coordinator ? chunk2
    
    
Expectation is the count of messages which expected to be received in current transaction. Note that you have to specify real expectation (full count of messages in transaction) only for last chunk - just use 0 for all previous.

You may want implement Merge trait to customize voting process or have end-of-transaction predicate more customized:

    class MyMerge(acc: Seq[Msg], votes: Seq[Vote[Msg]]) extends Merge[Msg] { 
       def isFull: Boolean = ... //should be true if all parts of chunked transaction received
       def mergeVotes = ...  //returns Commit[T] _ or Rollback[T] _ based on votes
    }
    
    implicit val merge = MyMerge
    
    val request = new ReqSeq(...)
    
    
