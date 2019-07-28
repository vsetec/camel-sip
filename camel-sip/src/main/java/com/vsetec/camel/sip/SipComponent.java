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

import java.net.URISyntaxException;
import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import javax.sip.SipException;
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
import javax.sip.header.ContactHeader;
import javax.sip.header.ExpiresHeader;
import javax.sip.header.FromHeader;
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
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
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
    private final Map<String, SipProvider> _sipProvidersByHostPortAndTransport = new HashMap<>(3);
    private final Map<ServerTransaction, Set<ClientTransaction>> _serverClients = new HashMap<>();
    private final Map<ClientTransaction, ServerTransaction> _clientServer = new HashMap<>();
    private final Map<Dialog, Dialog> _clientServerDialog = new HashMap<>();
    private final Map<Dialog, List<Dialog>> _serverClientDialogs = new HashMap<>();
    private final CamelSipListener _listener = new CamelSipListener();
    //private final String _ourHost;
    private final HeaderFactory _headerFactory;
    private final AddressFactory _addressFactory;
    private final MessageFactory _messageFactory;
    private final Object _security;

    public SipComponent(CamelContext camelContext, String implementationPackage, Map<String, Object> stackParameters, Object security) { // TODO: implement sips - change "security to the appropriate type

        super(camelContext);

        //_ourHost = hostIp;
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
        properties.setProperty("javax.sip.STACK_NAME", "delaSipStack");// + _ourHost);
        //properties.setProperty("gov.nist.javax.sip.MESSAGE_PROCESSOR_FACTORY", NioMessageProcessorFactory.class.getName());
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

    private ClientTransaction _redirectRequestToSpecificAddress(SipProvider receivingProvider, ServerTransaction serverTransaction, Request request, SipURI destinationSipUri) throws ParseException, SipException, InvalidArgumentException {
        // actual forwarding
        // let's forward our request there
        Request newRequest = (Request) request.clone();

        // where? add a route there
        Address destinationAddress = _addressFactory.createAddress(null, destinationSipUri);
        RouteHeader routeHeader = _headerFactory.createRouteHeader(destinationAddress);
        newRequest.addFirst(routeHeader);

        // where from? add a via and a record-route
        ListeningPoint listeningPoint = receivingProvider.getListeningPoint(destinationSipUri.getTransportParam());
        int responseListeningPort = listeningPoint.getPort();
        String listenedIp = listeningPoint.getIPAddress();

        ViaHeader viaHeader = _headerFactory.createViaHeader(listenedIp, responseListeningPort, destinationSipUri.getTransportParam(), null);
        newRequest.addFirst(viaHeader);

        SipURI recordRouteUri = _addressFactory.createSipURI(null, listenedIp);
        Address recordRouteAddress = _addressFactory.createAddress(null, recordRouteUri);
        recordRouteUri.setPort(responseListeningPort);
        recordRouteUri.setLrParam();
        recordRouteUri.setTransportParam(destinationSipUri.getTransportParam());
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
        return clientTransaction;
    }

    private class CamelSipRegistryItem {

        private final SipURI _registrarUri; // whom have we registered this with
        private final String _phoneOfficialAddress; // from from/to field
        private final SipURI _phoneRealAddress; // from contact field
        private final Instant _validTill; // from response Expires header
        private final String _transportToReach;

        public CamelSipRegistryItem(SipURI registrarUri, String phoneOfficialAddress, SipURI phoneRealAddress, int secondsToLive, String transport) {
            this._registrarUri = registrarUri;
            this._phoneOfficialAddress = phoneOfficialAddress;
            this._phoneRealAddress = phoneRealAddress;
            this._validTill = Instant.ofEpochMilli(System.currentTimeMillis() + secondsToLive * 1000);
            _transportToReach = transport;
        }

        @Override
        public int hashCode() {
            int hash = 3;
            hash = 37 * hash + Objects.hashCode(this._registrarUri.toString());
            hash = 37 * hash + Objects.hashCode(this._phoneOfficialAddress);
            hash = 37 * hash + Objects.hashCode(this._phoneRealAddress.toString());
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final CamelSipRegistryItem other = (CamelSipRegistryItem) obj;
            if (!Objects.equals(this._registrarUri.toString(), other._registrarUri.toString())) {
                return false;
            }
            if (!Objects.equals(this._phoneOfficialAddress, other._phoneOfficialAddress)) {
                return false;
            }
            if (!Objects.equals(this._phoneRealAddress.toString(), other._phoneRealAddress.toString())) {
                return false;
            }
            return true;
        }

    }

    @Override
    protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> endpointParams) throws Exception {

        //return new CamelSipEndpoint(uri, this, remaining, endpointParams);
        final String receivingHost;
        final String receivingTransport;
        final Integer receivingPort;

        final Set<Integer> responseCodes;
        final Set<String> requestMethods;
        final Set<Integer> responseCodesNot;
        final Set<String> requestMethodsNot;

        {
            Object reqMethod = endpointParams.get("requestMethod");
            if (reqMethod instanceof String) {
                requestMethods = Collections.singleton((String) reqMethod);
            } else if (reqMethod instanceof List) {
                requestMethods = new HashSet((List<String>) reqMethod);
            } else {
                requestMethods = null;
            }
        }
        {
            Object reqMethod = endpointParams.get("requestMethodNot");
            if (reqMethod instanceof String) {
                requestMethodsNot = Collections.singleton((String) reqMethod);
            } else if (reqMethod instanceof List) {
                requestMethodsNot = new HashSet((List<String>) reqMethod);
            } else {
                requestMethodsNot = null;
            }
        }
        {
            Object respCode = endpointParams.get("responseCode");
            if (respCode instanceof String) {
                responseCodes = Collections.singleton(Integer.parseInt((String) respCode));
            } else if (respCode instanceof List) {
                List rcList = (List) respCode;
                Integer[] rcs = new Integer[rcList.size()];
                for (int i = 0; i < rcs.length; i++) {
                    rcs[i] = Integer.parseInt((String) rcList.get(i));
                }
                responseCodes = new HashSet(Arrays.asList(rcs));
            } else {
                responseCodes = null;
            }
        }
        {
            Object respCode = endpointParams.get("responseCodeNot");
            if (respCode instanceof String) {
                responseCodesNot = Collections.singleton(Integer.parseInt((String) respCode));
            } else if (respCode instanceof List) {
                List rcList = (List) respCode;
                Integer[] rcs = new Integer[rcList.size()];
                for (int i = 0; i < rcs.length; i++) {
                    rcs[i] = Integer.parseInt((String) rcList.get(i));
                }
                responseCodesNot = new HashSet<>(Arrays.asList(rcs));
            } else {
                responseCodesNot = null;
            }
        }

        if (remaining.startsWith("proxy") || remaining.startsWith("respond")) {
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

            if (responseCodes == null || responseCodes.isEmpty()) {
                return new DefaultEndpoint(uri, this) {
                    @Override
                    public Producer createProducer() throws Exception {
                        return new CamelSipProxyProducer(this);
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
                        return new CamelSipResponder(this, responseCodes.iterator().next());
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
            }
        } else {
            return new DefaultEndpoint(uri, this) {
                @Override
                public Producer createProducer() throws Exception {
                    return new CamelSipForwardingProducer(this, receivingHost, receivingPort, receivingTransport);
                }

                @Override
                public Consumer createConsumer(Processor processor) throws Exception {
                    return new CamelSipConsumer(
                            this,
                            receivingHost,
                            receivingPort,
                            receivingTransport,
                            processor,
                            requestMethods,
                            requestMethodsNot,
                            responseCodes,
                            responseCodesNot);
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

        public CamelSipForwardingProducer(Endpoint endpoint, String destinationHost, Integer destinationPort, String transport) {
            super(endpoint);
            _destinationHost = destinationHost;
            _destinationPort = destinationPort;
            _sendingTransport = transport;
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

                SipURI destinationUri = _addressFactory.createSipURI(null, _destinationHost);
                if (_destinationPort != null) {
                    destinationUri.setPort(_destinationPort);
                }
                destinationUri.setLrParam();
                destinationUri.setTransportParam(_sendingTransport);

                _redirectRequestToSpecificAddress(message.getProvider(), serverTransaction, request, destinationUri);

            } else {
                // let's forward our response there
                // TODO: arbitrary response forwarding
                System.out.println("**********NO FWD OF RESPONSE*******\n\n\n");
                return;

            }

        }

    }

    private class CamelSipProxyProducer extends DefaultProducer {

        private final Map<String, Set<CamelSipRegistryItem>> _registeredNameRegistry = new HashMap<>(); // registered name - regitem
        private final Map<String, CamelSipRegistryItem> _selfNameRegistry = new HashMap<>(); // self proclaimed name - regitems

        public CamelSipProxyProducer(Endpoint endpoint) {
            super(endpoint);
        }

        private boolean _tryRedirectRequestToAnotherDialog(SipProvider receivingProvider, ServerTransaction serverTransaction, Request request) throws InvalidArgumentException, SipException, ParseException {
            Dialog serverDialog = serverTransaction.getDialog();
            if (serverDialog != null) {
                List<Dialog> clientDialogs = _serverClientDialogs.get(serverDialog);
                boolean sent = false;

                // ack?
                if (request.getMethod().equals(Request.ACK)) {
                    for (Dialog clientDialog : clientDialogs) {
                        Request newRequest = clientDialog.createAck(clientDialog.getLocalSeqNumber());
                        System.out.println("**********DIALOG SEND ACK***********\n" + newRequest.toString());
                        clientDialog.sendAck(newRequest);
                        sent = true;
                    }
                    return sent;

                } else { // something different. 
                    for (Dialog clientDialog : clientDialogs) {

                        _redirectRequestToSpecificAddress(receivingProvider, serverTransaction, request, (SipURI) clientDialog.getRemoteParty().getURI());
                        sent = true;

                    }
                    return sent;
                }

            }

            return false;
        }

        private boolean _tryRedirectInviteRequest(SipProvider receivingProvider, ServerTransaction serverTransaction, Request request) throws ParseException, SipException, InvalidArgumentException {
            if (request.getMethod().equals(Request.INVITE)) {

                SipURI toWhom = (SipURI) request.getRequestURI();
                // if it is a real address, redirect there
                CamelSipRegistryItem reg = _selfNameRegistry.get(toWhom.toString());
                if (reg != null) {
                    toWhom = (SipURI) toWhom.clone();
                    toWhom.setTransportParam(reg._transportToReach);
                    ClientTransaction clientTransaction = _redirectRequestToSpecificAddress(receivingProvider, serverTransaction, request, toWhom);
                    Dialog serverDialog = serverTransaction.getDialog();
                    Dialog clientDialog = clientTransaction.getDialog();
                    _bindDialogs(serverDialog, Collections.singletonList(clientDialog));
                    return true;
                }

                // no such phone registered. redirect to registrar IF it is a known phone calling
                // redirecting to it's own registrar
                ContactHeader contact = (ContactHeader) request.getHeader(ContactHeader.NAME);
                SipURI fromWhom = (SipURI) contact.getAddress().getURI();
                CamelSipRegistryItem myReg = _selfNameRegistry.get(fromWhom.toString());
                if (myReg != null) {

                    if (myReg._registrarUri != null) {
                        ClientTransaction clientTransaction = _redirectRequestToSpecificAddress(receivingProvider, serverTransaction, request, myReg._registrarUri);
                        Dialog serverDialog = serverTransaction.getDialog();
                        Dialog clientDialog = clientTransaction.getDialog();
                        _bindDialogs(serverDialog, Collections.singletonList(clientDialog));
                        return true;
                    } else {

                        // WE are the caller phone registrar! we have to look in our own records
                        Set<CamelSipRegistryItem> regs = _registeredNameRegistry.get(toWhom.toString());
                        Dialog serverDialog = serverTransaction.getDialog();
                        List<Dialog> clientDialogs = new ArrayList<>(3);
                        for (CamelSipRegistryItem reg1 : regs) {

                            if (reg1._registrarUri == null && reg1._validTill.isAfter(Instant.now())) {

                                ClientTransaction clientTransaction = _redirectRequestToSpecificAddress(receivingProvider, serverTransaction, request, reg1._phoneRealAddress);
                                Dialog clientDialog = clientTransaction.getDialog();
                                clientDialogs.add(clientDialog);

                            }

                        }

                        if (clientDialogs.isEmpty()) {
                            return false;
                        }

                        _bindDialogs(serverDialog, clientDialogs);
                        return true;

                    }
                }

            }

            return false;

        }

        private boolean _tryRedirectResponseToInitialSender(ClientTransaction clientTransaction, Response response) throws SipException, InvalidArgumentException {
            // as it is a response originated here, we can get a server transaction
            ServerTransaction serverTransaction = _clientServer.get(clientTransaction);
            if (serverTransaction == null) {
                return false;
            }

            Response newResponse = (Response) response.clone();
            newResponse.removeFirst(ViaHeader.NAME);

            // actual forwarding 
            // what if it is a register?
            _register(serverTransaction, newResponse, clientTransaction);
            System.out.println("**********PROXY SEND RESP***********\n" + newResponse.toString());
            serverTransaction.sendResponse(newResponse);
            return true;
        }

        private boolean _register(ServerTransaction serverTransaction, Response response, ClientTransaction clientTransaction) {
            // I am a server. I received a request and have to send a response
            Request requestWeveReceived = serverTransaction.getRequest();

            if (response.getStatusCode() == 200) { //we are okaying some request
                if (requestWeveReceived.getMethod().equals(Request.REGISTER)) { // and it is a register request!

                    // remember this phone for future proxying
                    String registeredName = ((FromHeader) requestWeveReceived.getHeader(FromHeader.NAME)).getAddress().getURI().toString();
                    SipURI selfProclaimedName = (SipURI) ((ContactHeader) requestWeveReceived.getHeader(ContactHeader.NAME)).getAddress().getURI();
                    //int expires = ((ContactHeader) requestWeveReceived.getHeader(ContactHeader.NAME)).getExpires();
                    int expires = ((ExpiresHeader) requestWeveReceived.getHeader(ExpiresHeader.NAME)).getExpires();

                    SipURI registrarAddress;

                    // was it a proxified register request? or we processed it ourselves
                    // in other words, is it a response we have created, or the one we're proxying
                    if (clientTransaction != null) {
                        // we are proxying. there is an actual registrar out there
                        Request registerRequestWeveSent = clientTransaction.getRequest();
                        // get the registrar address from the topmost Route header that we created
                        RouteHeader route = (RouteHeader) registerRequestWeveSent.getHeader(RouteHeader.NAME);
                        registrarAddress = (SipURI) route.getAddress().getURI();

                    } else {
                        // we aren't proxying. we decided to register ourselves
                        registrarAddress = null;
                    }

                    Set<CamelSipRegistryItem> items;
                    synchronized (_registeredNameRegistry) {
                        items = _registeredNameRegistry.get(registeredName);
                        if (items == null) {
                            items = new HashSet<>(4);
                        }
                    }

                    String transport = ((ViaHeader) response.getHeader(ViaHeader.NAME)).getTransport();

                    CamelSipRegistryItem item = new CamelSipRegistryItem(registrarAddress, registeredName, selfProclaimedName, expires, transport);
                    synchronized (items) {
                        System.out.println("**********REGISTER***********\nregistrar: " + (registrarAddress == null ? "<this server>" : registrarAddress)
                                + "\nregisteredName: " + registeredName
                                + "\nunder name of: " + selfProclaimedName.toString()
                                + "\nexpiring in sec: " + expires + "\n\n");
                        items.add(item);
                    }
                    _selfNameRegistry.put(selfProclaimedName.toString(), item);

                    return true;
                }
            }
            return false;
        }

        private void _bindDialogs(Dialog serverDialog, List<Dialog> clientDialogs) {

            for (Dialog clientDialog : clientDialogs) {
                _clientServerDialog.put(clientDialog, serverDialog);
            }

            List<Dialog> existingClientDialogs = _serverClientDialogs.get(serverDialog);
            if (existingClientDialogs == null) {
                existingClientDialogs = new ArrayList<>(clientDialogs);
                _serverClientDialogs.put(serverDialog, existingClientDialogs);
            } else {
                existingClientDialogs.addAll(clientDialogs);
            }

        }

        @Override
        public void process(Exchange exchange) throws Exception {
            org.apache.camel.Message toSend = exchange.getIn();
            if (!(toSend instanceof CamelSipMessage)) {
                //TODO: convert to sipmessage somehow and still send
                return;
            }
            CamelSipMessage message = (CamelSipMessage) toSend;

            // we've got message came from elsewhere, need to forward it as a proxy
            if (message.isRequest()) {

                // need to forward a request as a proxy
                Request request = message.getMessage();
                ServerTransaction serverTransaction = message.getTransaction();

                // maybe it happens to be a known server transaction! and we're cancelling it
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
                    } else {
                        System.out.println("**********NOTHINT TO CANCEL*************\n\n\n");
                    }
                } else {

                    SipProvider receivingProvider = message.getProvider();

                    boolean redirected = _tryRedirectInviteRequest(receivingProvider, serverTransaction, request);

                    if (!redirected) {
                        redirected = _tryRedirectRequestToAnotherDialog(receivingProvider, serverTransaction, request);
                    }

                    if (!redirected) {
                        // try to send along the route specified in request
                        RouteHeader routeHeader = (RouteHeader) request.getHeader(RouteHeader.NAME);
                        if (routeHeader == null) {
                            System.out.println("**********NO PROXYSEND REQ NO ROUTE*************\n\n\n");
                        } else {
                            Address destinationAddress = routeHeader.getAddress();
                            SipURI routeUri = (SipURI) destinationAddress.getURI();

                            String listenedIp = receivingProvider.getListeningPoint(routeUri.getTransportParam()).getIPAddress();

                            ViaHeader viaHeader = _headerFactory.createViaHeader(listenedIp, routeUri.getPort(), routeUri.getTransportParam(), null);

                            SipURI recordRouteUri = _addressFactory.createSipURI(null, listenedIp);
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
                            _clientServer.put(clientTransaction, serverTransaction);
                            _serverClients.get(serverTransaction).add(clientTransaction);

                            // will use the transport specified in route header to send
                            System.out.println("**********PROXYSEND REQ*************\n" + newRequest.toString());
                            clientTransaction.sendRequest();
                        }
                    }
                }
            } else {
                // it is a response to our previously forwarded request
                Response response = message.getMessage();
                ClientTransaction clientTransaction = message.getTransaction();

                boolean redirected = _tryRedirectResponseToInitialSender(clientTransaction, response);

                if (!redirected) {

                    Response newResponse = (Response) response.clone();
                    newResponse.removeFirst(ViaHeader.NAME);

                    // actual forwarding 
                    // send to the topmost via address
                    SipProvider sender = message.getProvider();
                    System.out.println("**********PROXYSEND RESP NONTRAN*******\n\n\n");
                    sender.sendResponse(newResponse);

                }

            }

        }

    }

    private class CamelSipResponder extends DefaultProducer {

        private final Integer _responseCode;

        public CamelSipResponder(Endpoint endpoint, Integer responseCode) {
            super(endpoint);
            _responseCode = responseCode;
        }

        @Override
        public void process(Exchange exchange) throws Exception {
            org.apache.camel.Message toSend = exchange.getIn();
            if (!(toSend instanceof CamelSipMessage)) {
                //TODO: convert to sipmessage somehow and still respond
                return;
            }
            CamelSipMessage message = (CamelSipMessage) toSend;
            if (message.isRequest()) {
                Request request = message.getMessage();
                ServerTransaction serverTransaction = message.getTransaction();

                // we may respond right here
                if (_responseCode != null) {
                    // let's respond
                    Response response = _messageFactory.createResponse(_responseCode, request);
                    System.out.println("**********SEND RESP BY CODE*************\n" + response.toString());
                    serverTransaction.sendResponse(response);
                }
            }
        }
    }

    private class CamelSipConsumer implements Consumer {

        private final Endpoint _endpoint;
        private final SipProvider _sipProvider;
        private final CamelSipConsumerProcessor _wrappingProcessor;

        public CamelSipConsumer(
                Endpoint endpoint,
                String listeningHost,
                Integer listeningPort,
                String transport,
                Processor processor,
                Set<String> requestMethods,
                Set<String> requestMethodsNot,
                Set<Integer> responseCodes,
                Set<Integer> responseCodesNot) {
            _endpoint = endpoint;

            String key = transport + ":" + listeningHost + ":" + listeningPort;

            SipProvider ret = _sipProvidersByHostPortAndTransport.get(key);
            if (ret == null) {
                try {
                    ListeningPoint lp = _sipStack.createListeningPoint(listeningHost, listeningPort, transport);

                    Iterator sps = _sipStack.getSipProviders();
                    while (sps.hasNext()) {
                        SipProvider tmpSp = (SipProvider) sps.next();
                        try {
                            tmpSp.addListeningPoint(lp);
                            ret = tmpSp;
                            break;
                        } catch (TransportAlreadySupportedException e) {

                        }
                    }
                    if (ret == null) {
                        ret = _sipStack.createSipProvider(lp);
                    }
                    _sipProvidersByHostPortAndTransport.put(key, ret);
                    ret.addSipListener(_listener);
                    _listener._providerProcessors.put(ret, new HashSet(4));
                } catch (InvalidArgumentException | ObjectInUseException | TransportNotSupportedException | TooManyListenersException e) {
                    throw new RuntimeException(e);
                }
            }
            _sipProvider = ret;

            _wrappingProcessor = new CamelSipConsumerProcessor(endpoint, requestMethods, requestMethodsNot, responseCodes, responseCodesNot) {
                @Override
                public void process(Exchange exchange) throws Exception {
                    processor.process(exchange);
                }
            };
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
        private final Set<String> _requestMethods;
        private final Set<String> _requestMethodsNot;
        private final Set<Integer> _responseCodes;
        private final Set<Integer> _responseCodesNot;

        private CamelSipConsumerProcessor(Endpoint endpoint, Set<String> requestMethods, Set<String> requestMethodsNot, Set<Integer> responseCodes, Set<Integer> responseCodesNot) {
            _endpoint = endpoint;
            _requestMethods = requestMethods;
            _requestMethodsNot = requestMethodsNot;
            _responseCodes = responseCodes;
            _responseCodesNot = responseCodesNot;
        }

        private void _processRequestEvent(RequestEvent event) {
            if (_requestMethods == null || _requestMethods.contains(event.getRequest().getMethod())) {
                if (_requestMethodsNot == null || !_requestMethodsNot.contains(event.getRequest().getMethod())) {
                    if (_responseCodes == null) {
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
                }
            }
        }

        private void _processResponseEvent(ResponseEvent event) {
            if (_responseCodes == null || _responseCodes.contains(event.getResponse().getStatusCode())) {
                if (_responseCodesNot == null || !_responseCodesNot.contains(event.getResponse().getStatusCode())) {
                    if (_requestMethods == null) {
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
            Dialog dialog = dialogTerminatedEvent.getDialog();
            if (dialog.isServer()) {
                List<Dialog> removed = _serverClientDialogs.remove(dialog);
                _clientServerDialog.keySet().removeAll(removed);
            } else {
                Dialog removed = _clientServerDialog.remove(dialog);
                _serverClientDialogs.remove(removed);
            }
        }

    }

    /**
     *
     * @param args host sipserver:port
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {

        String ourHostIp = args[0];
        String sipServer = args[1];

        CamelContext camelContext = new DefaultCamelContext();
        Map<String, Object> props = Collections.singletonMap("gov.nist.javax.sip.MESSAGE_PROCESSOR_FACTORY", "gov.nist.javax.sip.stack.NioMessageProcessorFactory");
        camelContext.addComponent("sip", new SipComponent(camelContext, "gov.nist", props, null));

        camelContext.addRoutes(new RouteBuilder(camelContext) {
            @Override
            public void configure() throws Exception {

                from("sip:udp://" + ourHostIp + ":5060?requestMethod=REGISTER").to("sip:udp://" + sipServer);
                from("sip:udp://" + ourHostIp + ":5060?requestMethodNot=REGISTER").to("sip:proxy");

                from("sip:ws://" + ourHostIp + ":6060?requestMethod=REGISTER").to("sip:udp://" + sipServer);
                from("sip:ws://" + ourHostIp + ":6060?requestMethodNot=REGISTER").to("sip:proxy");

//                from("sip:udp://0.0.0.0:5060").to("direct:dispatcher");
//
//                from("sip:ws://0.0.0.0:6060").to("direct:dispatcher");
//
//                from("direct:dispatcher").choice()
//                        .when().mvel("exchange.in.isRequest() && exchange.in.body.method=='REGISTER'").to("sip:udp://" + sipServer).endChoice()
//                        .when().mvel("exchange.in.isRequest() && exchange.in.body.method=='INVITE'").to("sip:respond?code=100").to("sip:udp://" + sipServer).endChoice()
//                        .when().mvel("exchange.in.isResponse() && exchange.in.body.statusCode==100").to("stub:nowhere").endChoice()
//                        .otherwise().to("sip:proxy");
            }
        });
        camelContext.start();
    }
}
