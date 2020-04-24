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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TooManyListenersException;
import javax.sip.ClientTransaction;
import javax.sip.Dialog;
import javax.sip.InvalidArgumentException;
import javax.sip.ListeningPoint;
import javax.sip.ObjectInUseException;
import javax.sip.ServerTransaction;
import javax.sip.SipProvider;
import javax.sip.SipStack;
import javax.sip.TransportNotSupportedException;
import javax.sip.address.SipURI;
import javax.sip.header.ContactHeader;
import javax.sip.header.ExpiresHeader;
import javax.sip.header.FromHeader;
import javax.sip.header.RouteHeader;
import javax.sip.header.ViaHeader;
import javax.sip.message.Request;
import javax.sip.message.Response;

/**
 *
 * @author fedd
 */
public class Registrar {

    private final Map<String, SipProvider> _sipProvidersByHostPortAndTransport = new HashMap<>(3);
    private final Map<String, Set<RegistryItem>> _registeredNameRegistry = new HashMap<>(); // registered name - regitem
    private final Map<String, RegistryItem> _selfNameRegistry = new HashMap<>(); // self proclaimed name - regitems
    private final Map<ServerTransaction, Set<ClientTransaction>> _serverClients = new HashMap<>();
    private final Map<ClientTransaction, ServerTransaction> _clientServer = new HashMap<>();
    private final Map<Dialog, Dialog> _clientServerDialog = new HashMap<>();
    private final Map<Dialog, List<Dialog>> _serverClientDialogs = new HashMap<>();
    private final SipStack _sipStack;
    private final Listener _listener;

    public Registrar(SipStack stack) {
        this._sipStack = stack;
        _listener = new Listener(this);
    }

    public Listener getSipListener() {
        return _listener;
    }

    public synchronized SipProvider getProvider(String transport) {

        String transportStarting = transport + ":";

        // choose random provider that is capable for listening for this transport
        for (Map.Entry<String, SipProvider> kv : _sipProvidersByHostPortAndTransport.entrySet()) {
            if (kv.getKey().startsWith(transportStarting)) {
                return kv.getValue();
            }
        }

        // there was no provider for this transport! Let's try create some (error prone)
        // to create one, let's choose some random host
        SipProvider someProvider = _sipProvidersByHostPortAndTransport.values().iterator().next();
        String someHost = someProvider.getListeningPoints()[0].getIPAddress();
        // decide a port
        int portCandidate = 5060;

        choosing:
        while (true) {
            String hostPortEnding = ":" + someHost + ":" + portCandidate;
            for (String key : _sipProvidersByHostPortAndTransport.keySet()) {
                if (key.endsWith(hostPortEnding)) {
                    portCandidate = portCandidate + 10;
                    continue choosing;
                }
            }
            // havent met such a port in our list
            return getProvider(someHost, portCandidate, transport);
        }
    }

    public SipProvider getProvider(String listeningHost, int listeningPort, String transport) {

        // one provider, one listening point, because I failed to find a way to get receiving listening point in sip listener
        String key = transport + ":" + listeningHost + ":" + listeningPort;

        SipProvider ret = _sipProvidersByHostPortAndTransport.get(key);
        if (ret == null) {
            try {
                ListeningPoint lp = _sipStack.createListeningPoint(listeningHost, listeningPort, transport);
                // is there a better way?
                Iterator sps = _sipStack.getSipProviders();
                while (sps.hasNext()) {
                    SipProvider tmpSp = (SipProvider) sps.next();
                    ListeningPoint foundLp = tmpSp.getListeningPoint(transport);
                    // make sure we create a different provider for a listening point
                    if (foundLp != null && foundLp.getIPAddress().equals(listeningHost) && foundLp.getPort() == listeningPort) {
                        ret = tmpSp;
                        break;
                    }
//                    try {
//                        tmpSp.addListeningPoint(lp);
//                        ret = tmpSp;
//                        break;
//                    } catch (TransportAlreadySupportedException e) {
//
//                    }
                }
                if (ret == null) {
                    ret = _sipStack.createSipProvider(lp);
                }
                _sipProvidersByHostPortAndTransport.put(key, ret);
                ret.addSipListener(_listener);
                //_sipListenersByProvider.put(ret, _listener);
            } catch (InvalidArgumentException | ObjectInUseException | TransportNotSupportedException | TooManyListenersException e) {
                throw new RuntimeException(e);
            }
        }
        return ret;
    }

    public synchronized void bindTransactions(ServerTransaction serverTransaction, ClientTransaction clientTransaction) {
        //assert (clientTransaction != null && serverTransaction != null);
        _clientServer.put(clientTransaction, serverTransaction);
        _serverClients.get(serverTransaction).add(clientTransaction);
    }

    public synchronized void registerTransaction(ServerTransaction serverTransaction) {
        //assert (serverTransaction != null);
        _serverClients.put(serverTransaction, new HashSet<>(3));
    }

    public synchronized void unregisterTransaction(ServerTransaction serverTransaction) {
        Set<ClientTransaction> removed = _serverClients.remove(serverTransaction);
        _clientServer.keySet().removeAll(removed);
    }

    public synchronized void unregisterTransaction(ClientTransaction clientTransaction) {
        ServerTransaction serverTransaction = _clientServer.remove(clientTransaction);
        Set<ClientTransaction> clientTransactions = _serverClients.get(serverTransaction);
        clientTransactions.remove(clientTransaction);
        if (clientTransactions.isEmpty()) {
            _serverClients.remove(serverTransaction);
        }
    }

    public synchronized void bindDialogs(Dialog serverDialog, List<Dialog> clientDialogs) {

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

    public synchronized void unregisterDialog(Dialog dialog) {
        if (dialog.isServer()) {
            List<Dialog> removed = _serverClientDialogs.remove(dialog);
            _clientServerDialog.keySet().removeAll(removed);
        } else {
            Dialog removed = _clientServerDialog.remove(dialog);
            List<Dialog> clientDialogs = _serverClientDialogs.get(removed);
            clientDialogs.remove(dialog);
            if (clientDialogs.isEmpty()) {
                _serverClientDialogs.remove(removed);
            }
        }

    }

    public List<Dialog> getClientDialogByServerDialog(Dialog serverDialog) {
        return _serverClientDialogs.get(serverDialog);
    }

    public RegistryItem getRegistryItemByContact(String contact) {
        // TODO: review this
        contact = _stripUpToSemicolon(contact);
        return _selfNameRegistry.get(contact);
    }

    public Set<RegistryItem> getRegistryItemsByRegisteredAddress(String registeredAddress) {
        return _registeredNameRegistry.get(registeredAddress);
    }

    public ServerTransaction getServerTransaction(ClientTransaction clientTransaction) {
        return _clientServer.get(clientTransaction);
    }

    public Set<ClientTransaction> getClientTransactions(ServerTransaction serverTransaction) {
        return _serverClients.get(serverTransaction);
    }

    protected boolean tryRegister(ServerTransaction serverTransaction, Response response) {
        return tryRegister(serverTransaction, response, null);
    }

    protected boolean tryRegister(ServerTransaction serverTransaction, Response response, ClientTransaction clientTransaction) {
        // I am a server. I received a request and have to send a response
        Request requestWeveReceived = serverTransaction.getRequest();
        if (response.getStatusCode() == 200) {
            //we are okaying some request
            if (requestWeveReceived.getMethod().equals(Request.REGISTER)) {
                // and it is a register request!
                // remember this phone for future proxying
                String registeredName = ((FromHeader) requestWeveReceived.getHeader(FromHeader.NAME)).getAddress().getURI().toString();
                SipURI selfProclaimedName = (SipURI) ((ContactHeader) requestWeveReceived.getHeader(ContactHeader.NAME)).getAddress().getURI();

                // expires magic
                int expires;
                {
                    ExpiresHeader expiresHeader = ((ExpiresHeader) requestWeveReceived.getHeader(ExpiresHeader.NAME));
                    if (expiresHeader != null) {
                        expires = expiresHeader.getExpires();
                    } else {
                        expires = ((ContactHeader) requestWeveReceived.getHeader(ContactHeader.NAME)).getExpires();
                    }

                }
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
                String transport = ((ViaHeader) response.getHeader(ViaHeader.NAME)).getTransport();
                this._register(registeredName, selfProclaimedName, transport, registrarAddress, expires);
                return true;
            }
        }
        return false;
    }

    private void _register(String registeredAddress, SipURI contactAddress, String transport, SipURI registrarAddress, int expires) {
        Set<RegistryItem> items;
        synchronized (_registeredNameRegistry) {
            items = _registeredNameRegistry.get(registeredAddress);
            if (items == null) {
                items = new HashSet<>(4);
                _registeredNameRegistry.put(registeredAddress, items);
            }
        }

        RegistryItem item = new RegistryItem(registrarAddress, registeredAddress, contactAddress, expires, transport);
        synchronized (items) {
            items.add(item);
        }

        String selfProclaimedName = contactAddress.toString();
        // TODO review this
        // strip off all the fancy parameters
        selfProclaimedName = _stripUpToSemicolon(selfProclaimedName);
        _selfNameRegistry.put(selfProclaimedName, item);

        System.out.println("**********REGISTER***********\nregistrar: " + (registrarAddress == null ? "<this server>" : registrarAddress)
                + "\nregisteredName: " + registeredAddress
                + "\nunder name of: " + contactAddress.toString()
                + "\nexpiring in sec: " + expires + "\n\n");

    }

    private String _stripUpToSemicolon(String string) {
        int semicolonPos = string.indexOf(";");
        if (semicolonPos > 0) {
            string = string.substring(0, semicolonPos);
        }
        return string;
    }

}
