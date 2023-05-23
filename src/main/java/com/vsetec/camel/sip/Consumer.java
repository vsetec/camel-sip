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

import java.util.Set;
import javax.sip.SipProvider;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.support.DefaultExchange;

/**
 *
 * @author fedd
 */
class Consumer implements org.apache.camel.Consumer {

    private final Endpoint _endpoint;
    private final ConsumerProcessor _wrappingProcessor;
    private final Listener _listener;
    private final SipProvider _provider;
    private final Set<String> _requestMethods;
    private final Set<String> _requestMethodsNot;
    private final Set<Integer> _responseCodes;
    private final Set<Integer> _responseCodesNot;

    public Consumer(Registrar registrar, Endpoint endpoint, String listeningHost, Integer listeningPort, String transport, Processor processor, Set<String> requestMethods, Set<String> requestMethodsNot, Set<Integer> responseCodes, Set<Integer> responseCodesNot) {
        _requestMethods = requestMethods;
        _requestMethodsNot = requestMethodsNot;
        _responseCodes = responseCodes;
        _responseCodesNot = responseCodesNot;
        _endpoint = endpoint;
        _provider = registrar.getProvider(listeningHost, listeningPort, transport);
        _listener = registrar.getSipListener();
        _wrappingProcessor = new ConsumerProcessor(endpoint) {
            @Override
            public void process(Exchange exchange) throws Exception {
                processor.process(exchange);
            }
        };
    }

    @Override
    public void start() {
        _listener.registerProcessor(_provider, _wrappingProcessor, _requestMethods, _requestMethodsNot, _responseCodes, _responseCodesNot);
    }

    @Override
    public void stop() {
        _listener.unregisterProcessor(_wrappingProcessor);
    }

    @Override
    public Endpoint getEndpoint() {
        return _endpoint;
    }

    @Override
    public Processor getProcessor() {
        return _wrappingProcessor;
    }

    @Override
    public Exchange createExchange(boolean autoRelease) {
        return new DefaultExchange(_endpoint);
    }

    @Override
    public void releaseExchange(Exchange exchange, boolean autoRelease) {
        // noop
    }

}
