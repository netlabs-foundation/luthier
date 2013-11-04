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

Lets see a full example using one of the existing transports:

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

       class MyFlows(val appContext: AppContext) extends Flows {
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

**Location:** ``uy.com.netlabs.luthier.endpoint.jms.Jms``

JMS is a transport type that supports all the possible endpoints thanks to its versatility. It has two main options:

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
  Any of this types of payload is supported by JMS.

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

      startAllFlows()
    }
  }

The example sets up for interacting flows. Though they are all visible through JMS, to make the example
self-contained, we added a ping flow, that is triggered by a Metronome with a pulse that is the text ``"ping"`` every
one second. So every second we are sending a message to the logQuestion queue. The logQuestion in turn, assumes
the message is a string (we could've checked, but it wasn't important) and asks it to the askMeQueue which is handled
by the say hello flow. The later simple concatenates a string and sends it back, then the logQuestion will send
the reply to the result topic by mapping on the ask future.

File Transport
--------------

**Location:** ``uy.com.netlabs.luthier.endpoint.file.File``

Simple file transport support that adds basic functionality.

.. code-block:: scala

  File(path: String, charset: String = "UTF-8", ioThreads: Int = 1)

Creates a File endpoint to specific file, this endpoint supports Sink and Pull endpoint types.

**Params:**

 * path: Path to the desired file. If relative, the endpoint will resolve it to the root path of the flow's AppContext
 * charset: Charset to use when writing text to it.
 * ioThreads: Threads in its IO threadpool.

**In message type:** Array[Byte]
  Reading the file always returns its content as an array of bytes.
**Supported payloads:** Iterable[Byte], Array[Byte], String, and java.io.Serializable.
  Any of this types of payload is writable to a file.

Full example:

.. code-block:: scala

  import uy.com.netlabs.luthier._
  import uy.com.netlabs.luthier.endpoint.logical.Polling._
  import uy.com.netlabs.luthier.endpoint.file._

  import scala.util._
  import scala.util.hashing.MurmurHash3
  import scala.concurrent._, duration._
  import language._

  object JmsTest extends App {

    val myApp = AppContext.build("Test File App")
    new Flows {
      val appContext = myApp
      new Flow("monitor file")(Poll(File("path/to/file"), every = 1.seconds)) {
        @volatile var lastHash: Int = -1
        logic { m =>
          val fileHash = MurmurHash3.bytesHash(m.payload)
          if (lastHash != fileHash) {
            File("dest/path/newFile").push(m).onFailure {
              case ex: Exception => log.error(ex, "Failed to copy file")
            }
          }
        }
      }

      startAllFlows()
    }
  }

This example contains one flow which shows the two usage types for this endpoint. It polls a file every 1 second
and calculates a hash to see if it changed. In case it did, it copies over to another destination, registering a side
effect on the future to check if it failed and logs it.

JDBC Transport
--------------

**Location:** ``uy.com.netlabs.luthier.endpoint.jdbc.Jdbc``

This transports supports prepared statements via an Askable and a Pull endpoint. While in the pull endpoint you already
specify the parameters, in the askable one you set the parameters with the message that you ask.
In order to facilitate extraction of data, the factories to the endpoints take a row mapper function that takes a row
and produces a object. The Row interface is defined as:

.. code-block:: scala

  trait Row {
    def get[Type](col: String): Type
    def get[Type](index: Int): Type
  }

It provides to ways of getting a fields value, via column name, or positional index, just like JDBC. What's good about
this interfaces is that we can validate that the type you passed is a valid JDBC sql type, so that for instance, if you
request a java.sql.Date it will work, but if you request a java.util.Properties, it won't compile, saying that
java.util.Properties is not a mapped sql type.

Here are the factories:

.. code-block:: scala

  Jdbc.const[R](query: String, rowMapper: (Row) ⇒ R,
                dataSource: DataSource, ioThreads: Int)

Creates a JDBC constant pull endpoint, that is, the params are fixed.

**Params:**

 * query: SQL query to be performed.
 * rowMapper: Row mapper function that takes a row instance and returns an object of type R (for result).
 * dataSource: JDBC connection provider (pooled connection data source are recommended).
 * ioThreads: Threads in its IO threadpool used to perform the queries and extract the data with the row mapper.

**In message type:** IndexedSeq[R]
  Reading the endpoint will return instances of R (through an indexed sequence), where R is a type that you parameterize
  with the mapper function.

|
.. code-block:: scala

  Jdbc.parameterized[R](query: String, rowMapper: (Row) ⇒ R,
                        dataSource: DataSource, ioThreads: Int)

Creates a JDBC endpoint which takes sql parameters that are passed on each request (ask invocation). The query that
you pass must have sql parameters specified with ``?`` which you later pass on each ask via a tuple or sequence or
product instance (of which case class are valid candidates, read about them `here <http://www.scala-lang.org/node/107>`_).

**Params:**

 * query: SQL query to be performed, there should be sql paramaterized variables declared with ``?``.
 * rowMapper: Row mapper function that takes a row instance and returns an object of type R (for result).
 * dataSource: JDBC connection provider (pooled connection data source are recommended).
 * ioThreads: Threads in its IO threadpool used to perform the queries and extract the data with the row mapper.

**In message type:** IndexedSeq[R]
  Reading the endpoint will return instances of R (through an indexed sequence), where R is a type that you parameterize
  with the mapper function.
**Supported payloads:** IndexedSeq[_ <: Any], Product.
  Asking always take an instance of a product, or a sequence of values.

Full example:

.. code-block:: scala

  import uy.com.netlabs.luthier._
  import endpoint.jdbc._

  import scala.concurrent._, duration._
  import scala.util._
  import language._

  import endpoint.logical.Polling._
  import endpoint.logical.Metronome

  object JdbcFastTest extends App {
    //create a H2 in memory DB
    val dataSource: javax.sql.DataSource =
      org.h2.jdbcx.JdbcConnectionPool.create("jdbc:h2:mem:test", "test", "test")
    val conn = dataSource.getConnection()
    try {
      val st = conn.createStatement()
      st.execute("CREATE TABLE Coffees (cofName text, supId text, price double)")
      st.execute("INSERT INTO Coffees VALUES('Colombian', '101', 7.99)")
      st.execute("INSERT INTO Coffees VALUES('Colombian_Decaf', '101', 8.99)")
      st.execute("INSERT INTO Coffees VALUES('French_Roast_Decaf', '49', 9.99)")
    } finally { conn.close() }

    //Declare a class that represents our coffees
    case class Coffee(name: String, supId: String, price: Double)
    //define a mapper function from rows to coffees
    val coffeeMapper = (r: Row) => new Coffee(r.get[String](1),
                                              r.get[String](2),
                                              r.get[Double](3))

    new Flows {
      val appContext = AppContext.build("Jdbc Test")

      new Flow("Poll jdbc")(Poll(Jdbc.const("", coffeeMapper, dataSource), every = 1.second)) {
        logic {coffees =>
          for (coffee <- coffees.payload) println(coffee)
        }
      }

      val query = Jdbc.parameterized(
                       "SELECT * from Coffees WHERE price > ? AND price < ?",
                       coffeeMapper, dataSource)
      new Flow("Check coffee's prices")(Metronome(1.second)) {
        logic {pulse =>
          query.ask(pulse.map(_ => (5, 8))) map {coffees =>
            println("Best priced coffees: " + coffees.payload.mkString(", "))
          }
        }
      }
    }
  }

This example is quite self-explanatory (and admittedly silly). First we create an in-memory H2 DB for the example and
populate it. Then we declare our class that represents the table in the DB and a row mapper for it, that maps the
columns positionally. Finally we declare our Flows container with two flows to showcase the two type of endpoints, where
the first one polls the database every one second with a fixed queries and lists the avaiable coffees, and the second
one shows how you could define a parameterized query that you specify its parameters on each call.

WebService Transport
--------------------

**Location:** ``uy.com.netlabs.luthier.endpoint.cxf.codefirst.Ws`` and ``uy.com.netlabs.luthier.endpoint.cxf.dynamic.WsInvoker``

The implementation of the web-service transports makes use of Apache CFX library to provide SOAP enabled web-services.
Since SOAP webservices are shema-full endpoints, the bindings that are necessary are usually too coupled and verbose
(take for example javax-ws), they usually demand that you write a bunch of classes that represent your webservice,
annotate them with javax-ws annotations, and then run a tool that generates the wsdl. The opposite is not as complicated,
but couples your code to the webservice more tightly, since you use a tool to import a wsdl, and it generates a bunch
of classes which you ultimately use.
In the implementation of this endpoint, we tried as much as possible to make your code independant of the specifics
of said bindings (i.e.: you won't be running any of those tools, and your webservices should be dynamic).

.. code-block:: scala

  Ws[I, PL, R](sei: Sei[I], maxResponseTimeout: FiniteDuration,
               ioWorkers: Int)(webMethod: (I) ⇒ MethodRef[I, PL, R])

This factory creates a Responsible endpoint that serves a web-service as specified by the Interface ``I``.
The types ``PL`` and ``R``, represent the payload type of incoming request, and the response type, of the expected response.
Note that this parameters only appear on that interface MethodRef, which we are supposed to provide with a function
in the second parameter list. This function represents the web-service method that we are defining in the flow declared
with this endpoint, and that syntax basically enables a DSL that allows you to select a method from the interface ``I``
from a proxy instance of it.
An example will make this more clear, lets first declare a webservice defintion:

.. code-block:: scala

  trait MyWebservice {
    @javax.jws.WebMethod
    def greet(user: String): String
    @javax.jws.WebMethod
    def sum(a: Double, b: Double): Double
  }

Unfortunately, we still need to declare an interface with an annotations on the methods like in java, though we hope
that you find the succint syntax of Scala for this easy enough to make it bearable, like we do.
The transport allows us to decouple the webservice implementation by letting us define a flow for each web method, but
first we need a service endpoint interface that represents our webservice. Lets do that and also write a flow for sum:

.. code-block:: scala

  val endpointIface = Sei[MyWebservice]("http://localhost:8080", "/myws")
  new Flow("sum")(Ws(endpointIface)(_.sum _)) {
    logic {req =>
      val reqParams: Tuple2[Double, Double] = req.payload
      //return the addition
      req.map(_ => reqParams._1 + reqParams._2)
    }
  }

A ``Sei`` instance is usually declared as a single field, instead of declaring it directly in the ``Ws`` factory call,
since it contain the setup for the webservice server that will serve our methods, this way you can have many flows
using the same sei, that together implement the methods of a webservice. If you don't do it this way, you would need a
different address for each web method, but due to the nature of webservices, all of the methods would be served under
each address, but the non-implemented ones will always return an exception.
Now the explanation of the MethodRef DSL part that we were talking about, note how nicely we selected the method that we
wanted to implement. This syntax means, the underscore would represent a MyWebservice instance, for which we are
selecting the method sum *but*, instead of calling it, we write a blank and an underscore. This tells scala that we do
not want to call the method, but instead, we like a function from it with the same signature (this is known as
`currying <http://en.wikipedia.org/wiki/Currying>`_), in this case, it will be a function that takes two doubles and
return one double.
This function will in turn be transformed in our MethodRef capturing the types of the signature, so that our endpoint
is fully typed with respect to the method that we are implementing. The mapping happens like this, all the parameters
that the method takes are boundled up in a tuple instance (we showed that in the example by declaring a field reqParams
of type Tuple2), so if you take 10 parameters, you get a Tuple10. In scala, tuples range from 2 to 22 so thats the
maximum amount of parameters that you can take (and if you are taking more, you are probably doing something wrong), and
each value in the tuple is accessed with a field named ``_i``, where i ranges from 1 to the size of the tuple. If your
method only takes one parameter, then thats the request type. It is not tupled.

.. NOTE::

  A nice syntax in scala for untupling is:

  .. code-block:: scala

    val (a, b) = myTuple2
    val (a, b, c) = myTuple3
    ...
    val (e1, e2, e3, ..., e22) = myTuple22

Finally, as expected, the return type of the method, is also what our responsible expects.

**Params:**

 * sei: The service endpoint interface that defines our web-service.
 * maxResponseTimeout: Timeout for responses to ocurre. Remeber that http is synchronous, but our flows are not, so we
   must have a timeout after wich, an exception is returned to the client.
 * ioThreads: Threads in its IO threadpool used to perform the queries and extract the data with the row mapper.
 * webMethod: WebMethod that we want to implement.

**Inbound message type:** TupleN[N1, N2...]
  Request will always be instances of tupleN or the type of the single parameter, as described in the endpoint description.
**Supported payloads:** WebMethod return type.
  The only accepted type is the type declared in the web method.

|
.. code-block:: scala

  WsInvoker[Result](client: WsClient, operation: String,
                    shutDownClientOnEndpointDispose: Boolean = false)

Creates a webservice invoker endpoint that is an Askable endpoint. The key to the endpoint is the WsClient instance
that is a dynamic webservice invoker of CXF.
So, in order to define a WsInvoker, lets first define a WsClient for the webservice we defined earlier and note, that
we do not need any kind of shared api, that for instance could've been the MyWebservice interface:

.. code-block:: scala

  val client = WsClient("http://localhost:8080/myws?wsdl")

That line will actually connect to the server and download the wsdl, generatic the specific classes in a runtime,
client-specific, classloader.

Now, inside a flow definition, we could invoke the sum web method with:

.. code-block:: scala

  val resp: Future[Message[Double]] =
    WsInvoker[Double](client, "sum").ask(m.map(_ => Seq(4.0, 6.0)))

We instance the endpoint with the type of the result, which we known to be Double, the client we previously instantiated,
and the name of the method we wish to invoke. With our endpoint set, we call the webservice method, by asking a message
with a payload of type Seq, and the parameters to the method, in this case, sum takes two doubles and so we passed.

Now suppose that the webservice we are trying to consult, takes as parameter some special class they defined, say for
example a User. Tools like jaxws would typically create a class User for you when you import the wsdl, and you would
have to use that code in a statically coupled way.
Since the purpose of our endpoint is that it is dynamic, so that we skip all together the import step, and so its runtime
dependant, we need a way to instantiate the that type User without actually knowing it fully. For this purpse, WsClient
has a method ``instance(className: String)`` that instantiates a class that it dynamically produced when instantiated,
and that it has in its internal classloader. Lets create an instance of the user class.

.. code-block:: scala

  val user = client.instance("some.package.User")

In order to make it easy to set the parameters that we do know the class have, we make use of a special capability of
Scala that lets us do runtime dispatch of methods, so that user instance that the WsClient returned, is actually dynamic,
and when you call methods like:

.. code-block:: scala

  user.setName("Daniel")
  user.setLastName("Rabinovich")

It will actually search of a method setName and setLastName in the dynamic class and call them.
Finally, to pass this user to a method request, you would ask like this:

.. code-block:: scala

  myInvoker.ask(m.map(_ => user.instance))

The value instance of user, is the actual instance of the dynamic class.

Note how our web-service client is not code coupled to the User class, they may add other attributes, and our code
would still work, because the class is runtime created. We need not recompile our code with a more recent version of the
wsdl to make use of newer attributes.

**Params:**

 * client: Web-service dynamic client that handles the CXF code related to processing the webservice.
 * operation: Name of the web method we want to invoke.
 * shutDownClientOnEndpointDispose: Whether or not to close the WsClient after the ask invocation. Under some
   circumstances, you might want to create a brand new WsClient for each call and dispose it afterwards. Other times
   you want to reuse the WsClient that you already created, since this saves up the time of downloading the wsdl, parsing
   it, and creating the dynamic classes. Logically the second option is the default.

.. WARN::

  The WsInvoker endpoint makes use of the Flows blocking thread pool by using the function ``blocking``. Please have
  this in mind with regard to thread pool size allocations, because you might be using ``blocking`` yourself, or might
  want to limit the amount of concurrent requests to the server.

**In message type:** Specified response type declared on the WsInvoker instantiation.

**Supported payloads:** Seq[Any] or Product.
  The endpoint will accept in its ask operation either. For more that one parameter, tuples might be handy, but for
  single parameters, since there is no syntax for Tuple1, you might want to use Seq, or you might want to always use
  Seq for consistency.

Full example:

.. code-block:: scala

  import uy.com.netlabs.luthier._
  import uy.com.netlabs.luthier.endpoint.cxf.codefirst._
  import uy.com.netlabs.luthier.endpoint.cxf.dynamic._
  import uy.com.netlabs.luthier.endpoint.logical.Metronome

  import scala.concurrent.duration._

  import language._


  class User {
    private var name: String = null
    def setName(name: String) {this.name = name}
    def getName() = name
    private var lastName: String = null
    def setLastName(lastName: String) {this.lastName = lastName}
    def getLastName() = lastName
  }

  object WsExample extends App {

    trait MyWebservice {
      @javax.jws.WebMethod
      def greet(user: User): String
      @javax.jws.WebMethod
      def sum(a: Double, b: Double): Double
    }

    new Flows {
      val appContext = AppContext.build("Ws Test")

      val sei = Sei[MyWebservice]("http://localhost:8080", "/myws")
      new Flow("sum")(Ws(sei)(_.sum _)) {
        logic {req =>
          req map (params => params._1 + params._2)
        }
      }
      new Flow("greet")(Ws(sei)(_.greet _)) {
        logic {req =>
          req.map(user => "Hello Mr. " + user.getLastName)
        }
      }

      val client = WsClient("http://localhost:8080/myws?wsdl")
      new Flow("consult webservices")(Metronome(1.second)) {
        logic {req =>
          val user = client.instance("User") //User is not in any package.
          user.setName("Carlos")
          user.setLastName("López Puccio")
          val greetResp =
            WsInvoker[String](client, "greet").ask(req.map(_ => Seq(user.instance)))
          greetResp map {resp => println("Greeted with: " + resp.payload)}
          val sumResp =
            WsInvoker[Double](client, "sum").ask(req.map(_ => Seq(10, 50)))
          sumResp map {resp =>
            assert(resp.payload == 60, "Sum must yield 60")
          }
        }
      }
    }
  }

The example puts together everything that we already described in the description sections of the endpoints. First it
declares a typical User class, and the MyWebservice interface. Inside the Flows container, the first two flows serve
the two methods of the webservice reutilizing the ``sei`` instance. The third flows creates a WsClient against the
webservice we created previously, and queries it every one second for a greet and a sum. Not the use of the dynamic
instance for User, and how we specify the expected return type between brackets when we declare the WsInvoker endpoint.

HTTP Transport
--------------

**Location:** ``uy.com.netlabs.luthier.endpoint.http.Http``

The HTTP transport is implemented using Jetty to server http request, and AsyncHttpClient for doing requests. It uses
two complementary libraries to do both tasks called databinder.unfiltered (server) and databinder.dispatch (client).

.. code-block:: scala

  Http.server(port: Int)

Creates a Responsible http server endpoint that listens on the given port. Since http requests are always synchronous and
expect a response, there is no Source implementation.
The api of dispatch.unfiltered enhances the api of standard javax.servlet.http.HttpServletRequest by providing a DSL
for extracting information. A typical request
is handled like:

.. code-block:: scala

  new Flow("http-server")(Http.server(3987)) {
    import unfiltered.request._ //bring into scope the unfiltered DSL
    logic { m =>
      m.payload match {
        //respond a string with the requested path
        case GET(Path(p)) => m map (_ => p)
      }
    }
  }

The payload of the request is javax.servlet.http.HttpServletRequest, which is the expected type that unfiltered handles,
while the expected response is either a String, or an uniltered ResponseFunction. Read about unfitlered's DSL
`here <http://unfiltered.databinder.net/Request+Matchers.html>`_

**Params:**

 * port: The port where the Jetty server listens for requests.

**Inbound message type:** javax.servlet.http.HttpServletRequest.
  A typical servlet request, which allows you to use fully its api, or use the unfiltered helpers.
**Supported payloads:** String or unfiltered.response.ResponseFunction[javax.servlet.http.HttpServletResponse].
  You can return a plain string, or use one of the many response functions that unfiltered has, for example to return
  a json message.

|
.. code-block:: scala

  Http[R](req: RequestBuilder, handler: FunctionHandler[R],
          ioThreads: Int, httpClientConfig: AsyncHttpClientConfig = defaultConfig)

Creates a Pull endpoint with the specified request and response handler. Using dispatch, you specify a request and how
to handle response independently. Requests will typically include post params, or credentials, while response handlers
will tranform the response to some sensible data, for example transforming an html page with jsoup to a org.w3c.Document.
For documentation on dispatch see `here <http://dispatch.databinder.net/Dispatch.html>`_

**Params:**

 * req: Request object. Contains url, credentials, http method, and request content.
 * handler: Function that transforms the server response into something useful. Goes from something as simple as a
   StringResponseHandler, to BytesResponseHandler, JsonResponseHandlers and DocumentResponseHandlers.
 * ioThreads: Amount of threads passed to the configuration of the HttpClient.
 * httpCientConfig: A thorough configration of the AsyncHttpClient can be passed with this parameter. You can either use
   its builder interface as defined `here <http://www.jarvana.com/jarvana/view/com/ning/async-http-client/1.7.0/async-http-client-1.7.0-javadoc.jar!/index.html?com/ning/http/client/AsyncHttpClientConfig.html>`_.
   You can our the helper object uy.com.netlabs.luthier.endpoint.http.ClientConfig, which takes every parameter as optional,
   allowing you to use it like ``ClientConfig(allowPoolingConnection = true, requestTimeoutInMs = 15000,
   realm = someRealm, sslContext = someSslContext)`` to create a AsyncHttpClientConfig instance.

**In message type:** R.
  R where R is the return type specified in the FunctionHandler that you pass in the handler param.

|
.. code-block:: scala

  Http[R](ioThreads: Int = 1, httpClientConfig: AsyncHttpClientConfig = deaultConfig

Creates an Askable endpoint. This endpoint works similarly as the pull endpont, except that you pass the request and
the response handler in the ask message.

**Params:**

 * ioThreads: Amount of threads passed to the configuration of the HttpClient.
 * httpCientConfig: A thorough configration of the AsyncHttpClient can be passed with this parameter. You can either use
   its builder interface as defined `here <http://www.jarvana.com/jarvana/view/com/ning/async-http-client/1.7.0/async-http-client-1.7.0-javadoc.jar!/index.html?com/ning/http/client/AsyncHttpClientConfig.html>`_.
   You can our the helper object uy.com.netlabs.luthier.endpoint.http.ClientConfig, which takes every parameter as optional,
   allowing you to use it like ``ClientConfig(allowPoolingConnection = true, requestTimeoutInMs = 15000,
   realm = someRealm, sslContext = someSslContext)`` to create a AsyncHttpClientConfig instance.

**In message type:** R.
  R where R is the return type specified in the FunctionHandler that you pass in the handler param.
**Supported payloads:** (Request, FunctionHandler[R]) .
  You must ask messages with a paylod of tuple2 containing the request, and the function handler

Full example:

.. code-block:: scala

  import uy.com.netlabs.luthier._
  import endpoint.http._
  import endpoint.file._
  import endpoint.logical.Polling._

  import language._
  import scala.concurrent.duration._

  import dispatch.{Http => _, _} //import everything, except Http, so that
  //it doesn't collide with our Http object

  object HttpExample extends App {

    new Flows {
      val appContext = AppContext.build("Http Example")

      new Flow("http-server")(Http.server(8888)) {
        import unfiltered.request._
        import unfiltered.response._
        logic {req =>
          req.payload match {
            //capture the path of the past, as well as the post params
            case POST(Path(p)) & Params(params) =>
              //return a simple string representing the parameters map
              req.map(_ => params.mkString("\n"))

            case GET(Path(p)) =>
              //serve files relatives to root (not recomended but still)
              //remember that File(p).pull() returns a Future[Message[Array[Byte]]], so we map
              //the future message, to a message of ResponseBytes containing the read bytes
              //so that we return a Future[Message[ResponseFunction[Array[Byte]]]]
              File(p).pull().map(bytesMsg =>
                bytesMsg.map(bytes => ResponseBytes(bytes))
              ).recover {
                case ex: java.io.FileNotFoundException =>
                  req.map(_ => NotFound ~> ResponseString("File " + p + " not found"))
                case ex: Exception =>
                  req.map(_ => ServiceUnavailable ~> ResponseString(ex.toString))
              }

            //for all other cases...
            case _ =>
              req.map(_ => MethodNotAllowed ~> ResponseString("Must be POST OR GET"))
          }
        }
      }

      new Flow("poll-google")(Poll(
          Http(url("http://www.google.com").setFollowRedirects(true),
               new OkFunctionHandler(as.String)),
          every = 10.seconds)) {
        logic {req =>
          val askReq =
            Http[String]().ask(req.map(_ => (url("http://www.google.com").
                 setFollowRedirects(true), new OkFunctionHandler(as.String))))
          askReq map (res => assert(req.payload == res.payload,
                                    "Polling and asking the same, should return the same."))
        }
      }
    }
  }

The example starts with a server flow on port 8888. Before defining the logic, we bring into scope the DSLs of
unfiltered for requests parsing and response creation. In the logic, we know that the payload is an http request
so we write a match clause to handle the cases that we are about using unfiltered's DSL.
We spefically handle two cases, POSTS and GETS. For the former we are simply returning a string concatenation
of the parameters passed in the POST request. For the GET case, we are doing something more interesting (and violating
all security advises), we are retrieving from the filesystem, files with a path relative to the rootLocation of the
AppContext via the File endpoint, then we map its result (a future of message of a byte array) to create a message
with a ResponseBytes wrapping the bytes read, and we return that. Not how composition shines here, and even in the
case that the file does not exists, because we are handling that case by recovering the failed future, handling
specifically the file not found case, and returning a very crude 404, we are also handling other general exception
that might happend and return service unavailable.
Finally, for all other http requests, we return a method not allowed.

The second flow showcases the poll and ask endpoint by doing the very same operation on boths. The request in both
cases it created by requesting an url, configuring it to follow redirects is there are any, and passing as response
handler an OkFunctionHandler which transforms the response to a String. OkFunctionHandler is a handler that will fail
for http status codes distinct from 200. There are other ways to setup FunctionHandlers and other converters like
``as.Bytes``. Again, for more functionality read databinder.dispatch's `documentation <http://dispatch.databinder.net/Dispatch.html>`_.
