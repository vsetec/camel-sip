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
import javax.sip.Dialog;
import javax.sip.ServerTransaction;
import javax.sip.SipProvider;
import javax.sip.Transaction;
import javax.sip.message.Request;
import javax.sip.message.Response;
import org.apache.camel.CamelContext;
import org.apache.camel.support.DefaultMessage;

/**
 *
 * @author fedd
 */
public class Message extends DefaultMessage {

    private final SipProvider _provider;
    private final Transaction _transaction;

    public Message(CamelContext camelContext, SipProvider provider, Request request, ServerTransaction serverTransaction) {
        super(camelContext);
        _provider = provider;
        ServerTransaction transaction = serverTransaction;
        assert (transaction != null);
        _transaction = transaction;
        super.setBody(request);
    }

    public Message(CamelContext camelContext, SipProvider provider, Response response, ClientTransaction clientTransaction) {
        super(camelContext);
        _provider = provider;
        _transaction = clientTransaction;
        super.setBody(response);
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

    public <T extends javax.sip.message.Message> T getMessage() {
        return (T) getBody();
    }

    public Dialog getDialog() {
        return _transaction.getDialog();
    }

    public <T extends Transaction> T getTransaction() {
        return (T) _transaction;
    }

}
