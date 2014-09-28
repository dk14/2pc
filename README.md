xa
==

This simple library allows you to create your own distributed transaction coordinator :
    
    case class Message(...)
    
    class CoordinatorImpl extends Coordinator[Message, TransactorImpl]

    class TransactorImpl extends Transactor[Message, ProcessorImpl] {
       override def complete //transactionId is available here as tid
       override def rollback //transactionId is available here as tid
       //super.complete/rollback will send notification to coordinator
    }

    class ProcessorImpl extends Processor[Task] {
        def process(r: Req[Message]) = ... //should return Commit or Rollback
        def complete(m: Message) = ...
        def rollback(m: Message) = ...
    }
    
where 

- Message - is a message participating in transaction
- Coordinator - is a supervisor
- Transactor - implements 2PC protocol
- Processor - is an actor which actually do the job

Just implement complete/rollback to adopt it to your underlying system like JDBC, JMS or some other messaging protocol. 

To send set of messages, participating in one distributed transaction, use:
  
      implicit val askTimeout: Timeout = Timeout(Helper.defaultTimeout)
      val coordinator = system.actorOf(Props(classOf[CoordinatorImpl]), "coordinator")
      val request = ReqSeq(tid, Seq(msg1, ..., msgN))
      coordinator ! request

where

- tid - is unique id which correlates messages in XA transaction

If you want to reduce data from processors to some actor - just point them to one tid-scoped actor

     class Receiver() extends Actor { ... }
     
     //in your superviser
     context.child(tid) getOrElse context.actorOf(Props[Receiver], tid)
     
     //in your message
     case class Message(a: ActorRef, e: Option[Throwable], ...)
     
     //in processor
     def process(r: Req[Message]) = {
         val res = ... 
         r.req.a ! res    
     }
     
     def complete(m: Message) = m.a ! Done
     def rollback(m: Message) = m.a ! m.e //or m.a ! PoisonPill
     
     
Same protocols may send data chunk by chunk and you may need process every chunk immediately. You can use chunked transaction to adopt such protocols:

    import Helper._
    implicit val expectations = 5.expected[Task]
    val chunk1 = ReqSeq("1001000", Seq(msg1, msg2, msg3))
    val chunk2 = ReqSeq("1001000", Seq(msg4, msg5))
    coordinator ! chunk1
    val result = coordinator ? chunk2
    
    
Expectation is the count of messages which expected to be received in current transaction. Note that you have to specify real expectation (full count of messages in transaction) only for last chunk - just use 0 for previouses.

You may want to implement Merge trait to customize voiting process or have end-of-transaction predicate more customized:

    class MyMerge(acc: Seq[Msg], votes: Seq[Vote[Msg]]) extends Merge[Msg] { 
       def isFull: Boolean = ... //true if all parts of chunked transaction received
       def mergeVotes = ...  //returns Commit[T] _ or Rollback[T] _ based on votes
    }
    
    implicit val merge = MyMerge
    
    val request = new ReqSeq(...)
    
    