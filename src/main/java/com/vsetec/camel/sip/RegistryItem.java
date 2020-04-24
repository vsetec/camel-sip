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

import java.time.Instant;
import java.util.Objects;
import javax.sip.address.SipURI;

/**
 *
 * @author fedd
 */
public class RegistryItem {

    final SipURI _registrarUri; // whom have we registered this with
    private final String _phoneOfficialAddress; // from from/to field
    final SipURI _phoneRealAddress; // from contact field
    final Instant _validTill; // from response Expires header
    final String _transportToReach;

    public RegistryItem(SipURI registrarUri, String phoneOfficialAddress, SipURI phoneRealAddress, int secondsToLive, String transport) {
        this._registrarUri = registrarUri;
        this._phoneOfficialAddress = phoneOfficialAddress;
        this._phoneRealAddress = phoneRealAddress;
        this._validTill = Instant.ofEpochMilli(System.currentTimeMillis() + secondsToLive * 1000);
        _transportToReach = transport;
    }

    @Override
    public int hashCode() {
        int hash = 3;
        if (_registrarUri != null) {
            hash = 37 * hash + Objects.hashCode(this._registrarUri.toString());
        }
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
        final RegistryItem other = (RegistryItem) obj;
        if (this._registrarUri != null && other._registrarUri != null) {
            if (!Objects.equals(this._registrarUri.toString(), other._registrarUri.toString())) {
                return false;
            }
        } else if (this._registrarUri != other._registrarUri) { // one of them is null, comparison is legitimate
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
