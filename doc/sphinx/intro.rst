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
is what the flow should return (in case it is a request-response flow), so here our logic is returning ``response``.

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

A common pattern when ignoring the previousContent is giving the variable the name ``_`` like:

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
declare a very generic payload type, or the most generic one being ``Any`` (which as its name states, it can be anything).
In such cases there are several tools you can use to work with the specific payload.
The first tool is the the ``as`` operator of messages. Suppose you are working with JMS, and you know that through that
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
Note how in the last ``case`` statement we do not declare the type of other, this acts as a wildcard, so we can handle
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

In that example, we forgot to return ``"someMessage"`` inside a message object via mapping on the root message, hence
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
we provide a bunch of workers per flow for this exclusive purpose. ``blockingWorkers`` define the amount of workers, which
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
a ``blockingOpResult``, we then create a message with that ``blockingOpResult``, and that last statement is what blocking
will return, eventually. Outside of the blocking call, we assign its result in a ``result`` value, and we define that
our flow returns that.
In the example, ``<blockingOpResultType>`` represents the type of the ``someBlockingOperation`` call, that we later return
in our message.

Future
------

This is another key concept for Luthier. Since most of the operations happen asynchronously, we need a safe, composable
way to express that this operations might fail, or take some time, and that we might want to do stuff with their results
once they become available.
All of that is reprsented by Future. Its full type is Future[T] where T represents the result type of the operation (for
operations that don't return anything, T is the special type Unit, which would be the java equivalente for Void, though
not quite, because in java, when you declare a method to return Void, you still need to issue a ``return null;`` as last
statement, and this isn't the case with Scala's Unit).
A future encpasulates some code that will eventually complete or fail, so there is no way to actually obtain whatever
it represents. There is no ``get`` operation, instead, you are supposed to compose its result with new logic. In order to
do this, it provides the following operations:

.. code-block:: scala

  value: Option[Try[T]]

      //The value of this Future.
      //
      //If the future is not completed the returned value will be None.
      //If the future is completed the value will be Some(Success(t)) if
      //it contains a valid result, or Some(Failure(error)) if it contains an exception.

  onComplete[U](func: (Try[T]) ⇒ U): Unit
      // When this future is completed, either through an exception, or a value, apply
      // the provided function.
      // If the future has already been completed, this will either be applied immediately
      // or be scheduled asynchronously.
      // Multiple callbacks may be registered; there is no guarantee that they will be
      // executed in a particular order.
  map[S](f: (T) ⇒ S): Future[S]
      // Creates a new future by applying a function to the successful result of this future.
      // If this future is completed with an exception then the new future will also contain
      // this exception.
  mapTo[S]: Future[S]
      // Creates a new Future[S] which is completed with this Future's result if that
      // conforms to S's erased type or a ClassCastException otherwise.
  onFailure[U](callback: PartialFunction[Throwable, U]): Unit
      // When this future is completed with a failure (i.e. with a throwable), apply the provided
      // callback to the throwable.
      // The future may contain a throwable object and this means that the future failed.
      // Futures obtained through combinators have the same exception as the future they were obtained from.
      // If the future has already been completed with a failure, this will either be
      // applied immediately or be scheduled asynchronously.
      // Will not be called in case that the future is completed with a value.
      // Multiple callbacks may be registered; there is no guarantee that they will be
      // executed in a particular order.
  onSuccess[U](pf: PartialFunction[T, U]): Unit
      // When this future is completed successfully (i.e. with a value), apply the provided partial
      // function to the value if the partial function is defined at that value.
      // If the future has already been completed with a value, this will either be applied
      // immediately or be scheduled asynchronously.
      // Multiple callbacks may be registered; there is no guarantee that they will be
      // executed in a particular order.
  recover[U >: T](pf: PartialFunction[Throwable, U]): Future[U]
      // Creates a new future that will handle any matching throwable that this future might
      // contain. If there is no match, or if this future contains a valid result then
      // the new future will contain the same.
      //
      // Example:
      //
      // future (6 / 0) recover { case e: ArithmeticException => 0 } // result: 0
      // future (6 / 0) recover { case e: NotFoundException   => 0 } // result: exception
      // future (6 / 2) recover { case e: ArithmeticException => 0 } // result: 3
  recoverWith[U >: T](pf: PartialFunction[Throwable, Future[U]]): Future[U]
      // Creates a new future that will handle any matching throwable that this future might
      // contain by assigning it a value of another future.
      // If there is no match, or if this future contains a valid result then the new future
      // will contain the same result.
      //
      // Example:
      //
      // val f = future { Int.MaxValue }
      // future (6 / 0) recoverWith { case e: ArithmeticException => f } // result: Int.MaxValue
  transform[S](s: (T) ⇒ S, f: (Throwable) ⇒ Throwable): Future[S]
      // Creates a new future by applying the 's' function to the successful result of this future,
      // or the 'f' function to the failed result. If there is any non-fatal exception thrown
      // when 's' or 'f' is applied, that exception will be propagated to the resulting future.
  zip[U](that: Future[U]): Future[(T, U)]
      // Zips the values of this and that future, and creates a new future holding the tuple
      // of their results.
      // If this future fails, the resulting future is failed with the throwable stored in this.
      // Otherwise, if that future fails, the resulting future is failed with the throwable
      // stored in that.

For a complete list on the methods, check `here <http://www.scala-lang.org/api/current/index.html#scala.concurrent.Future>`_.

The methods shown are the most common ones used with Futures, though there are some that you will use so much, that they
deserve some attention of their own.

Lets start with the most common one: map. Since futures encapsulate computations that will eventually yield results,
more often than note you will want to do something with that result once it is avilable. Think for example on the
following flow logic: upon an item price request, you need to consult a webservice that provides all the information
available for that item, now since you only need the price, you need to transform the response form the webservice
into the information that you required. Let's do just that in a flow:

.. code-block:: scala

  new Flow("obtain-item-price")(SomeInboundEndpoint) {
    logic {rootMessage: Message[String] =>
      //our root message contains a string with the item id
      val wsResponse: Future[Message[ItemData]] =
        WebService(...).ask(rootMessage) //the webservice takes a string with the item id,
                                         //since that is our request, we just send it over

      //the flow definition now needs a response of a price, so we need to adapt the ItemData
      val res: Future[Message[Double]] = wsResponse.map {itemDataMessage: Message[ItemData] =>
        itemDataMessage.map(itemData => itemData.price)
      }
      res
    }
  }

.. HINT::

  Notice in the statement ``itemDataMessage.map(itemData => itemData.price)`` that we are using parenthesis instead
  of curly braces, when we define statement blocks of just one line, like this map instance, we can use parenthesis.

First we declare the flow with some endpoint that represents our request-response logic.
In the logic definition, we sent the message as is to the webservice call and we get a future back, representing
the eventual response. Now, the response (when it becomes available) will be of type ItemData, and our flow
is supposed to return just the price (for this example, we chose Double to represent it) so we must adapt the webservice
response by mapping over the ``wsResponse`` future. Writing this ``map`` is the same as when we mapped over messages: we
first declare the content that will be mapped, which is the value that is encapsulated by the future, then the arrow,
then the statements that represent the mapping -- remember that the last statement is the one returned --.
So in our example, we are mapping the item data that is sent to us from the webservice, by just using its field price.
Note that mapping a future returns, again, a future. This is what propagates our logic efficiently without blocking, we
now have a future of the correct type, and it wont block at all, when the response arrives, the mapping will be applied
and the future ``res`` will be completed.
Now, remember that flows accepts as responses either a message of the expected type, or a future of a message of the
expected type, here we are providing the later, hence completing the flow definition.

You might be thinking, the previous flow obtained its data from just one place, so mapping works, but what happens
when you need to retrieve data from one place, then use that value to obtain data from another palce (possibly even
do this n-times), and then respond? or what happens when you need to retrieve data from several places at the same time
and then aggregate their results later? Lets study those two cases:

Case 1: concatenating futures
*****************************

Lets start by doing the same thing we did with map before and see what happens. Suppose that our request
has an item name, which we must consult to a webservice to obtain the item ID, and then we are ready to ask for its
data (the webservice endpoint syntax will be a pseudo syntax):

.. code-block:: scala

  new Flow("obtain-item-price")(SomeInboundEndpoint) {
    logic {rootMessage: Message[String] =>
      //our root message contains a string with the item name

      val resolveNameFuture: Future[Message[String]] =
        WebService("resolveNameOp", ...).ask(rootMessage)

      val itemDataFuture: Future[Future[Message[ItemData]]] =
        resloveNameFuture.map {name: Message[String] =>
          WebService("getItemData", ...).ask(name)
        }
     //what? nested futures?
    }
  }

As you can see in the example above, when we mapped resolveNameFuture by using its message to ask for the item data,
we are essentially mapping a Message[String] to a Future[Message[ItemData]], so the resulting value from mapping is a
future of a future of a message of the item data!
The way to alleviate this issue is by using ``flatMap`` instead of ``map``. If you check the definition we included
above, you will see that flatMap is like map, but it *flattens* (that's why its called ``flatMap``) one layer of Futures.
So our flow would look like:

.. code-block:: scala

  new Flow("obtain-item-price")(SomeInboundEndpoint) {
    logic {rootMessage: Message[String] =>
      //our root message contains a string with the item name

      val resolveNameFuture: Future[Message[String]] =
        WebService("resolveNameOp", ...).ask(rootMessage)

      val itemDataFuture: Future[Message[ItemData]] =
        resolveNameFuture.flatMap {name: Message[String] =>
          WebService("getItemData", ...).ask(name)
        }

      val res: Future[Message[Double]] =
        itemDataFuture.map {itemDataMessage: Message[ItemData] =>
          itemDataMessage.map(itemData: ItemData => itemData.price)
        }
      res //res has now the correct type
    }
  }

.. HINT::

  ``map`` and ``flatMap`` are no new concepts, in fact, they are like basic blocks of what is known as functional
  programming, although their name varies in the literature. This concepts are quite general and they encompass
  any generic container. For example, our type Message, is just a container of a payload T, hence it is able to map
  and flatMap. Collection classes, the class Option, Try, Either and many others also are containers which satisfy
  this concept.
  In fact, it's so common for a container to be able to provide this functionality, that scala added a special syntax
  to use them, which in certain circumstances becomes much more readable. The rule is something like this for some
  futures of Ints:

  .. code-block:: scala

    val res: Future[Int] = for {
      a <- futureA
      b <- futureB
      c <- futureC
    } yield a + b + c

    //translates to
    val res2: Future[Int] =
      futureA.flatMap {a =>
        futureB.flatMap {b =>
          futureC.map {c => a + b + c}
        }
      }

  It translates each ``<-`` arrow (called generator) to a flatmaps except for the last one, which is just a map.
  This syntax, which is called for-comprehension, also supports filtering which looks like:

  .. code-block:: scala

    val res: Future[Int] = for {
      a <- futureA
      if a > 10
      b <- futureB
      c <- futureC
      if b > c
    } yield a + b + c

  The translation for this is a little more involved and non important, but if you still want to know, check
  Scala's documentation `here <http://docs.scala-lang.org/overviews/core/futures.html#functional_composition_and_forcomprehensions>`_.

  As a final example, lets see how does our example looks like using this syntax:

    .. code-block:: scala

      new Flow("obtain-item-price")(SomeInboundEndpoint) {
        logic {rootMessage: Message[String] =>
          //our root message contains a string with the item name

          val res: Future[Message[Double]] = for {
            resolveNameMsg: Message[String] <-
                           WebService("resolveNameOp", ...).ask(rootMessage)
            itemDataMsg: Message[ItemData] <-
                           WebService("getItemData", ...).ask(resolveNameMsg)
          } yield itemDataMsg.map(itemData: ItemData => itemData.price)
          res
        }
      }

Case 2: aggregating futures
***************************

In this case we want to create several futures in parallel, which represent requests to different providers, and
then aggregate their results to return the cheapest price. For this example, we will assume we have a list of askable
endpoints, that will all respond a message of Double, when we request the price of an item. The flow looks like:

  .. code-block:: scala

    new Flow("obtain-item-price")(SomeInboundEndpoint) {
      val priceProviders = Seq(Endpoint1, Endpoitn2, Endpoint3...)
      logic {rootMessage: Message[String] =>
        //for each provider, we ask them the price
        val priceAnswersFutures: Seq[Future[Message[Double]]] =
          for (provider <- priceProviders) yield provider.ask(rootMessage)

        //to aggregate the result of all of those futures, we use a generic
        //tool defined on the Future object per se
        val priceAnwsersFuture: Future[Seq[Message[Double]]] =
                            Future.sequence(priceAnswersFutures)

        val minPrice: Future[Message[Double]]
        priceAnswersFuture.map(prices: Seq[Message[Double]] =>
          prices.minBy(msg: Message[Double] => msg.payload.price)
        )
        //return the minimum price
        minPrice
      }
    }

We started by sending each provider the same request, and obtained a different future for each of their answers.

.. NOTE::

  Note how we could've used map, instead of the for-yield syntax here.

Since we need to operate once all of those futures are completed, one might be tempted to block, but remember
that we never want to do that. Instead, we used a general function defined in the Future object (as opposed to eeach
instance) called sequence. Notice how after applying this function Seq and Future swapped places when comparing the
variables ``priceAnswersFutures`` and ``priceAnwsersFuture``. We moved from a sequence of futures of messages, to a
single future, that once it completes, will contain a sequence of messages.

.. HINT::

  Again, as with map and flatMap, sequence is another general concept of functional programming, and it is applicable
  to most generic containers, so you will find it on collections classes and other containers of the sort.
  It basically states that sequence over Seq[Container[A]] for any A yields Container[Seq[A]].

We finally used a useful operation over the sequence, that finds for us the minimum element if we provide it with a
function that tells it how to compare its elements, in this case, by telling it to use the price field of the payload
which is a number.


Blocking
********

For the unavoidable situation where you **have** to wait for the result, you can do so with Await.
This tool takes a future and a timeout (always, non optional) and allows you to await for the result, in the case
where the future ends with failure, it will rethrow the exception that caused it, otherwise it will return the result.
Use like this:

.. code-block:: scala

  import scala.concurrent.Await       //import the Await object
  import scala.concurrent.duration._  //provides the timeout syntax

  ...

  val someFuture: Future[Result] = ...
  val r: Result = Await.result(someFuture, 10.seconds)

  //or if you just want to know when its done
  Await.ready(someFuture, 10.seconds)

Remember that in the flows, when you use endpoints, they will return future instances representing their actions, it's
highly discouraged that you block on they flow's actor (you even get a warning message in runtime if you do). Instead
always try to compose your futures. Remember that request-response flows accept Futures for results.

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

===============
Getting Started
===============

*<TODO>*