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

import javax.sip.ClientTransaction;
import javax.sip.ServerTransaction;
import javax.sip.SipProvider;
import javax.sip.message.Request;
import javax.sip.message.Response;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.support.DefaultExchange;

/**
 *
 * @author fedd
 */
abstract class ConsumerProcessor implements Processor {

    private final Endpoint _endpoint;

    public ConsumerProcessor(Endpoint endpoint) {
        _endpoint = endpoint;
    }

    protected void _processRequestEvent(SipProvider provider, Request request, ServerTransaction serverTransaction) {
        try {
            System.out.println("**********RECV REQ*************\n" + request.toString());
            Exchange exchange = new DefaultExchange(_endpoint);
            Message in = new Message(_endpoint.getCamelContext(), provider, request, serverTransaction);
            exchange.setIn(in);
            process(exchange);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected void _processResponseEvent(SipProvider provider, Response response, ClientTransaction clientTransaction) {
        try {
            System.out.println("**********RECV RESP*************\n" + response.toString());
            Exchange exchange = new DefaultExchange(_endpoint);
            Message in = new Message(_endpoint.getCamelContext(), provider, response, clientTransaction);
            exchange.setIn(in);
            process(exchange);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
