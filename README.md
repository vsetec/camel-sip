# Camel SIP Component
-------------------

**This is the work in progress**

This component is intended to allow interaction using SIP protocol with user agents of any type. It should be possible to design proxy servers, redirect servers, registrars, websocket proxies and any other SIP network elements as Camel routes.

It is based on JAIN SIP, currently uses the 'gov.nist' implementation.

## Examples:

Proxy server:

    from("sip:udp://1.2.3.4:5060?requestMethod=REGISTER").to("sip:udp://5.6.7.8");
    from("sip:udp://1.2.3.4:5060?requestMethodNot=REGISTER").to("sip:proxy");

Websocket proxy server:

    from("sip:ws://1.2.3.4:6060?requestMethod=REGISTER").to("sip:udp://5.6.7.8");
    from("sip:ws://1.2.3.4:6060?requestMethodNot=REGISTER").to("sip:proxy");

These examples imply that there is a normal SIP server present in the network.

## Features

### Registrar

This component has an internal registrar, which stores any UAC that was sent an `OK` response to the `REGISTER` request, no matter if it was redirected from another registrar or originated with `respond` producer. It is used when redirecting `INVITE`s without specifying a target UAS. This should be useful for proxying websocket requests to a regular SIP server.

### Proxy

There is an internal storage of client-server transactions bindings in this component, as well as client-server dialog bindings that start from `INVITE` request. These transaction or dialog bindings are used to proxy requests and responses.

The bindings are created whenever a message is redirected to a different destination.

### JAIN SIP artifacts

The Camel message received by a consumer contains an object representing a Request or Response as a message body. Additionally it provides access to the `javax.sip.Transaction` and `javax.sip.SipProvider` objects so that the routes may inspect those and proceed accordingly.

## Syntax

### Explicit destination

We may specify an explicit destination both for waiting for incoming messages (consumer, `from`) and for emitting messages to another server (producer, `to`).

After a component scheme (in our examples, "sip:"), normally a URI should follow, starting from any of

* udp
* tcp
* ws

as scheme, then the listening or target IP address and then the port number.

The catch-all IP "0.0.0.0" may not work properly when selecting an interface to send an arbitrary request out of transaction or dialog, but this should be tested.

Full example:

    sip:udp://1.2.3.4:5060

### Proxifying messages

Message, a SIP request or response, may be transmitted to another user agent without explicitly specifying its destination. The target will be chosen using the internal registrar information or the client-server transaction or dialog bindings. 

To redirect message to the implied target, use the `proxy` word in the uri like this:

    sip:proxy

### Responding to requests

To respond in a producer, use the `respond` keyword, providing a status code of your intended response:

    sip:respond?responseCode=100

This should send a `TRYING` response back to the request's origin.

### Filtering by methods

When receiving requests, we may limit which types of requests will start the particular route, using `requestMethod` and `requestMethodNot` query parameters, like the following:

    sip:udp://1.2.3.4:5060?requestMethod=REGISTER&requestMethod=INVITE

As you can see it's okay to specify several request methods. Same goes with exclusions:

    sip:udp://1.2.3.4:5060?requestMethodNot=REGISTER&requestMethodNot=INVITE

### Filtering by status codes

When receiving responses, you may choose to process only responses with the certain status codes, like the following:

    sip:udp://1.2.3.4:5060?responseCode=200

    sip:udp://1.2.3.4:5060?responseCodeNot=100&responseCodeNot=180

# Happy testing!

Please note that this library is not ready yet.

The library's main class has the `main` method that implements a SIP Proxy as described in the example above. It takes two arguments: 

* the host IP address - it is used to listen for incoming messages on UDP port 5060 and websocket via port 6060;

* the external SIP server host and port to redirect messages to.



