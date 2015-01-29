package colossus
package service

import akka.actor._
import scala.util.{Try, Success, Failure}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}


/**
 * This exception is only thrown when there's a uncaught exception in the execution block of a Callback.  For example, {{{
 val c: Callback[Foo] = getCallback()
 c.execute{
   case Success(foo) => throw new Exception("exception")
 }
 }}}
 * will result in a `CallbackExecutionException` being thrown, however {{{
 c.map{_ => throw new Exception("exception")}.execute()
 }}}
 * will not because the exception can still be recovered
 */

class CallbackExecutionException(cause: Throwable) extends Exception("Uncaught exception in callback Execution block", cause)


/**
 * A Callback is a Monad for doing in-thread non-blocking operations.  It is
 * essentially a "function builder" that uses function composition to chain
 * together a callback function that is eventually passed to another function.
 *
 * Normally if you have a function that requires a callback, the function looks something like:{{{
   def doSomething(param, param, callBack: result => Unit)
 }}}
 * and then you'd call it like {{{
  doSomething(arg1, arg2, result => println("got the result"))
  }}}
 * This is the well-known continuation pattern, and it something we'd like to avoid due to the common occurrance of deeply nested "callback hell".  Instead, the `Callback` allows us to define out function as{{{
  def doSomething(param1, param2): Callback[Result]
  }}}
 * and call it like {{{
  val c = doSomething(arg1, arg2)
  c.map{ result =>
    println("got the result")
  }.execute()
  }}}
 *
 * Thus, in practice working with Callbacks is very similar to working with Futures.  The big differences from a future are:
 *
 * 1.  Callbacks are not thread safe at all.  They are entirely intended to stay inside a single worker.  Otherwise just use Futures.
 *
 * 2.  The execute() method needs to be called once the callback has been
 * fully built, which unlike futures requires some part of the code to know when a callback is ready to be invoked
 *
 * == Using Callbacks in Services ==
 *
 * When building services, particularly when working with service clients, you
 * will usually be getting Callbacks back from clients when requests are sent.
 * *Do not call `execute` yourself!* on these Callbacks.  They must be returned
 * as part of request processing, and Colossus will invoke the callback itself.
 *
 * == Using Callbacks elsewhere ==
 *
 * If you are using Callbacks in some custom situation outside of services, be
 * aware that exceptions thrown inside a `map` or `flatMap` are properly caught
 * and can be recovered using `recover` and `recoverWith`, however exceptions
 * thrown in the "final" handler passed to `execute` are not caught.  This is
 * because the final block cannot be mapped on (since it is only passed when
 * the callback is executed) and throwing the exception is preferrable to
 * suppressing it.  
 *
 * Any exception that is thrown in this block is however rethrown as a
 * `CallbackExecutionException`.  Therefore, any "trigger" function you wrap
 * inside a callback should properly catch this exception.
 *
 */
trait Callback[+O] {
  def map[U](f: O => U): Callback[U]
  def flatMap[U](f: O => Callback[U]): Callback[U]

  def mapTry[U](f: Try[O] => Try[U]): Callback[U]

  def recover[U >: O](p: PartialFunction[Throwable, U]): Callback[U]
  def recoverWith[U >: O](p: PartialFunction[Throwable, Callback[U]]): Callback[U]

  final def withFilter(f: O => Boolean): Callback[O] = this.mapTry{_.filter(f)}

  def execute(onComplete: Try[O] => Unit = _ => ())
  /**
   * Hook a future into the callback and execute the caller.  Use this if you
   * need to hand a callback to something out-of-thread
   */
  def toFuture(implicit ex: ExecutionContext): Future[O] = {
    val p = Promise[O]()
    this.map{o => p.success(o)}.execute()
    p.future
  }

  //TODO: take a wild guess
  def zip[B](b: Callback[B]): Callback[(O,B)] = Callback.zip(this, b)
  def zip[B,C](b: Callback[B], c: Callback[C]): Callback[(O,B,C)] = {
    zip(b.zip(c)).map{case (x, (y, z)) => (x,y,z)}
  }
}

case class MappedCallback[I, O](trigger: (Try[I] => Unit) => Unit, handler: Try[I] => Try[O]) extends Callback[O] {
  def execute(onComplete: Try[O] => Unit = _ => ()) {
    try {
      trigger({i => onComplete(handler(i))})
    } catch {
      case err: Throwable => throw new CallbackExecutionException(err)
    }
  }

  //since we're getting a new callback, we must rebuild the trigger so that the
  //new callback takes in the built handler function and not this one
  //notice the order of execution is handler(this callback) => f(the mapping) => cb (the handler function built after this call)
  def flatMap[U](f: O => Callback[U]): Callback[U] = {
    val newTrigger: (Try[U] => Unit) => Unit = cb => trigger(i => handler(i).map{v => 
      Try(f(v)) match {
        case Success(callback) => callback.execute(cb)
        case Failure(err) => cb(Failure(err))
      }
    }.get) //this get is needed to avoid suppressing thrown exceptions in the final execute block
    UnmappedCallback(newTrigger)
  }
  def map[U](f: O => U): Callback[U] = MappedCallback(trigger, {i: Try[I] => handler(i).flatMap(v => Try(f(v)))})

  def mapTry[U](f: Try[O] => Try[U]): Callback[U] = MappedCallback(trigger, {i: Try[I] => f(handler(i))})

  def recover[U >: O](p: PartialFunction[Throwable, U]): Callback[U] = MappedCallback(trigger, {i: Try[I] => 
    handler(i) match {
      case Success(o) => Success(o)
      case Failure(err) => try { p.andThen(s => Success(s)).applyOrElse(err, {e: Throwable => Failure[U](e)})} catch {
        case t: Throwable => Failure(t)
      }
    }
  })

  def recoverWith[U >: O](p: PartialFunction[Throwable, Callback[U]]): Callback[U] = {
    val newtrigger: (Try[U] => Unit) => Unit = cb => trigger(i => handler(i) match {
      case Success(o) => cb(Success(o))
      case Failure(err) => try {
        p.applyOrElse(err, (e: Throwable) => Callback.failed(e)).execute(cb)
      } catch {
        case t: Throwable => cb(Failure(t))
      }
    })
    UnmappedCallback(newtrigger)
  }

}

/**
 * UnmappedCallback is essentially an optimization that avoids needing to
 * create a MappedCallback with an identity function
 */
case class UnmappedCallback[I](trigger: (Try[I] => Unit) => Unit) extends Callback[I]{
  def map[O](f: I => O): Callback[O] = MappedCallback(trigger, (i: Try[I]) => i.map(f))
  def flatMap[O](f: I => Callback[O]): Callback[O] = {
    val newTrigger: (Try[O] => Unit) => Unit = cb => trigger(ti => ti.map{i =>
      Try(f(i)) match {
        case Success(callback) => callback.execute(cb)
        case Failure(err) => cb(Failure(err))
      }
    }.get)
    UnmappedCallback(newTrigger)
  }

  def mapTry[O](f: Try[I] => Try[O]): Callback[O] = MappedCallback(trigger, f)

  def execute(onComplete: Try[I] => Unit = _ => ()) {
    try {
      trigger({i => onComplete(i)})
    } catch {
      case err: Throwable => throw new CallbackExecutionException(err)
    }
  }

  def recover[U >: I](p: PartialFunction[Throwable, U]): Callback[U] = MappedCallback(trigger, (i: Try[I]) => i match {
    case Success(o) => Success(o)
    case Failure(err) => try { p.andThen(s => Success(s)).applyOrElse(err, {e: Throwable => Failure[U](e)})} catch {
      case t: Throwable => Failure(t)
    }
  })

  def recoverWith[U >: I](p: PartialFunction[Throwable,Callback[U]]): Callback[U] = {
    val newTrigger: (Try[U] => Unit) => Unit = cb => trigger {
      case Success(o) => cb(Success(o))
      case Failure(err) => try {
        p.applyOrElse(err, (e: Throwable) => Callback.failed(e)).execute(cb)
      } catch {
        case t: Throwable => cb(Failure(t))
      }
    }
    UnmappedCallback(newTrigger)
  }

}

/**
 * A `CallbackExecutor` is an actor that mixes in the [CallbackExecution] trait
 * to complete the execution of a [Callback] that has been converted from a
 * `Future`.  In almost every case, the executor should be an actor running in
 * a Pinned Dispatcher and shound be the same actor that created the original
 * Callback, for example, a Colossus worker.
 *
 * This type is generally only needed when converting a Future to a Callback,
 * or scheduling a Callback for delayed execution.  
 */
case class CallbackExecutor(context: ExecutionContext, executor: ActorRef)
object CallbackExecutor {
  def apply(executor: ActorRef)(implicit ex: ExecutionContext): CallbackExecutor = CallbackExecutor(ex, executor)
}


object Callback {
  import scala.reflect.ClassTag

  def identity[A]: Function1[A,A] = (a: A) => a

  /**
   * The class tag is only needed to construct the array
   */
  class SequencedCallback[A : ClassTag](callback: Try[Seq[Try[A]]] => Unit, callbacks: Seq[Callback[A]]) {
    private val results = new Array[Try[A]](callbacks.size)
    private var numComplete = 0

    def finish(index: Int, value: Try[A]) {
      results(index) = value
      numComplete += 1
      if (numComplete == callbacks.size) {
        callback(Success(results.toList))
      }        
    }

    def execute() {
      callbacks.zipWithIndex.foreach{ case (cb,index) =>
        cb.execute(result => finish(index, result))
      }
    }
  }

  class TupledCallback[A,B](a: Callback[A], b: Callback[B], completion: Try[(A,B)] => Unit) {
    private var aresult: Option[Try[A]] = None
    private var bresult: Option[Try[B]] = None

    def checkDone() {
      if (aresult.isDefined && bresult.isDefined) {
        val ab = for {
          va <- aresult.get
          vb <- bresult.get
        } yield (va,vb)
        completion(ab)
      }
    }
        

    def execute(){
      a.execute{a => aresult = Some(a); checkDone()}
      b.execute{b => bresult = Some(b); checkDone()}
    }
  }

  def apply[I](f: (Try[I] => Unit) => Unit): Callback[I] = UnmappedCallback(f)

  def zip[A,B](a: Callback[A], b: Callback[B]): Callback[(A,B)] = {
    UnmappedCallback((f: Try[(A,B)] => Unit) => new TupledCallback[A,B](a, b, f).execute())
  }

  def sequence[A : ClassTag](callbacks: Seq[Callback[A]]): Callback[Seq[Try[A]]] = {
    UnmappedCallback((f: Function1[Try[Seq[Try[A]]], Unit]) => new SequencedCallback(f, callbacks).execute())
  }

  /** Create a callback that immediately returns the value
   *
   * @param value the successful value or error to complete with
   */
  def complete[I](value: Try[I]): Callback[I] = UnmappedCallback[I](f => f(value))

  /** returns a callback that will immediately succeed with the value as soon as its executed
   *
   * @param value the value to complete with
   */
  def successful[I](value: I): Callback[I] = complete(Success(value))

  /** returns a callback that will immediately fail with the given error
   *
   * @param err the error to return
   */
  def failed[I](err: Throwable): Callback[I] = complete(Failure(err))

  /** Convert a Future to a Callback
   *
   * Because Callbacks are expected to be handled entirely in a single thread,
   * you must provide an executor actor which will finish processing the
   * callback after the future is complete (eg if someone maps or flatMaps this
   * callback
   *
   * IMPORTANT - the executor actor must be able to handle the CallbackExec
   * message.  All it has to do is call the execute() method.  Or you can mixin
   * the CallbackExecutor trait and compose handleCallback with your receives!!
   *
   * @param future The future to convert
   * @param executor  The executor actor to complete the callback
   * @return Callback linked to the Future
   */
  def fromFuture[I](future: Future[I])(implicit executor: CallbackExecutor): Callback[I] = {
    implicit val ex = executor.context
    val trigger: (Try[I] => Unit) => Unit = cb => future.onComplete{res => executor.executor ! CallbackExec(() => cb(res))}
    UnmappedCallback(trigger)
  }

  /**
   * Schedule a callback to be executed after a delay
   *
   * This method requires an implicit executor actor, which must be able to
   * handle CallbackExec messages
   *
   * @param in how long to wait before executing the callback
   * @param cb the callback to execute
   */
  def schedule[I](in: FiniteDuration)(cb: Callback[I])(implicit executor: CallbackExecutor): Callback[I] = {
    implicit val ex = executor.context
    val trigger: (Try[I] => Unit) => Unit = x => executor.executor ! CallbackExec(() => cb.execute(x), Some(in))
    UnmappedCallback(trigger)
  }

  object Implicits {
    implicit def objectToSuccessfulCallback[T](obj: T): Callback[T] = Callback.successful(obj)
  }
      
  
}


/* Message used to finish executing a callback after work was done in a future
 *
 * When using `Callback.fromFuture`, we need a way to bring execution back into
 * the thread executing the callback.  So any entity executing a callback must
 * be an Actor implementing the `CallbackExecution` trait.  This message is
 * sent to that trait on the completion of the converted Future, containing the
 * code for any `map` or `flatMap` that occurs on the callback.
 */
case class CallbackExec(cb: () => Unit, in: Option[FiniteDuration] = None) {
  def execute() {
    cb()
  }
}


/**
 * This little actor is needed because apparently actors that are running in a
 * pinned dispatcher AND are sending messages to themselves (like workers)
 * cannot use the scheduler...the messages never get sent.  So we get around
 * this by sending a message to this actor living in the default dispatcher
 * which then does the proper scheduling
 */
class Delayer extends Actor with ActorLogging {
  import context.dispatcher
  def receive = {
    case c: CallbackExec => {
      context.system.scheduler.scheduleOnce(c.in.get, sender(), CallbackExec(c.cb))
    }
  }
}

trait CallbackExecution extends Actor with ActorLogging{

  val delayer = context.actorOf(Props[Delayer])

  val handleCallback: Receive = {
    case c: CallbackExec => c.in match {
      case None => c.execute()
      case Some(wait) => {
        delayer ! c
      }
    }
  }
}