============
Introduction
============

What is Luthier?
===============

Luthier is a library that provides `ESB <http://en.wikipedia.org/wiki/Enterprise_service_bus>`_ like features,
allowing for quick definitions of data flows between different transports. It models accurately the different exchange
patterns in endpoints, imposing no payload overhead, so that you are in charge for what is actually sent over.

Luthier has a different philosophy from the most prominent open ESBs becuase it prioritizes type safety and predictability
of a flow outcome. To do this, we chose to create a type-safe domain language in a wider, general purpose language.
This approach allows you to learn just the DSL and do a lot of stuff without knowing how to program in that language, but
when things gets really complex, by learning a few things, you can still model them out elegantly.

In order to provide this, we chose `Scala <http://www.scala-lang.org>`_, since it is a jvm compatible language, and it
provides the language abstractions we needed to create our DSL.

Luthier also promotes the actor model for concurrency, and so `Akka <http://akka.io>`_ was a natural choice. With it
we can scale up vertically and horinzontally with ease. Please visit the Akka website for more information.


Core concepts
=============

We'll describe in this section the key compontents that comprise the library. Examples shown here will by partial (we
might leave imports out, as well as other contextual items). For a full working example, please go to the section
`Getting Started`_.

Let's start by having a look at how a typical flow looks like:

.. code-block:: scala

  new Flow(name = "greet")(endpoint = SomeResponsibleEndpoint) {
    workers = 10
    logic {rootMessage: Message[String] =>
      val response: Message[String] = rootMessage map {previousMessageContent: String =>
        "Hello " + previousMessageContent
      }
      response
    }
  }

This minimal flow, will receive a message of type String, and will respond a new message which simply greets whatever
text was received.

Its easy to infer the structure of the flow so lets go over those concepts.
First, to instantiate a flow, you need to give it two parameters, the name of the flow, and the inbound endpoint
(note that naming the parameters is optional and it is named here for illustrational purposes). You might be confused
about the usage of parenthesis at this point, but it is enough to know that in scala, your methods (and constructors)
might declare more than one parameter list, which might be good for grouping parameters (and some other usages will
see later). In our case, separating the name from the endpoint declaration is visually appealing.


.. _logic method description:
|
After the declaration comes the structure of the flow. Between the logic and the flow declaration, you can modify some variables of
the flow, for example, the amount of workers. In this section of the flow declaration, you could also declare your
own variables that could be accessed from each flow run, though care must be taken because of the concurrent nature
of the runs.

the logic part of the flow, defines what it does with each message it receives. You define it like:

.. code-block:: scala

  logic {<rootMessageName>[: <RootMessageType] =>
    <flowLogic>
  }

<rootMessageName> is a name you give to the message that originates the flow run, and that you can refer to trhoughout
the flow logic. You can also specify its type, for clarity, but it is optional, since the flow already knows the
type of the messages that originates flow runs from the endpoint you used to create it.

In the body of our logic, we are declaring a value (val, which is an immutable variable) that contains the result of
transforming the root message, and then we write a statement with it. The last expression of the logic block
is what the flow should return (in case it is a request-response flow), so here our logic is returning `response`.

.. NOTE::

  The method logic takes a single parameter (if you have been following the lambda project of java 8, you should already
  infer what it takes), which is a function that takes a Message. So we declare said function between curly braces, and
  the arrow separates the arguments the function take from its body.


Let's see each concept in more detail.

Message
-------

Is the unit that carries the payload and associated metadata throughout the flow. In its definition, it knows
the type of its payload.
A flow run is initiated when the root transport wraps the payload in a Message object, and requests a run of the flow.
During the flow, you will typically transform this message, possibly sending it to other endpoints for processing and
then aggregating their results.
One important thing about messages, is that you should never create them, instead, you should always obtain a new
modified version via a transformation on a previous one (being the flow run originating message, the root one).
Doing otherwise is possible, but you would be losing the metadata associated with the message, that might be important
(for example, a reply to destination in a JMS based flow). In order to promote this, we made it a little difficult (or
verbose) to instantiate messages from scratch, and easy to transform a previous instance to obtain what we desire.
In case you are wondering, what If the message I want has nothing to do with the previous one? then you can safely
ignore the previous content in the transformation.
The method map defined on message is what we use to obtain new ones. It's structure is:

.. code-block:: scala

  message.map{previousContent[: Type] => <listOfExpressions>}

where listOfExpresions are any expression you like, and you may use the previousContent, or ingore it.
You can specify the type of the previous content for clarity, but note that since the language knows the content
type of the message, when mapping, you don't need to tell him the type of the payload.
Please note that mapping always returns a new message instance, immutability is a critical concept in a big
concurrent system, so in Luthier we strive to keep mutability at its minimum.

A common pattern when ignoring the previousContent is giving the variable the name `_` like:

.. code-block:: scala

  message.map{_ => newPayload}

In such case, the underscore acts as a placeholder (hence the character used), it indicates that there is a variable
there, and that we don't care about it.


Endpoint
---------

They come in five different flavors each modeling a specific message exchange pattern. They are: Source endpoints,
Responsible endpoints, Sink endpoints, Askable endpoints and Pull endpoints.

When defining a flow, you must provide it either with a Source endpoint, or a Responsible one, because flows always
need an inbound endpoint. The rest are meant to be used in the flow logic.

Endpoints are never instantiated directly, instead you access them through a EndpointFactory. This allows for an
automatic lifecyle management, as well reusage features, specially when it comes to resources (think of a connection
to somewhere for example).

Inbound Endpoints
*****************

Source and Responsible are the only inbound endpoint, these can only be used by passing their factories to a Flow
definition.
Inbound endpoints do more than just originate messages, they also define the exchange pattern and the type of messages
that are valid as in and out messages.
This is a very important feature in Luthier, since flows will validate that you reply a valid message given the transport
you chose. For example, if you are using a JMS responsible endpoint, the accepted type of message you may respond are
String, Array[Byte] or java.io.Serializable objects, since these are the types that JMS natively support.

In turn, Source endpoints define one-way flows, that is, you cannot respond to the sender, while Responsible
endpoint define request-response flows, which means you must *always* provide a response.

Outbound Endpoints
******************

Sink and Askable are the two types of outbound endpoints, since they send something over the transport on demand.
This endpoints are used inisde the logic definition, and they return a `Future <Futures>`_ object representing the
asynchrounous computation they will perform.

Sink endpoints, as their name imply, simple send something over the transport, obtaining no response. Typical sink
endpoints may be log endpoints, or an endpoint to execute statements (non queries) to a database. They only method
they provide is push. Usage is like:

.. code-block:: scala

  [val future = ]SomeSinkEndpoint.push(myMessage)

Like we said, pushing something over the sink, returns a future, even when there is no answer. This future represents
the completion of such task, and it might result in failure, so you can check the future if you want.

Askable endpoints on the other hand, send something over the transport, but always expect an answer back.
Usage is like:

.. code-block:: scala

  [val responseFuture = ]SomeAskableEndpoint.ask(myMessage[, timeout = someTimeout])

In the case of the askable endpoints, the future it returns also represents the anwser we will get, or the exception
if the operation failed.
The timeout parameter we specified, hints the transport that it should provide a result in the future in at most
that time. If the timout is exceeded, it should complete the future with a timeout exception.

For better understanding of futures, please read its section.

Pull Endpoints
**************

This endpoints are not inbound, since they cannot define a flow, and are not outbound, since they cant send anything.
They can only attempt to retrieve something when asked. This kind of endpoint may represent task like reading the
content of a file, or an URL, or executing some predefined select on a database, or running a system process
and obtaining its output. You can think of them as an Askable endpoint that you ask nothing, and it provides an answer.

Their usage is like:

.. code-block:: scala

  [val valueFuture = ]SomePullEndpoint.pull()

Although we marked valueFuture as optional, it would not make much sense to run a PullEndpoint ignoring its result.
The pull operation returns a Future with the data that we are pulling, or an exception if something went wrong.

Logical Endpoints
*****************

These are endpoints that do not represent a transport per se, but that add value over other kind of endpoints (thats why
they are logical).

Right now, Luthier has only a two logical endpoints, Metronome and Polling endpoint.

A Metronome endpoints takes its concept from the musician tool, because it emits a pulse at a constant rate. With this
endpoint, you choose what the pulse is. For example:

.. code-block:: scala

  new Flow(name = "metronome")(endpoint = Metronome(pulse = "Pulse", every = 1 second)) {
    logic {rootMessage: Message[String] =>
      log.info("A pulse was received, it contains: " + rootMessage.payload)
    }
  }

The Polling endpoint, allows us to compose it with Pull or Askable endpoints to create a Source endpoint. For example
suppose you have a webservice, that you want to consult periodically. Since webservices are by nature request-response
endpoints always, they make up for a good askable endpoint. Now you want your flow to be run with the result of asking
something to that webservice. It could look something like this:

.. code-block:: scala

  new Flow(name = "poll-web-service")(endpoint = Poll(endpoint = MyWebServiceEndpoint,
                                                      every = 1 second,
                                                      message = (wsParam1, wsParam2))) {
    logic {wsResponse: Message[WsResponse] =>
      log.info("Poll result: " + wsResponse.payload)
    }
  }


Flows
-----

Flows (yes, in plural) is the container that allows us to define flows. They have a reference to an AppContext which
provide the root path of the flows (useful value to use inside them) and the actor system, which is the environment
that controls our concurrency parameters, as well as support clustering and logging.
A Flows instance will hold a reference to all the flows defined in it, so its easy to start, or stop them all at once.

.. NOTE::

  Though currently not used, this is a good point for extensions. You could for example extend the Flows container
  with monitoring, and all the flows defined in it would automatically gain that functionality.


Flow
----

If you have been reading orderly, you should have a pretty good idea by now of how ot work with flows. In this section
we will explain some of its components.

logic
*****

We use this method to provide the *logic* that our flow executes every time that it receives an incoming message.
We already describe the structure of this method, so if you skip it, please read the `logic method description`_.

The logic block must comply with the defintion of the Flow. That is, when you declare a flow, and you give it a root
endpoint, that endpoint actually tells the flow three things: the payload type of the incoming messages, whether or not
it is request-response or one-way, and, in case it is request-response, the valid response types. Many source endpoints
declare a very generic payload type, or the most generic one being `Any` (which as its name states, it can be anything).
In such cases there are several tools you can use to work with the specific payload.
The first tool is the the `as` operator of messages. Suppose you are working with JMS, and you know that through that
queue that you are using, you are only sending messages of a specific type, since JMS supports several divergent types,
the endpoint would declare an Any payload, in order to say, this message is of this type (which is known as casting)
you do:

.. code-block:: scala

  logic {inMessage: Message[Any] =>
    val myMessage: Message[MyType] = inMessage.as[MyType]
  }

Your second tool, is type match. Suppose now that through another queue, you receive message of several different
types, you can do a type match to handle each specific case as follow:

.. code-block:: scala

  logic {inMessage: Message[Any] =>
    inMessage.payload match {
      case typeA: TypeA =>
        ...
        inMessage.map(...)
      case typeB: TypeB => inMessage.map(...)
      case other => inMessage.map(_ => "Unkown message: " + other)
    }
  }

The match statement acts like a switch, only one of the case definitions will be run. The last expression of the
executed branch of the switch, is the return value for the logic (in case this is a request-response flow).
Note how in the last `case` statement we do not declare the type of other, this acts as a wildcard, so we can handle
unexpected cases.

Another important aspect of the logic is the return value when you are defining request-response flows.
Remember that when you define a flow with a responsible endpoint, the later specifies what is allowable as a response.
Depending on the the endpoint, there might be several possible respose types. Its responsability of the documentation
of such endpoint to state what is it that it accepts, but when you provide a type that doesn't validate, you will
receive a compilation error like:

::

  Invalid response found: String.
  Expected a Message[T] or a Future[Message[T]] where T could be any of [
      String
      Array[Byte]
      java.io.Serializable
  ]
          "someMessage"

In that example, we forgot to return `"someMessage"` inside a message object via mapping on the root message, hence
the compiler complaints.
There is another important piece of information in that compilation error. Note that you are allowed to return either a
Message of an accepted type, or a Future of a Message of the expected type. If you read the section of endpoints already,
you know that most of them return a Future of a value as a consecuence of using them, that Future encapsulates their
possible response (in case of an askable endpoint) or failure. There are other tools that also wrap their result in a
future, because of their asynchronous nature (see for example `blockingWorkers and the blocking method`_). This means
that you can return either a message of the expected type, because you already have it, or a future that will eventually
contain a valid type. This is a really useful composition tool, because writing forwarer flows becomes trivial, like this
one:

.. code-block:: scala

  //Forward a webservice call in case that we can't handle it
  new Flow("endpoint-forwarder")(Jms.queue(..., jmsConnectionFactory)) {
    logic {req => Jms.queue(..., jmsConnectionFactory).ask(req) }
  }

Exchange pattern
****************

As you have seen, the exchange pattern of the flow depends entirely on the root endpoint you used to define it. Now,
due to the nature of various transports, it makes sence for its endpoint to implement more than one endpoint type,
and this might be a problem when you try to define a flow with and endpoint that is both a Source, and a Responsible
endpoint.
To solve this, we have to explicitly specify the exchange pattern in the flow optional third parameter list like this:

.. code-block:: scala

  new Flow("flow1")(SomeHybridEndpoint)(ExchangePattern.RequestResponse) {
    logic {req => ... }
  }
  new Flow("flow2")(SomeHybridEndpoint)(ExchangePattern.OneWay) {
    logic {req => ... }
  }

.. HINT::

  Remeber when we said that having multiple parameter lists had other usages? well, this is one of them, making them
  optional. In this case, the exchange pattern is infered via your endpoint type, only failing when your endpoint
  supports both type.

name
****

The name of the flow is pretty much self explanatory, though one detail is important. This name must be unique for the
given AppContext defined in the container Flows. This is like this, because there is an Akka actor for every flow,
which is the one in charge of running for each incoming message.

rootEndpoint
************

Is the source endpoint used to define the flow. Normally, you will never have to use this value.

log
***

Is the logging facility of the flow. Contains the typical logging operations you would expect.
The log instance is constructed based on the actor name of the flow, so when you log, you know exactly which flow is
doing it. Here is an excerpt of the operations it supports:

 * info(message: String)
 * warning(message: String)
 * error(message: String)
 * debug(message: String)

For a complete defintion, visit its documentation page: http://doc.akka.io/api/akka/2.0.2/#akka.event.LoggingAdapter

workers
*******

This variable defines the amount of workers to create for the actor. Its default value is 5, but you can change this
in the section that goes between the flow declaration and its logic, like:

.. code-block:: scala

  new Flow(...)(...) {
    workers = 10
    logic {rootMessage: Message[String] =>
      ...
    }
  }

This means that the flow will be run at most 10 times concurrently.

Its important to highlight, that the workers of the flow are the ones executing the instructions in the logic
block, **and nothing more**. That means that when the logic of your flow does a request on an askable endpoint for
example, it will **not** block the flow workers during that request. Instead, when the transport effectively's got the
result (whether it is the response or an exception), it will ask the flow to resume the execution it suspended.

This is one of the key concepts of the architecture, that is non blocking. The workers of a flow will only be limited
by cpu and will not block on endpoint usage.

blockingWorkers and the blocking method
***************************************

Sometimes in the logic of a flow, you need to do a blocking call, be it because you are interfacing with another library
or because Luthier didn't provide an endpoint for that, and you don't want to write one. In such cases, it might be
easier to just block (for example, opening an reading on a socket). Since not blocking the workers actors is crucial,
we provide a bunch of workers per flow for this exclusive purpose. `blockingWorkers` define the amount of workers, which
defaults to 10, and the method blocking is used to submit a task for them. A future object will be returned encapsulating
the asynchronous result. Usage is like:

.. code-block:: scala

  new Flow(...)(...) {
    blockingWorkers = 10
    logic {rootMessage: Message[String] =>
      ...
      val result: Future[Message[<blockingOpResultType>]] = blocking {
        val blockingOpResult = someBlockingOperation
        rootMessage.map(_ => blockingOpResult)
      }
      result
    }
  }

In the snippet above, we declare that when we receive a request, we must perform some blocking operation that outputs
a `blockingOpResult`, we then create a message with that `blockingOpResult`, and that last statement is what blocking
will return, eventually. Outside of the blocking call, we assign its result in a `result` value, and we define that
our flow returns that.
In the example, `<blockingOpResultType>` represents the type of the `someBlockingOperation` call, that we later return
in our message.

Future
------


Flow Run
--------

This is a special value that normally you don't deal with. It represents, as it name implies, a run of a flow. It is
a value that is implicitly available in a run, and that adds coherence to it. It is defined by the root message that
originates a run, plus the specific flow that defined it.

Normally, the expected usage for it is to define generic logic that can apply to different flow logics.
Its type is defined as FlowRun[FlowType] where FlowType is bound to be the actual flow to which the run belongs.
There are some really advance things you can do with the flow run, here we'll show a simple one, we'll define a
helper function that sends any message to a predefined JMS log queue:

.. code-block:: scala

  def sendToLog(m: Message[_])(implicit flowRun: FlowRun[_ <: Flow]) = {
    import flowRun.flow._
    Jms.topic("log", jmsConnectionFactory, ioThreads = 1).push(m.as[Serializable]) //assert the message IS serializable
  }

In this example we are defining a method that accepts any message (again, the underscore acts as a placeholder) and then
we declare a second parameter list that is implicit and takes a flow Run of some type that extends Flow (when
when you define a Flow, you are creating a new type that extends it). Because the second parameter list is implicit, if
all the parameters are implicitly available in the scope of the call to the operation, you won't need to provide them.
Like we said previously, the flow run is implicitly present during the flow so it *will* be provided.
In the content of the method, we first import `flowRun.flow._`, that is, we bring into scope the content of the flow that
the flow run refers to. This is necessary since using endpoints and stuff depends on a flow context.

Of course, usage would be like:

.. code-block:: scala

  new Flow(...)(...) {
    logic {req =>
      ...
      sendToLog(req)
      ...
    }
  }

*<TODO>*

===============
Getting Started
===============

*<TODO>*