package uy.com.netlabs.luthier

object `package` {
  /**
   * Alias for Right so that it can be used as Extractor inside an Endpoint definition. Like:
   * {{
   * dest.ask(message) onComplete {
       case Response(response) => onEvents foreach (_.apply(response))
       case TransportError(err) => println(s"Poller ${flow.name} failed")
     }
   * }}
   */
  val Response = Right
  
  /**
   * Alias for Left so that it can be used as Extractor inside an Endpoint definition. Like:
   * {{
   * dest.ask(message) onComplete {
       case Response(response) => onEvents foreach (_.apply(response))
       case TransportError(err) => println(s"Poller ${flow.name} failed")
     }
   * }}
   */
  val TransportError = Left
}