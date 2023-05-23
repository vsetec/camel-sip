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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.sip.ClientTransaction;
import javax.sip.InvalidArgumentException;
import javax.sip.ListeningPoint;
import javax.sip.PeerUnavailableException;
import javax.sip.ServerTransaction;
import javax.sip.SipException;
import javax.sip.SipFactory;
import javax.sip.SipProvider;
import javax.sip.SipStack;
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.header.HeaderFactory;
import javax.sip.header.RecordRouteHeader;
import javax.sip.header.RouteHeader;
import javax.sip.header.ViaHeader;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import org.apache.camel.CamelConfiguration;
import org.apache.camel.CamelContext;
import org.apache.camel.Endpoint;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.main.Main;
import org.apache.camel.support.DefaultComponent;
import org.apache.camel.support.DefaultEndpoint;
import org.apache.camel.support.jsse.SSLContextParameters;

/**
 *
 * @author fedd
 */
public class SipComponent extends DefaultComponent {

    private final SipFactory _sipFactory = SipFactory.getInstance();
    private final SipStack _sipStack;
    private final Registrar _registrar;
    private final HeaderFactory _headerFactory;
    private final AddressFactory _addressFactory;
    private final MessageFactory _messageFactory;
    private final SSLContextParameters _security;

    public SipComponent(String implementationPackage, Map<String, Object> stackParameters, SSLContextParameters security) { // TODO: implement sips - change "security to the appropriate type

        super();

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

        _registrar = new Registrar(_sipStack);
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

    public Registrar getRegistrar() {
        return _registrar;
    }

    protected ClientTransaction redirectRequestToSpecificAddress(SipProvider receivingProvider, ServerTransaction serverTransaction, Request request, SipURI destinationSipUri) throws ParseException, SipException, InvalidArgumentException {
        // actual forwarding
        // let's forward our request there
        Request newRequest = (Request) request.clone();

        // where? add a route there
        Address destinationAddress = _addressFactory.createAddress(null, destinationSipUri);
        RouteHeader routeHeader = _headerFactory.createRouteHeader(destinationAddress);
        newRequest.addFirst(routeHeader);

        // where from? add a via and a record-route
        SipProvider sendingProvider;
        if (receivingProvider.getListeningPoint(destinationSipUri.getTransportParam()) == null) {
            sendingProvider = _registrar.getProvider(destinationSipUri.getTransportParam());
        } else {
            sendingProvider = receivingProvider;
        }

        ListeningPoint listeningPoint = sendingProvider.getListeningPoint(destinationSipUri.getTransportParam());
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
        ClientTransaction clientTransaction = sendingProvider.getNewClientTransaction(newRequest);

        // remember the server transaction, to forward back the responses
        _registrar.bindTransactions(serverTransaction, clientTransaction);

        System.out.println("**********FWD REQ*************\n" + newRequest.toString());
        clientTransaction.sendRequest();
        return clientTransaction;
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
                        return new ProxyProducer(this, com.vsetec.camel.sip.SipComponent.this);
                    }

                    @Override
                    public org.apache.camel.Consumer createConsumer(Processor processor) throws Exception {
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
                        return new Responder(this, responseCodes.iterator().next(), SipComponent.this);
                    }

                    @Override
                    public org.apache.camel.Consumer createConsumer(Processor processor) throws Exception {
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
                    return new ForwardingProducer(SipComponent.this, this, receivingHost, receivingPort, receivingTransport);
                }

                @Override
                public org.apache.camel.Consumer createConsumer(Processor processor) throws Exception {
                    return new Consumer(
                            _registrar,
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

    /**
     *
     * @param args host sipserver:port
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {

        String ourHostIp = args[0];
        String sipServer = args[1];

        Main main = new Main();

        Map<String, Object> props = Collections.singletonMap("gov.nist.javax.sip.MESSAGE_PROCESSOR_FACTORY", "gov.nist.javax.sip.stack.NioMessageProcessorFactory");

        main.configure().addConfiguration(new CamelConfiguration() {
            @Override
            public void configure(CamelContext camelContext) throws Exception {
                camelContext.addComponent("sip", new SipComponent("gov.nist", props, null));

                camelContext.addRoutes(new RouteBuilder() {
                    @Override
                    public void configure() throws Exception {

                        from("sip:udp://" + ourHostIp + ":5060?requestMethod=REGISTER").to("sip:udp://" + sipServer);
                        from("sip:udp://" + ourHostIp + ":5060?requestMethodNot=REGISTER").to("sip:proxy");

                        from("sip:ws://" + ourHostIp + ":6060?requestMethod=REGISTER").to("sip:udp://" + sipServer);
                        from("sip:ws://" + ourHostIp + ":6060?requestMethodNot=REGISTER").to("sip:proxy");

                        from("sip:ws://" + ourHostIp + ":7060?requestMethod=REGISTER").to("sip:respond?responseCode=200");
                        from("sip:ws://" + ourHostIp + ":7060?requestMethod=INVITE").to("sip:respond?responseCode=100").to("sip:proxy");
                        from("sip:ws://" + ourHostIp + ":7060?requestMethodNot=REGISTER&requestMethodNot=INVITE").to("sip:proxy");

                    }
                });
            }
        });

        main.run();
    }
}
