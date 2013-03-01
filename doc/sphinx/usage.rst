.. _usage:

=====
Usage
=====

As stated before, Luthier is a library, not a framework. That means that you call and tell it what to do, and not
vice-versa.
This feature allows Luthier to meld in any project easily, but also comes with the overhead of setting up a Flows
container so that you can start writing flows. In this section, we'll see two approaches for this, as well as the
documentation about the specific implemented transports.

Direct usage
============

As mentioned before, you need to contain your flows in a ``Flows`` container which, itselfs needs an AppContext
definition.
We have several factory methods to construct an AppContext, they are:

.. code-block:: scala

  AppContext.build(name: String, rootLocation: Path, config: Config, classLoader: ClassLoader)
  AppContext.build(name: String, rootLocation: Path, classLoader: ClassLoader)
  AppContext.build(name: String, rootLocation: Path, config: Config)
  AppContext.build(name: String, rootLocation: Path)
  AppContext.build(name: String)

The first one is the most general one, while the others are just variations using the appropriate defaults for the
other values. The parameters are:

 * name:
     The name of the AppContext, it is used to create the ActorSystem, which in turn is used to instantiate the
     actors of the flow, so the name is important in that it is a constant prefix for every actor.
 * rootLocation:
     The root path of the AppContext, flows might use this as a root path to create relative paths.
 * config:
     The configuration container for the actor sytem. Akka's ActorSystem is quite complex and supports a lots of
     configuration. You might want to take a look at it's `documentation page <http://doc.akka.io/docs/akka/2.1.1/general/configuration.html>`_.
 * classLoader:
    The less used parameter. Normally used to load actors if using some sort of clustering or remoting, typically not
    passed.

After you created your AppContext, you can instantiate a Flows container like this:

.. code-block:: scala

  val ac = AppContext.build(...)
  new Flows {
    val appContext = ac
    ...
  }

  //or simply
  new Flows {
    val appContext = AppContext.build(...)
    ...
  }

Once in the scope of a Flows container, you define your flows, and finally, start them up, which you can do specifically
by calling start on a flow, or in batch, by invoking Flows helper function startAllFlows.

.. code-block:: scala

  val flows = new Flows {
    val appContext = AppContext.build(...)
    ...

    new Flow("hello-world")(SomeEndpoint) {
      logic {req =>
        req.map(_ => "Hello world!")
      }
    }
  }
  flows.startAllFlows()

Let's see a full example using one of the existing transports:

.. code-block:: scala

  import uy.com.netlabs.luthier.{Flows, AppContext, Message}
  import uy.com.netlabs.luthier.endpoint.logical.Polling.Poll
  import uy.com.netlabs.luthier.endpoint.file._
  import scala.concurrent._
  import scala.concurrent.duration._

  class PollFileFlow extends App {

    val flows = new Flows {
      val appContext = AppContext.build("example-app")

      new Flow("poll-a-file")(Poll(File("/path/to/some/file"), every = 10.seconds)) {
        logic {req: Message[Array[Byte]] =>
          println("File's content as string: " + new String(req.payload))
        }
      }
    }
  }

This example is fully runable provided that you setup the classpath correclty.
We started with several imports from Luthier, which give us the basic domain classes we need plus the endpoints Poll
and File (again, remember that those endpoints are actually factories).

Usage through runner
====================

The other option to run flows is through the runner. This is a module which allows for dynamic running of flows in
runtime.
The runner takes a path to a .scala file with a flow defintion, or a .flow file which is like the normal .scala file
but with taking away some boilerplate, and then it tries to load it, and starts monitoring it.
Loading the file implies compiling it, that means, you get error reporting just as if you had integrated the compilation
of the flow in you your compilation unit. If compilation succeeds the flows defined there are instantiated with an
AppContext rooted at the flow's file path. If compilation fails, it will monitor for modifications and attempt a reload
on a file change event.
When the runner successfully loads a flow, it will still keep monitoring the file and if it changes, it will attempt to
load it, and if it compiles fine, it will load the flow, stop the previous incarnation, and then start the new one,
reducing down times to under 50 milliseconds (on an i7 2500 processor, it takes 10 ms).

Here is how you setup a runner in your code:

.. code-block:: scala

  import java.nio.Paths
  import uy.com.netlabs.luthier.runner.{FlowsRunner, FlowHandler}

  val runner = new FlowsRunner("GDS", Main.class.getClassLoader());
  //bind some variable, so that it is accessible from the flows
  runner.bindObject("platform", "java.lang.String", "MyPlatform")
  //load three flows
  val handlers: Array[FlowHandler] =
    runner.load(Paths.get("/some/path/flow1.flow"),
                Paths.get("/some/path/flow2.flow"),
                Paths.get("/some/path/flow3.flow"))

First of all is instantiating its main class, ``FlowsRunner``, then we could for example bind some objects (note
that you must fully type it in the second parameter) so that it is usable from the flows (which will look like a global
value). Finally we call the load method (which can be called at different times) which returns an array of FlowHandler,
each representing one of the specified flow files.

Here's the list of operations supported by FlowHanlder:

.. code-block:: scala

  val filePath: Path
    //Path of flow loaded
  def flowRef(flowName: String): Option[Flow]
    //Searchs for a specific Flow instance with the given name
  var lastUpdate: Long
    //Last time that the flows file was read
  def load(): () ⇒ Unit
    //Attempts a load in the current thread.
  def theFlows: Option[Seq[Flows]]
    //List of Flows container read from the file.
  def watcherFlow: Option[Flow]
    //Flow registered in the FlowsRunner actorSystem to monitor the file. Might be none, if it hasn't started
    //watching it yet.

The api of the ``FlowsRunner`` is even usable from Java, so that you could create a project in that language
and code the business logic in it, but do all the routing in nice Luthier's flows.

Runner's flows files
--------------------

As we mentioned, there are two types of flows files the runners accepts:

 * .flow

     This files contains only flow definition, the runner takes cares of wrapping them in a Flows containers
     so they can be as succint as possible. The pros are that you write as less as possible, the cons is that since
     this is not a valid scala file, IDEs won't be happy with it, and wont provide their goodness.
     They look like this:

     .. code-block:: scala

       import uy.com.netlabs.luthier.endpoint.SomeEndpoint

       new Flow("test")(SomeEndpoint) {
         logic {req =>
         }
       }

     The runner will automatically add the imports:

     .. code-block:: scala

       import uy.com.netlabs.luthier._
       import uy.com.netlabs.luthier.typelist._
       import scala.language._

     and wrap them in a Flows container.

 * .fflow or .scala

     This files (known as full flows files) are full scala files so any IDE will be happy with it provided that you set up the classpath
     correctly.
     The Runner will compile this file and search for the top level classes that you defined, filtering for the
     the ones that extends Flows, define a single constructor (the primary) and that their first parameter is an AppContext.

     If they define more parameters in their constructors, they will try to be matched using the objects that you
     previously bound to the FlowsRunner. The matching is made via type and name.
     Normally one fo this looks like:

     .. code-block:: scala

       import uy.com.netlabs.luthier._
       import uy.com.netlabs.luthier.typelist._
       import scala.language._
       import uy.com.netlabs.luthier.endpoint.SomeEndpoint

       class MyFlows(appContext: AppContext) extends Flows {
         new Flow("test")(SomeEndpoint) {
           logic {req =>
           }
         }
       }


Transports
==========

In this section we show you the several transports implemented and how to use their ``EndpointFactories``.
When we list the factory methods, the parameters that have parameter equating a value, means that is their
default, and providing it is optional.

JMS Transport
-------------

JMS is a transport type that supports all the possible endpoints thanks to its versatility. Its endpoint factory is
located at ``uy.com.netlabs.luthier.endpoint.jms.Jms``. It has two main options:

.. code-block:: scala

  Jms.queue(queue: String, connectionFactory: ConnectionFactory, messageSelector: String = null, ioThreads: Int = 4)

Creates an endpoint that communicates with a JMS queue. The endpoint produced serves as Source, Responsible, Askable
and Sink endoint types. Note that since it is a valid Source and Responsible type, you will have to specify which type
of flow you want by expliciting it.

**Params:**

 * queue: name of the queue to use.
 * connectionFactory: JMS connection factory used to produce connections. Typically, you'll want a pooled connection
   factory.
 * messageSelector: JMS message selector used to filter accepted messages.
 * ioThreads: Amount of threads in its input-output threadpool. This threadpool is only used to perform JMS io, reading
   and writing messages, so normally a size of 4 is a good tradeoff between performance and thread footprint.

**Inbound message type:** Any
  That is the nearest common acestor for java.io.Serializable, String and array of byte messages.
**Supported payloads:** String, Array[Byte] and java.io.Serializable.
  Any of this types of payload is fine send over.

|
|
.. code-block:: scala

  Jms.topic(topic: String, connectionFactory: ConnectionFactory, messageSelector: String = null, ioThreads: Int = 4)

Creates an endpoint that communicates with a JMS topic. The endpoint produced serves as Source and Sink endpoint types.
Due to the nature of topics, it makes no sense to make the endpoint responsible or askable, only sink and source. So you
can listen to messages sent to it, and send messages to it.

**Params:**

 * queue: name of the topic to use.
 * connectionFactory: JMS connection factory used to produce connections. Typically, you'll want a pooled connection
   factory.
 * messageSelector: JMS message selector used to filter accepted messages.
 * ioThreads: Amount of threads in its input-output threadpool. This threadpool is only used to perform JMS io, reading
   and writing messages, so normally a size of 4 is a good tradeoff between performance and thread footprint.

**Inbound message type:** Any
  That is the nearest common acestor for java.io.Serializable, String and array of byte messages.
**Supported payloads:** String, Array[Byte] and java.io.Serializable.
  Any of this types of payload is fine send over.
|
|
Full example:

.. code-block:: scala

  import uy.com.netlabs.luthier._
  import uy.com.netlabs.luthier.endpoint.jms
  import scala.concurrent.duration._
  import language._

  object JmsTest extends App {

    val myApp = AppContext.build("Test Jms App")
    new Flows {
      val appContext = myApp

      // instantiate an ActiveMQ pooled connection factory to a broker in this machine
      val jmsConnectionFactory = {
        val res = new org.apache.activemq.pool.PooledConnectionFactory("tcp://localhost:61616")
        res.start
        res
      }

      val askMeQueue = Jms.queue("askMe", jmsConnectionFactory)

      new Flow("say hello")(askMeQueue)(ExchangePattern.RequestResponse) {
        logic { req =>
          req.map(payload => "Hello " + payload)
        }
      }

      new Flow("logQuestion")(Jms.queue("logQuestion",
                              jmsConnectionFactory))(ExchangePattern.OneWay) {
        logic { req =>
          //ask the request to the askMeQueue, and if that succeedes, send the response to
          //topic result
          askMeQueue.ask(req.as[String]) map {r =>
            Jms.topic("result", jmsConnectionFactory).push(r.as[String])
          }
        }
      }
      new Flow("listenResult")(Jms.topic("result", jmsConnectionFactory)) {
        logic {req => println("Result to some request: " + req.payload)}
      }

      new Flow("ping")(endpoint.logical.Metronome("ping", 1 seconds)) {
        logic {m =>
          println("...pinging")
          //send to logQuestion via push. The future returned represents the
          //operation, so we register a side effect on completion, to log
          //whether it was a failure or a success.
          Jms.queue("logQuestion", jmsConnectionFactory).push(m).
            onComplete (t => println("Ping result " + t))
        }
      }

      registeredFlows foreach (_.start)
    }
  }

The example sets up for interacting flows. Though they are all visible through JMS, to make the example
self-contained, we added a ping flow, that is triggered by a Metronome with a pulse that is the text ``"ping"`` every
one second. So every second we are sending a message to the logQuestion queue. The logQuestion in turn, assumes
the message is a string (we could've checked, but it wasn't important) and asks it to the askMeQueue which is handled
by the say hello flow. The later simple concatenates a string and sends it back, then the logQuestion will send
the reply to the result topic by mapping on the ask future.
