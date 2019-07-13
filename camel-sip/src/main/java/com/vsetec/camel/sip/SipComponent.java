/*
 * Copyright 2019 fedd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.vsetec.camel.sip;

import gov.nist.javax.sip.header.Via;
import gov.nist.javax.sip.stack.NioMessageProcessorFactory;
import java.net.URISyntaxException;
import java.text.ParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TooManyListenersException;
import javax.sip.ClientTransaction;
import javax.sip.Dialog;
import javax.sip.DialogTerminatedEvent;
import javax.sip.IOExceptionEvent;
import javax.sip.InvalidArgumentException;
import javax.sip.ListeningPoint;
import javax.sip.ObjectInUseException;
import javax.sip.PeerUnavailableException;
import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.ServerTransaction;
import javax.sip.SipFactory;
import javax.sip.SipListener;
import javax.sip.SipProvider;
import javax.sip.SipStack;
import javax.sip.TimeoutEvent;
import javax.sip.Transaction;
import javax.sip.TransactionAlreadyExistsException;
import javax.sip.TransactionTerminatedEvent;
import javax.sip.TransactionUnavailableException;
import javax.sip.TransportAlreadySupportedException;
import javax.sip.TransportNotSupportedException;
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.header.HeaderFactory;
import javax.sip.header.RecordRouteHeader;
import javax.sip.header.RouteHeader;
import javax.sip.header.ViaHeader;
import javax.sip.message.Message;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;
import org.apache.camel.CamelContext;
import org.apache.camel.Consumer;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.impl.DefaultComponent;
import org.apache.camel.impl.DefaultEndpoint;
import org.apache.camel.impl.DefaultExchange;
import org.apache.camel.impl.DefaultMessage;
import org.apache.camel.impl.DefaultProducer;

/**
 *
 * @author fedd
 */
public class SipComponent extends DefaultComponent {

    private final SipFactory _sipFactory = SipFactory.getInstance();
    private final SipStack _sipStack;
    private final Map<String, SipProvider> _sipProvidersByPortAndTransport = new HashMap<>(3);
    private final Map<ServerTransaction, Set<ClientTransaction>> _serverClients = new HashMap<>();
    private final Map<ClientTransaction, ServerTransaction> _clientServer = new HashMap<>();
    private final CamelSipListener _listener = new CamelSipListener();
    private final String _ourHost;
    private final HeaderFactory _headerFactory;
    private final AddressFactory _addressFactory;
    private final MessageFactory _messageFactory;
    private final Object _security;

    public SipComponent(CamelContext camelContext, String hostIp, String implementationPackage, Map<String, Object> stackParameters, Object security) { // TODO: implement sips - change "security to the appropriate type

        super(camelContext);

        _ourHost = hostIp;
        _security = security;
        _sipFactory.setPathName(implementationPackage);

        try {
            _headerFactory = _sipFactory.createHeaderFactory();
            _addressFactory = _sipFactory.createAddressFactory();
            _messageFactory = _sipFactory.createMessageFactory();

        } catch (PeerUnavailableException e) {
            throw new RuntimeException(e);
        }

        Properties properties = new Properties();
        properties.setProperty("javax.sip.STACK_NAME", "delaSipStack");
        properties.setProperty("gov.nist.javax.sip.MESSAGE_PROCESSOR_FACTORY", NioMessageProcessorFactory.class.getName());
        if (stackParameters != null) {
            properties.putAll(stackParameters);
        }
        properties.remove("javax.sip.IP_ADDRESS");
        try {
            _sipStack = _sipFactory.createSipStack(properties);
        } catch (PeerUnavailableException e) {
            throw new RuntimeException(e);
        }

    }

    public HeaderFactory getHeaderFactory() {
        return _headerFactory;
    }

    public AddressFactory getAddressFactory() {
        return _addressFactory;
    }

    public MessageFactory getMessageFactory() {
        return _messageFactory;
    }

    @Override
    protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> endpointParams) throws Exception {

        //return new CamelSipEndpoint(uri, this, remaining, endpointParams);
        final String receivingHost;
        final String receivingTransport;
        final Integer receivingPort;
        final Integer responseCode;

        String responseCodeString = (String) endpointParams.get("responseCode");
        if (responseCodeString == null) {
            responseCode = null;
        } else {
            responseCode = Integer.parseInt(responseCodeString);
        }
        if (remaining.startsWith("proxy")) {
            receivingHost = null;
            receivingTransport = null;
            receivingPort = null;
        } else {
            try {
                java.net.URI endpointAddress = new java.net.URI(remaining);
                receivingHost = endpointAddress.getHost();
                receivingTransport = endpointAddress.getScheme();
                int destinationPortTmp = endpointAddress.getPort();
                if (destinationPortTmp > 0) {
                    receivingPort = destinationPortTmp;
                } else {
                    receivingPort = null;
                }
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }
        }

        if (receivingHost == null) {
            return new DefaultEndpoint(uri, this) {
                @Override
                public Producer createProducer() throws Exception {
                    return new CamelSipProxyProducer(this, responseCode);
                }

                @Override
                public Consumer createConsumer(Processor processor) throws Exception {
                    return null;
                }

                @Override
                public boolean isSingleton() {
                    return true;
                }

                @Override
                public boolean isLenientProperties() {
                    return true;
                }
            };
        } else {
            return new DefaultEndpoint(uri, this) {
                @Override
                public Producer createProducer() throws Exception {
                    return new CamelSipForwardingProducer(this, receivingHost, receivingPort, receivingTransport, responseCode);
                }

                @Override
                public Consumer createConsumer(Processor processor) throws Exception {
                    return new CamelSipConsumer(this, receivingHost, receivingPort, receivingTransport, responseCode, processor);
                }

                @Override
                public boolean isSingleton() {
                    return true;
                }

                @Override
                public boolean isLenientProperties() {
                    return true;
                }
            };
        }

    }

    private class CamelSipForwardingProducer extends DefaultProducer {

        private final String _destinationHost;
        private final Integer _destinationPort;
        private final String _sendingTransport;
        private final Integer _responseCode;

        public CamelSipForwardingProducer(Endpoint endpoint, String destinationHost, Integer destinationPort, String transport, Integer responseCode) {
            super(endpoint);
            _destinationHost = destinationHost;
            _destinationPort = destinationPort;
            _sendingTransport = transport;
            _responseCode = responseCode;
        }

        @Override
        public void process(Exchange exchange) throws Exception {
            org.apache.camel.Message toSend = exchange.getIn();
            if (!(toSend instanceof CamelSipMessage)) {
                //TODO: convert to sipmessage somehow and send
                return;
            }
            CamelSipMessage message = (CamelSipMessage) toSend;

            // we've got message came from elsewhere, need to forward it as a proxy
            if (message.isRequest()) {

                // need to forward a request as a proxy
                Request request = message.getMessage();
                ServerTransaction serverTransaction = message.getTransaction();

                // we may respond right here
                if (_responseCode != null) {
                    // let's respond
                    Response response = _messageFactory.createResponse(_responseCode, request);
                    System.out.println("**********SEND RESP BY CODE BEFORE FORWARD*************\n" + response.toString());
                    serverTransaction.sendResponse(response);
                }

                // actual forwarding
                // let's forward our request there
                Request newRequest = (Request) request.clone();

                // receiving = destination
                // where? add a route there
                SipURI destinationUri = _addressFactory.createSipURI(null, _destinationHost);
                if (_destinationPort != null) {
                    destinationUri.setPort(_destinationPort);
                }
                destinationUri.setLrParam();
                destinationUri.setTransportParam(_sendingTransport);
                Address destinationAddress = _addressFactory.createAddress(null, destinationUri);
                RouteHeader routeHeader = _headerFactory.createRouteHeader(destinationAddress);
                newRequest.addFirst(routeHeader);

                // where from? add a via and a record-route
                SipProvider receivingProvider = message.getProvider();
                ListeningPoint listeningPoint = receivingProvider.getListeningPoint(_sendingTransport);
                int responseListeningPort = listeningPoint.getPort();

                ViaHeader viaHeader = _headerFactory.createViaHeader(_ourHost, responseListeningPort, _sendingTransport, null);
                newRequest.addFirst(viaHeader);

                SipURI recordRouteUri = _addressFactory.createSipURI(null, _ourHost);
                Address recordRouteAddress = _addressFactory.createAddress(null, recordRouteUri);
                recordRouteUri.setPort(responseListeningPort);
                recordRouteUri.setLrParam();
                recordRouteUri.setTransportParam(_sendingTransport);
                RecordRouteHeader recordRoute = _headerFactory.createRecordRouteHeader(recordRouteAddress);
                newRequest.addHeader(recordRoute);

                // will use the transport specified in route header to send
                ClientTransaction clientTransaction = receivingProvider.getNewClientTransaction(newRequest);

                // remember the server transaction, to forward back the responses
                //clientTransaction.setApplicationData(serverTransaction);
                _clientServer.put(clientTransaction, serverTransaction);
                _serverClients.get(serverTransaction).add(clientTransaction);

                System.out.println("**********FWD REQ*************\n" + newRequest.toString());
                clientTransaction.sendRequest();

            } else {
                // let's forward our response there
                // TODO: arbitrary response forwarding
                System.out.println("**********NO FWD OF RESPONSE*******\n\n\n");
                return;

            }

        }

    }

    private class CamelSipProxyProducer extends DefaultProducer {

        private final Integer _responseCode;

        public CamelSipProxyProducer(Endpoint endpoint, Integer responseCode) {
            super(endpoint);
            _responseCode = responseCode;
        }

        @Override
        public void process(Exchange exchange) throws Exception {
            org.apache.camel.Message toSend = exchange.getIn();
            if (!(toSend instanceof CamelSipMessage)) {
                //TODO: convert to sipmessage somehow and send
                return;
            }
            CamelSipMessage message = (CamelSipMessage) toSend;

            // we've got message came from elsewhere, need to forward it as a proxy
            if (message.isRequest()) {

                // need to forward a request as a proxy
                Request request = message.getMessage();
                ServerTransaction serverTransaction = message.getTransaction();

                // we may respond right here
                if (_responseCode != null) {
                    // let's respond
                    Response response = _messageFactory.createResponse(_responseCode, request);
                    System.out.println("**********SEND RESP BY CODE BEFORE PROXYING*************\n" + response.toString());
                    serverTransaction.sendResponse(response);
                }

                // maybe it happens to be a known server transaction!
                if (request.getMethod().equals("CANCEL")) {
                    Set<ClientTransaction> clientTransactions = _serverClients.get(serverTransaction);
                    if (clientTransactions != null && !clientTransactions.isEmpty()) {
                        for (ClientTransaction clientTransaction : clientTransactions) {
                            Request cancelRequest = clientTransaction.createCancel();
                            ClientTransaction clientCancelTransaction = message.getProvider().getNewClientTransaction(cancelRequest);
                            //clientTransaction.setApplicationData(serverTransaction);
                            _clientServer.put(clientCancelTransaction, serverTransaction);
                            _serverClients.get(serverTransaction).add(clientCancelTransaction);
                            System.out.println("**********PROXYSEND CANCEL REQ*************\n" + cancelRequest.toString());
                            clientCancelTransaction.sendRequest();
                        }
                    }
                } else {
                    RouteHeader routeHeader = (RouteHeader) request.getHeader(RouteHeader.NAME);
                    SipURI routeUri = (SipURI) routeHeader.getAddress().getURI();
                    ViaHeader viaHeader = _headerFactory.createViaHeader(_ourHost, routeUri.getPort(), routeUri.getTransportParam(), null);

                    SipURI recordRouteUri = _addressFactory.createSipURI(null, _ourHost);
                    Address recordRouteAddress = _addressFactory.createAddress(null, recordRouteUri);
                    recordRouteUri.setPort(routeUri.getPort());
                    recordRouteUri.setLrParam();
                    recordRouteUri.setTransportParam(routeUri.getTransportParam());
                    RecordRouteHeader recordRoute = _headerFactory.createRecordRouteHeader(recordRouteAddress);

                    // actual forwarding 
                    Request newRequest = (Request) request.clone();
                    newRequest.addFirst(viaHeader);
                    newRequest.addHeader(recordRoute);

                    ClientTransaction clientTransaction = message.getProvider().getNewClientTransaction(newRequest);
                    //clientTransaction.setApplicationData(serverTransaction);
                    _clientServer.put(clientTransaction, serverTransaction);
                    _serverClients.get(serverTransaction).add(clientTransaction);

                    // will use the transport specified in route header to send
                    System.out.println("**********PROXYSEND REQ*************\n" + newRequest.toString());
                    clientTransaction.sendRequest();
                }
            } else {
                // it is a response to our previously forwarded request
                Response response = message.getMessage();
                Response newResponse = (Response) response.clone();
                newResponse.removeFirst(ViaHeader.NAME);

                // actual forwarding 
                // as it is a response originated here, we can get a server transaction
                ClientTransaction clientTransaction = message.getTransaction();

                if (clientTransaction == null) {
                    // send to the via address
                    SipProvider sender = message.getProvider();
                    System.out.println("**********PROXYSEND RESP NONTRAN*******\n\n\n");
                    sender.sendResponse(newResponse);
                } else {
                    //ServerTransaction serverTransaction = (ServerTransaction) clientTransaction.getApplicationData();
                    ServerTransaction serverTransaction = _clientServer.get(clientTransaction);
                    System.out.println("**********PROXYSEND RESP TRAN***********\n" + newResponse.toString());
                    serverTransaction.sendResponse(newResponse);
                }

            }

        }

    }

    private class CamelSipConsumer implements Consumer {

        private final Endpoint _endpoint;
        private final Integer _responseCode;
        private final SipProvider _sipProvider;
        private final CamelSipConsumerProcessor _wrappingProcessor;

        public CamelSipConsumer(Endpoint endpoint, String listeningHost, Integer listeningPort, String transport, Integer responseCode, Processor processor) {
            _responseCode = responseCode;
            _endpoint = endpoint;

            String key = transport + ":" + listeningPort;

            SipProvider ret = _sipProvidersByPortAndTransport.get(key);
            if (ret == null) {
                try {
                    ListeningPoint lp = _sipStack.createListeningPoint(_ourHost, listeningPort, transport);
                    
                    Iterator sps = _sipStack.getSipProviders();
                    while(sps.hasNext()){
                        SipProvider tmpSp = (SipProvider) sps.next();
                        try{
                            tmpSp.addListeningPoint(lp);
                            ret = tmpSp;
                            break;
                        }catch(TransportAlreadySupportedException e){
                            
                        }
                    }
                    if(ret==null){
                        ret = _sipStack.createSipProvider(lp);
                    }
                    _sipProvidersByPortAndTransport.put(key, ret);
                    ret.addSipListener(_listener);
                    _listener._providerProcessors.put(ret, new HashSet(4));
                } catch (InvalidArgumentException | ObjectInUseException | TransportNotSupportedException | TooManyListenersException e) {
                    throw new RuntimeException(e);
                }
            }
            _sipProvider = ret;

            if (_responseCode == null) {
                _wrappingProcessor = new CamelSipConsumerProcessor(endpoint) {
                    @Override
                    public void process(Exchange exchange) throws Exception {
                        processor.process(exchange);
                    }
                };
            } else {
                _wrappingProcessor = new CamelSipConsumerProcessor(endpoint) {
                    @Override
                    public void process(Exchange exchange) throws Exception {
                        CamelSipMessage message = (CamelSipMessage) exchange.getIn();
                        if (message.isRequest()) {
                            Response response = _messageFactory.createResponse(_responseCode, message.getMessage());
                            System.out.println("**********SEND RESP BY CODE UPON RECV*************\n" + response.toString());
                            ((ServerTransaction) message.getTransaction()).sendResponse(response);

                        }
                        processor.process(exchange);
                    }
                };
            }
        }

        @Override
        public void start() throws Exception {
            _listener._providerProcessors.get(_sipProvider).add(_wrappingProcessor);
        }

        @Override
        public void stop() throws Exception {
            _listener._providerProcessors.get(_sipProvider).remove(_wrappingProcessor);
        }

        @Override
        public Endpoint getEndpoint() {
            return _endpoint;
        }

    }

    private abstract class CamelSipConsumerProcessor implements Processor {

        private final Endpoint _endpoint;

        private CamelSipConsumerProcessor(Endpoint endpoint) {
            _endpoint = endpoint;
        }

        private void _processRequestEvent(RequestEvent event) {
            try {
                System.out.println("**********RECV REQ*************\n" + event.getRequest().toString());
                Exchange exchange = new DefaultExchange(_endpoint);
                CamelSipMessage in = new CamelSipMessage(getCamelContext(), event);
                exchange.setIn(in);
                process(exchange);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private void _processResponseEvent(ResponseEvent event) {
            try {
                System.out.println("**********RECV RESP*************\n" + event.getResponse().toString());
                Exchange exchange = new DefaultExchange(_endpoint);
                CamelSipMessage in = new CamelSipMessage(getCamelContext(), event);
                exchange.setIn(in);
                process(exchange);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public class CamelSipMessage extends DefaultMessage {

        private final SipProvider _provider;
        private final Transaction _transaction;

        private CamelSipMessage(CamelContext camelContext, RequestEvent requestEvent) {
            super(camelContext);
            Request request = requestEvent.getRequest();
            _provider = (SipProvider) requestEvent.getSource();
            ServerTransaction transaction = requestEvent.getServerTransaction();
            if (transaction == null) {
                try {
                    transaction = _provider.getNewServerTransaction(request);
                } catch (TransactionAlreadyExistsException | TransactionUnavailableException e) {
                    throw new RuntimeException(e);
                }
            }
            _transaction = transaction;
            _serverClients.put(transaction, new HashSet<>(5));

            try{
                ((ViaHeader)request.getHeader(Via.NAME)).setReceived(_ourHost);
            }catch(ParseException e){
                throw new RuntimeException(e);
            }
            
            super.setBody(request);
        }

        private CamelSipMessage(CamelContext camelContext, ResponseEvent responseEvent) {
            super(camelContext);
            _provider = (SipProvider) responseEvent.getSource();
            _transaction = responseEvent.getClientTransaction();
            super.setBody(responseEvent.getResponse());
        }

        public boolean isRequest() {
            return getBody() instanceof Request;
        }

        public boolean isResponse() {
            return getBody() instanceof Response;
        }

        public SipProvider getProvider() {
            return _provider;
        }

        public <T extends Message> T getMessage() {
            return (T) getBody();
        }

        public Dialog getDialog() {
            return _transaction.getDialog();
        }

        public <T extends Transaction> T getTransaction() {
            return (T) _transaction;
        }
    }

    private class CamelSipListener implements SipListener {

        private final Map<SipProvider, Set<CamelSipConsumerProcessor>> _providerProcessors = new HashMap<>();

        @Override
        public void processRequest(RequestEvent event) {
            SipProvider originatingProvider = (SipProvider) event.getSource();
            for (CamelSipConsumerProcessor processor : _providerProcessors.get(originatingProvider)) {
                processor._processRequestEvent(event);
            }
        }

        @Override
        public void processResponse(ResponseEvent event) {
            SipProvider originatingProvider = (SipProvider) event.getSource();
            for (CamelSipConsumerProcessor processor : _providerProcessors.get(originatingProvider)) {
                processor._processResponseEvent(event);
            }
        }

        @Override
        public void processTimeout(TimeoutEvent timeoutEvent) {

        }

        @Override
        public void processIOException(IOExceptionEvent exceptionEvent) {

        }

        @Override
        public void processTransactionTerminated(
                TransactionTerminatedEvent transactionTerminatedEvent) {
            if (transactionTerminatedEvent.isServerTransaction()) {
                ServerTransaction serverTransaction = transactionTerminatedEvent.getServerTransaction();
                Set<ClientTransaction> removed = _serverClients.remove(serverTransaction);
                _clientServer.keySet().removeAll(removed);
            } else {
                ClientTransaction clientTransaction = transactionTerminatedEvent.getClientTransaction();
                ServerTransaction serverTransaction = _clientServer.remove(clientTransaction);
                _serverClients.remove(serverTransaction);
            }
        }

        @Override
        public void processDialogTerminated(DialogTerminatedEvent dialogTerminatedEvent) {

        }

    }
}
