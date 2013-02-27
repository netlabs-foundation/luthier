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
