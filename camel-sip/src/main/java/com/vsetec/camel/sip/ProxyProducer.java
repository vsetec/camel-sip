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

import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import javax.sip.ClientTransaction;
import javax.sip.Dialog;
import javax.sip.InvalidArgumentException;
import javax.sip.ServerTransaction;
import javax.sip.SipException;
import javax.sip.SipProvider;
import javax.sip.address.Address;
import javax.sip.address.SipURI;
import javax.sip.header.ContactHeader;
import javax.sip.header.ExpiresHeader;
import javax.sip.header.FromHeader;
import javax.sip.header.RecordRouteHeader;
import javax.sip.header.RouteHeader;
import javax.sip.header.ViaHeader;
import javax.sip.message.Request;
import javax.sip.message.Response;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultProducer;

/**
 *
 * @author fedd
 */
class ProxyProducer extends DefaultProducer {

    private final SipComponent component;
    private final Registrar _registrar;

    public ProxyProducer(Endpoint endpoint, final SipComponent component) {
        super(endpoint);
        this.component = component;
        _registrar = component.getRegistrar();
    }

    private boolean _tryRedirectRequestToAnotherDialog(SipProvider receivingProvider, ServerTransaction serverTransaction, Request request) throws InvalidArgumentException, SipException, ParseException {
        Dialog serverDialog = serverTransaction.getDialog();
        if (serverDialog != null) {
            List<Dialog> clientDialogs = _registrar.getClientDialogByServerDialog(serverDialog);
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
            } else {
                // something different.
                for (Dialog clientDialog : clientDialogs) {
                    component.redirectRequestToSpecificAddress(receivingProvider, serverTransaction, request, (SipURI) clientDialog.getRemoteParty().getURI());
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
            RegistryItem reg = _registrar.getRegistryItemByContact(toWhom.toString());
            if (reg != null) {
                toWhom = (SipURI) toWhom.clone();
                toWhom.setTransportParam(reg._transportToReach);
                ClientTransaction clientTransaction = component.redirectRequestToSpecificAddress(receivingProvider, serverTransaction, request, toWhom);
                Dialog serverDialog = serverTransaction.getDialog();
                Dialog clientDialog = clientTransaction.getDialog();
                _registrar.bindDialogs(serverDialog, Collections.singletonList(clientDialog));
                return true;
            }
            // no such phone registered. redirect to registrar IF it is a known phone calling
            // redirecting to it's own registrar
            ContactHeader contact = (ContactHeader) request.getHeader(ContactHeader.NAME);
            SipURI fromWhom = (SipURI) contact.getAddress().getURI().clone();
            fromWhom.removeParameter("ob"); //TODO: provide more sophisticated "ob" handling
            RegistryItem myReg = _registrar.getRegistryItemByContact(fromWhom.toString());
            if (myReg != null) {
                if (myReg._registrarUri != null) {
                    ClientTransaction clientTransaction = component.redirectRequestToSpecificAddress(receivingProvider, serverTransaction, request, myReg._registrarUri);
                    Dialog serverDialog = serverTransaction.getDialog();
                    Dialog clientDialog = clientTransaction.getDialog();
                    _registrar.bindDialogs(serverDialog, Collections.singletonList(clientDialog));
                    return true;
                } else {
                    // WE are the caller phone registrar! we have to look in our own records
                    Set<RegistryItem> regs = _registrar.getRegistryItemsByRegisteredAddress(toWhom.toString());
                    Dialog serverDialog = serverTransaction.getDialog();
                    List<Dialog> clientDialogs = new ArrayList<>(3);
                    for (RegistryItem reg1 : regs) {
                        if (reg1._registrarUri == null && reg1._validTill.isAfter(Instant.now())) {
                            ClientTransaction clientTransaction = component.redirectRequestToSpecificAddress(receivingProvider, serverTransaction, request, reg1._phoneRealAddress);
                            Dialog clientDialog = clientTransaction.getDialog();
                            clientDialogs.add(clientDialog);
                        }
                    }
                    if (clientDialogs.isEmpty()) {
                        return false;
                    }
                    _registrar.bindDialogs(serverDialog, clientDialogs);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean _tryRedirectResponseToInitialSender(ClientTransaction clientTransaction, Response response) throws SipException, InvalidArgumentException {
        // as it is a response originated here, we can get a server transaction
        ServerTransaction serverTransaction = _registrar.getServerTransaction(clientTransaction);
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
        if (response.getStatusCode() == 200) {
            //we are okaying some request
            if (requestWeveReceived.getMethod().equals(Request.REGISTER)) {
                // and it is a register request!
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
                String transport = ((ViaHeader) response.getHeader(ViaHeader.NAME)).getTransport();
                _registrar.register(registeredName, selfProclaimedName, transport, registrarAddress, expires);
                return true;
            }
        }
        return false;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        org.apache.camel.Message toSend = exchange.getIn();
        if (!(toSend instanceof Message)) {
            //TODO: convert to sipmessage somehow and still send
            return;
        }
        Message message = (Message) toSend;
        // we've got message came from elsewhere, need to forward it as a proxy
        if (message.isRequest()) {
            // need to forward a request as a proxy
            Request request = message.getMessage();
            ServerTransaction serverTransaction = message.getTransaction();
            // maybe it happens to be a known server transaction! and we're cancelling it
            if (request.getMethod().equals("CANCEL")) {
                Set<ClientTransaction> clientTransactions = _registrar.getClientTransactions(serverTransaction);
                if (clientTransactions != null && !clientTransactions.isEmpty()) {
                    for (ClientTransaction clientTransaction : clientTransactions) {
                        Request cancelRequest = clientTransaction.createCancel();
                        ClientTransaction clientCancelTransaction = message.getProvider().getNewClientTransaction(cancelRequest);
                        //clientTransaction.setApplicationData(serverTransaction);
                        _registrar.bindTransactions(serverTransaction, clientTransaction);
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
                        ViaHeader viaHeader = component.getHeaderFactory().createViaHeader(listenedIp, routeUri.getPort(), routeUri.getTransportParam(), null);
                        SipURI recordRouteUri = component.getAddressFactory().createSipURI(null, listenedIp);
                        Address recordRouteAddress = component.getAddressFactory().createAddress(null, recordRouteUri);
                        recordRouteUri.setPort(routeUri.getPort());
                        recordRouteUri.setLrParam();
                        recordRouteUri.setTransportParam(routeUri.getTransportParam());
                        RecordRouteHeader recordRoute = component.getHeaderFactory().createRecordRouteHeader(recordRouteAddress);
                        // actual forwarding
                        Request newRequest = (Request) request.clone();
                        newRequest.addFirst(viaHeader);
                        newRequest.addHeader(recordRoute);
                        ClientTransaction clientTransaction = message.getProvider().getNewClientTransaction(newRequest);
                        _registrar.bindTransactions(serverTransaction, clientTransaction);
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
