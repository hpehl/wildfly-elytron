/*
 * JBoss, Home of Professional Open Source
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wildfly.security.sasl.digest;

import static org.wildfly.security._private.ElytronMessages.log;
import static org.wildfly.security.sasl.digest._private.DigestUtil.*;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import org.wildfly.common.Assert;
import org.wildfly.security.util.ByteStringBuilder;

import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.sasl.AuthorizeCallback;
import javax.security.sasl.RealmCallback;
import javax.security.sasl.SaslException;
import javax.security.sasl.SaslServer;

/**
 * @author <a href="mailto:pskopek@redhat.com">Peter Skopek</a>
 *
 */
class DigestSaslServer extends AbstractDigestMechanism implements SaslServer {

    DigestSaslServer(String[] realms, String mechanismName, String protocol, String serverName, CallbackHandler callbackHandler, Charset charset, String[] qops, String[] ciphers) throws SaslException {
        super(mechanismName, protocol, serverName, callbackHandler, FORMAT.SERVER, charset, ciphers);
        this.realms = realms;
        this.supportedCiphers = getSupportedCiphers(ciphers);
        this.qops = qops;
    }

    private static final byte STEP_ONE = 1;
    private static final byte STEP_THREE = 3;

    private String[] realms;
    private String supportedCiphers;
    private int receivingMaxBuffSize = DEFAULT_MAXBUF;
    private String[] qops;
    private int nonceCount = -1;

    /**
     * Generates a digest challenge
     *
     *    digest-challenge  =
     *    1#( realm | nonce | qop-options | stale | maxbuf | charset
     *          algorithm | cipher-opts | auth-param )
     *
     *   realm             = "realm" "=" <"> realm-value <">
     *   realm-value       = qdstr-val
     *   nonce             = "nonce" "=" <"> nonce-value <">
     *   nonce-value       = qdstr-val
     *   qop-options       = "qop" "=" <"> qop-list <">
     *   qop-list          = 1#qop-value
     *   qop-value         = "auth" | "auth-int" | "auth-conf" |
     *                        token
     *   stale             = "stale" "=" "true"
     *   maxbuf            = "maxbuf" "=" maxbuf-value
     *   maxbuf-value      = 1*DIGIT
     *   charset           = "charset" "=" "utf-8"
     *   algorithm         = "algorithm" "=" "md5-sess"
     *   cipher-opts       = "cipher" "=" <"> 1#cipher-value <">
     *   cipher-value      = "3des" | "des" | "rc4-40" | "rc4" |
     *                       "rc4-56" | token
     *   auth-param        = token "=" ( token | quoted-string )
     * @return
     */
    private byte[] generateChallenge() {
        ByteStringBuilder challenge = new ByteStringBuilder();

        // realms
        StringBuilder sb = new StringBuilder();
        for (String realm: this.realms) {
            sb.append("realm=\"").append(SaslQuote.quote(realm)).append("\"").append(DELIMITER);
        }
        challenge.append(sb.toString().getBytes(getCharset()));


        // nonce
        assert nonce == null;
        nonce = generateNonce();
        challenge.append("nonce=\"");
        challenge.append(SaslQuote.quote(nonce));
        challenge.append("\"").append(DELIMITER);

        // qop
        if (qops != null) {
            challenge.append("qop=\"");
            boolean first = true;
            for(String qop : qops){
                if(!first) challenge.append(DELIMITER);
                first = false;
                challenge.append(SaslQuote.quote(qop));
            }
            challenge.append("\"").append(DELIMITER);
        }

        // maxbuf
        if (receivingMaxBuffSize != DEFAULT_MAXBUF) {
            challenge.append("maxbuf=");
            challenge.append(String.valueOf(receivingMaxBuffSize));
            challenge.append(DELIMITER);
        }

        // charset
        if (StandardCharsets.UTF_8.equals(getCharset())) {
            challenge.append("charset=");
            challenge.append("utf-8");
            challenge.append(DELIMITER);
        }

        // cipher
        if (supportedCiphers != null && qops != null && arrayContains(qops, QOP_AUTH_CONF)) {
            challenge.append("cipher=\"");
            challenge.append(supportedCiphers);
            challenge.append("\"").append(DELIMITER);
        }

        challenge.append("algorithm=md5-sess"); // only for backwards compatibility with HTTP Digest

        return challenge.toArray();
    }

    private void noteDigestResponseData(HashMap<String, byte[]> parsedDigestResponse) {
        byte[] data = parsedDigestResponse.get("nc");
        if (data != null) {
            nonceCount = Integer.parseInt(new String(data, StandardCharsets.UTF_8));
        } else {
            nonceCount = -1;
        }

        data = parsedDigestResponse.get("cipher");
        if (data != null) {
            cipher = new String(data, StandardCharsets.UTF_8);
        } else {
            cipher = "";
        }

        data = parsedDigestResponse.get("authzid");
        if (data != null) {
            authzid = new String(data, StandardCharsets.UTF_8);
        } else {
            authzid = null;
        }
    }

    private byte[] validateDigestResponse(HashMap<String, byte[]> parsedDigestResponse) throws SaslException {
        if (nonceCount != 1) {
            throw log.saslNonceCountMustEqual(getMechanismName(), 1, nonceCount);
        }

        Charset clientCharset = StandardCharsets.ISO_8859_1;
        if (parsedDigestResponse.get("charset") != null) {
            String cCharset = new String(parsedDigestResponse.get("charset"), StandardCharsets.UTF_8);
            if (cCharset.equals("utf-8")) {
                if (StandardCharsets.UTF_8.equals(getCharset())) {
                    clientCharset = StandardCharsets.UTF_8;
                } else {
                    throw log.saslUnsupportedCharset(getMechanismName(), "UTF-8");
                }
            } else {
                throw log.saslUnknownCharset(getMechanismName());
            }
        }

        String userName;
        if (parsedDigestResponse.get("username") != null) {
            userName = new String(parsedDigestResponse.get("username"), clientCharset);
        } else {
            throw log.saslMissingDirective(getMechanismName(), "username");
        }

        String clientRealm;
        if (parsedDigestResponse.get("realm") != null) {
            clientRealm = new String(parsedDigestResponse.get("realm"), clientCharset);
        } else {
            clientRealm = "";
        }
        if (!arrayContains(realms, clientRealm)) {
            throw log.saslUnallowedClientRealm(getMechanismName(), clientRealm);
        }

        if (parsedDigestResponse.get("nonce") == null) {
            throw log.saslMissingDirective(getMechanismName(), "nonce");
        }

        byte[] nonceFromClient = parsedDigestResponse.get("nonce");
        if (!Arrays.equals(nonce, nonceFromClient)) {
            throw log.saslNoncesDoNotMatch(getMechanismName());
        }

        if (parsedDigestResponse.get("cnonce") == null) {
            throw log.saslMissingDirective(getMechanismName(), "cnonce");
        }
        cnonce = parsedDigestResponse.get("cnonce");

        if (parsedDigestResponse.get("nc") == null) {
            throw log.saslMissingDirective(getMechanismName(), "nc");
        }

        String clientDigestURI;
        if (parsedDigestResponse.get("digest-uri") != null) {
            clientDigestURI = new String(parsedDigestResponse.get("digest-uri"), clientCharset);
            if (!clientDigestURI.equalsIgnoreCase(digestURI)) {
                throw log.saslMismatchedWrongDigestUri(getMechanismName(), clientDigestURI, digestURI);
            }
        } else {
            throw log.saslMissingDirective(getMechanismName(), "digest-uri");
        }

        qop = QOP_AUTH;
        if (parsedDigestResponse.get("qop") != null) {
            qop = new String(parsedDigestResponse.get("qop"), clientCharset);
            if (!arrayContains(QOP_VALUES, qop)) {
                throw log.saslUnexpectedQop(getMechanismName(), qop);
            }
            if (qop != null && qop.equals(QOP_AUTH) == false) {
                setWrapper(new DigestWrapper(qop.equals(QOP_AUTH_CONF)));
            }
        }

        // get password
        final NameCallback nameCallback = new NameCallback("User name", userName);
        final RealmCallback realmCallback = new RealmCallback("User realm", clientRealm);
        byte[] digest_urp = getPredigestedSaltedPassword(realmCallback, nameCallback);
        if (digest_urp == null) {
            digest_urp = getSaltedPasswordFromTwoWay(realmCallback, nameCallback, true);
        }
        if (digest_urp == null) {
            digest_urp = getSaltedPasswordFromPasswordCallback(realmCallback, nameCallback, true);
        }
        if (digest_urp == null) {
            throw log.saslCallbackHandlerDoesNotSupportCredentialAcquisition(getMechanismName(), null);
        }

        hA1 = H_A1(messageDigest, digest_urp, nonce, cnonce, authzid, clientCharset);

        byte[] expectedResponse = digestResponse(messageDigest, hA1, nonce, nonceCount, cnonce, authzid, qop, digestURI, true);

        // check response
        if (parsedDigestResponse.get("response") == null) {
            throw log.saslMissingDirective(getMechanismName(), "response");
        }
        if ( ! Arrays.equals(expectedResponse, parsedDigestResponse.get("response"))) {
            throw log.saslAuthenticationRejectedInvalidProof(getMechanismName());
        }

        createCiphersAndKeys();

        // authorization check
        final AuthorizeCallback authorizeCallback = new AuthorizeCallback(userName, authzid == null ? userName : authzid);
        try {
            tryHandleCallbacks(authorizeCallback);
        } catch (UnsupportedCallbackException e) {
            throw log.saslAuthorizationUnsupported(getMechanismName(), e);
        }
        if (authorizeCallback.isAuthorized()) {
            authzid = authorizeCallback.getAuthorizedID();
        } else {
            throw log.saslAuthorizationFailed(getMechanismName(), userName, authzid);
        }

        return createResponseAuth(parsedDigestResponse);
    }

    private byte[] createResponseAuth(HashMap<String, byte[]> parsedDigestResponse) {
        ByteStringBuilder responseAuth = new ByteStringBuilder();
        responseAuth.append("rspauth=");

        byte[] response_value = digestResponse(messageDigest, hA1, nonce, nonceCount, cnonce, authzid, qop, digestURI, false);

        responseAuth.append(response_value);
        return responseAuth.toArray();
    }

    /* (non-Javadoc)
     * @see javax.security.sasl.SaslServer#getAuthorizationID()
     */
    @Override
    public String getAuthorizationID() {
        return authzid;
    }

    /* (non-Javadoc)
     * @see org.wildfly.sasl.util.AbstractSaslParticipant#init()
     */
    @Override
    public void init() {
        setNegotiationState(STEP_ONE);
    }

    @Override
    public byte[] evaluateResponse(byte[] response) throws SaslException {
        return evaluateMessage(response);
    }

    @Override
    protected byte[] evaluateMessage(int state, final byte[] message) throws SaslException {
        switch (state) {
            case STEP_ONE:
                if (message != null && message.length != 0) {
                    throw log.saslInitialChallengeMustBeEmpty(getMechanismName());
                }
                setNegotiationState(STEP_THREE);
                return generateChallenge();
            case STEP_THREE:
                if (message == null || message.length == 0) {
                    throw log.saslClientRefusesToInitiateAuthentication(getMechanismName());
                }

                // parse digest response
                HashMap<String, byte[]> parsedDigestResponse = parseResponse(message);
                noteDigestResponseData(parsedDigestResponse);

                // validate
                byte[] response = validateDigestResponse(parsedDigestResponse);

                negotiationComplete();
                return response;
        }
        throw Assert.impossibleSwitchCase(state);
    }

}
