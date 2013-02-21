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

Then comes the structure of the flow. In this example, we first state that the amount of workers for this flow must be
ten (which means that ther might be at most, ten concurrent runs of it), and then we declare the logic that must be
executed when a message arrives. At this point, in the body of the flow we might have declared our own personal
variables that could be accessed from each flow run, though care must be taken because of the concurrent nature of the
runs.

The method logic takes a single parameter (if you have been following the lambda project of java 8, you should already
infer what it takes), which is a function that takes a Message. So we declare said function between curly braces, and
the arrow separates the arguments the function take from its body.
In the body of our logic, we are declaring a value (val, which is an immutable variable) that contains the result of
transforming the root message, and then we write a statement with it. In scala, the last expression of functions is what
they return, so here our logic is returning `response`.

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

In such case, the underscore acts as a placeholder (hence the character used), it indicates that we are declaring
a function which takes one parameter, and that we don't care about it.


Endpoint
---------

They come in five different flavors each modeling a specific message exchange pattern. They are: Source endpoints,
Responsible endpoints, Sink endpoints, Askable endpoints and Pull endpoints.

When defining a flow, you must provide it either with a Source endpoint, or a Responsible one, because flows always
need an inbound endpoint. The rest are meant to be used in the flow logic.

Endpoints are never instantiated directly, instead you access them through a EndpointFactory. This allows for an
automatic lifecyle management, as well reusage features, specially when it comes to resources.

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

Logical Endpoints
*****************

These are endpoints that do not represent a transport per se, but that add value over other kind of endpoints (thats why
they are logical).

Right now, Luthier has only a two logical endpoints, Metronome and Polling endpoint.

A Metronome endpoints takes its concept from its musician tool, because it emits a pulse at a constant rate. With this
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

*<TODO>*

Flows
-----

*<TODO>*

Flow
----

*<TODO>*

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