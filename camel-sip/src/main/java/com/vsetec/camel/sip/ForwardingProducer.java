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

import javax.sip.ServerTransaction;
import javax.sip.address.SipURI;
import javax.sip.message.Request;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultProducer;

/**
 *
 * @author fedd
 */
class ForwardingProducer extends DefaultProducer {

    private final SipComponent _component;
    private final String _destinationHost;
    private final Integer _destinationPort;
    private final String _sendingTransport;

    public ForwardingProducer(SipComponent component, Endpoint endpoint, String destinationHost, Integer destinationPort, String transport) {
        super(endpoint);
        _component = component;
        _destinationHost = destinationHost;
        _destinationPort = destinationPort;
        _sendingTransport = transport;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        org.apache.camel.Message toSend = exchange.getIn();
        if (!(toSend instanceof Message)) {
            //TODO: convert to sipmessage somehow and send
            return;
        }
        Message message = (Message) toSend;
        // we've got message came from elsewhere, need to forward it as a proxy
        if (message.isRequest()) {
            // need to forward a request as a proxy
            Request request = message.getMessage();
            ServerTransaction serverTransaction = message.getTransaction();
            SipURI destinationUri = _component.getAddressFactory().createSipURI(null, _destinationHost);
            if (_destinationPort != null) {
                destinationUri.setPort(_destinationPort);
            }
            destinationUri.setLrParam();
            destinationUri.setTransportParam(_sendingTransport);
            _component.redirectRequestToSpecificAddress(message.getProvider(), serverTransaction, request, destinationUri);
        } else {
            // let's forward our response there
            // TODO: arbitrary response forwarding
            System.out.println("**********NO FWD OF RESPONSE*******\n\n\n");
            return;
        }
    }

}
