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

Its important to highlight by now, that the workers of the flow are the ones executing the instructions in the logic
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
defaults to 10, and the method blocking is used to submit them a task. A future object will be returned encapsulating
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

Flow Run
--------

*<TODO>*

Future
------

*<TODO>*

===============
Getting Started
===============

*<TODO>*