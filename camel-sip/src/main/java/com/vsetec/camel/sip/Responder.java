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
import javax.sip.message.Request;
import javax.sip.message.Response;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultProducer;

/**
 *
 * @author fedd
 */
class Responder extends DefaultProducer {

    private final Integer _responseCode;
    private final SipComponent _component;

    public Responder(Endpoint endpoint, Integer responseCode, SipComponent component) {
        super(endpoint);
        _responseCode = responseCode;
        _component = component;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        org.apache.camel.Message toSend = exchange.getIn();
        if (!(toSend instanceof Message)) {
            //TODO: convert to sipmessage somehow and still respond
            return;
        }
        Message message = (Message) toSend;
        if (message.isRequest()) {
            Request request = message.getMessage();
            ServerTransaction serverTransaction = message.getTransaction();
            // we may respond right here
            if (_responseCode != null) {
                // let's respond
                Response response = _component.getMessageFactory().createResponse(_responseCode, request);
                System.out.println("**********SEND RESP BY CODE*************\n" + response.toString());
                serverTransaction.sendResponse(response);
                _component.getRegistrar().tryRegister(serverTransaction, response);
            }
        }
    }

}
