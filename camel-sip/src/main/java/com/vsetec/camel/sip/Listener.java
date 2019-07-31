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

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.sip.ClientTransaction;
import javax.sip.Dialog;
import javax.sip.DialogTerminatedEvent;
import javax.sip.IOExceptionEvent;
import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.ServerTransaction;
import javax.sip.SipListener;
import javax.sip.SipProvider;
import javax.sip.TimeoutEvent;
import javax.sip.TransactionAlreadyExistsException;
import javax.sip.TransactionTerminatedEvent;
import javax.sip.TransactionUnavailableException;
import javax.sip.message.Request;
import javax.sip.message.Response;

/**
 *
 * @author fedd
 */
public class Listener implements SipListener {

    private final Registrar _registrar;
    private final Map<SipProvider, Processors> _processors = new HashMap<>();

    public Listener(Registrar registrar) {
        this._registrar = registrar;
    }

    private class Processors {

        private final Map<String, Set<ConsumerProcessor>> _methodProcessors = new HashMap<>();
        private final Map<ConsumerProcessor, Set<String>> _processorMethodsNot = new HashMap<>();
        private final Map<Integer, Set<ConsumerProcessor>> _statusProcessors = new HashMap<>();
        private final Map<ConsumerProcessor, Set<Integer>> _processorStatusesNot = new HashMap<>();
    }

    protected synchronized void registerProcessor(
            SipProvider provider,
            ConsumerProcessor processor,
            Set<String> methods,
            Set<String> methodsNot,
            Set<Integer> statuses,
            Set<Integer> statusesNot
    ) {

        Processors processorsCache = _processors.get(provider);
        if (processorsCache == null) {
            processorsCache = new Processors();
            _processors.put(provider, processorsCache);
        }

        boolean addedForExplicit = false;

        if (methods != null && !methods.isEmpty()) {
            for (String method : methods) {
                Set<ConsumerProcessor> processors = processorsCache._methodProcessors.get(method);
                if (processors == null) {
                    processors = new HashSet<>();
                    processorsCache._methodProcessors.put(method, processors);
                }
                processors.add(processor);
                addedForExplicit = true;
            }
        }

        if (statuses != null && !statuses.isEmpty()) {
            for (Integer status : statuses) {
                Set<ConsumerProcessor> processors = processorsCache._statusProcessors.get(status);
                if (processors == null) {
                    processors = new HashSet<>();
                    processorsCache._statusProcessors.put(status, processors);
                }
                processors.add(processor);
                addedForExplicit = true;
            }
        }

        if (!addedForExplicit) {
            if (methodsNot == null) {
                methodsNot = Collections.EMPTY_SET;
            }
            processorsCache._processorMethodsNot.put(processor, methodsNot);

            if (statusesNot == null) {
                statusesNot = Collections.EMPTY_SET;
            }
            processorsCache._processorStatusesNot.put(processor, statusesNot);
        }
    }

    protected synchronized void unregisterProcessor(ConsumerProcessor processor) {
        for (Processors processorsCache : _processors.values()) {
            for (Set<ConsumerProcessor> processors : processorsCache._methodProcessors.values()) {
                processors.remove(processor);
            }
            for (Set<ConsumerProcessor> processors : processorsCache._statusProcessors.values()) {
                processors.remove(processor);
            }
            processorsCache._processorMethodsNot.remove(processor);
            processorsCache._processorStatusesNot.remove(processor);
        }
    }

    @Override
    public void processRequest(RequestEvent event) {
        Request request = event.getRequest();
        ServerTransaction transaction = event.getServerTransaction();
        SipProvider provider = (SipProvider) event.getSource();
        if (transaction == null) {
            try {
                transaction = provider.getNewServerTransaction(request);
            } catch (TransactionAlreadyExistsException | TransactionUnavailableException e) {
                throw new RuntimeException(e);
            }
        }
        _registrar.registerTransaction(transaction);

        String method = request.getMethod();

        boolean ranExplicitly = false;

        Processors processorsCache = _processors.get(provider);

        Set<ConsumerProcessor> processors = processorsCache._methodProcessors.get(method);
        if (processors != null) {
            for (ConsumerProcessor processor : processors) {
                processor._processRequestEvent(provider, request, transaction);
                ranExplicitly = true;
            }
        }

        if (!ranExplicitly) {
            for (Map.Entry<ConsumerProcessor, Set<String>> nots : processorsCache._processorMethodsNot.entrySet()) {
                if (!nots.getValue().contains(method)) {
                    nots.getKey()._processRequestEvent(provider, request, transaction);
                }
            }
        }

    }

    @Override
    public void processResponse(ResponseEvent event) {
        Response response = event.getResponse();
        ClientTransaction transaction = event.getClientTransaction();
        SipProvider provider = (SipProvider) event.getSource();
        Integer status = event.getResponse().getStatusCode();

        boolean ranExplicitly = false;

        Processors processorsCache = _processors.get(provider);

        Set<ConsumerProcessor> processors = processorsCache._statusProcessors.get(status);
        if (processors != null) {
            for (ConsumerProcessor processor : processors) {
                processor._processResponseEvent(provider, response, transaction);
                ranExplicitly = true;
            }
        }

        if (!ranExplicitly) {
            for (Map.Entry<ConsumerProcessor, Set<Integer>> nots : processorsCache._processorStatusesNot.entrySet()) {
                if (!nots.getValue().contains(status)) {
                    nots.getKey()._processResponseEvent(provider, response, transaction);
                }
            }
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
            _registrar.unregisterTransaction(transactionTerminatedEvent.getServerTransaction());
        } else {
            _registrar.unregisterTransaction(transactionTerminatedEvent.getClientTransaction());
        }
    }

    @Override
    public void processDialogTerminated(DialogTerminatedEvent dialogTerminatedEvent) {
        Dialog dialog = dialogTerminatedEvent.getDialog();
        _registrar.unregisterDialog(dialog);
    }
}
