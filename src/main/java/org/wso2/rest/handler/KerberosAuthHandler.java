/*
 *
 *   Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *   WSO2 Inc. licenses this file to you under the Apache License,
 *   Version 2.0 (the "License"); you may not use this file except
 *   in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */

package org.wso2.rest.handler;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.ManagedLifecycle;
import org.apache.synapse.MessageContext;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.core.axis2.Axis2Sender;
import org.apache.synapse.deployers.SynapseArtifactDeploymentException;
import org.apache.synapse.rest.AbstractHandler;
import org.ietf.jgss.GSSException;

import javax.security.auth.login.LoginException;
import java.security.PrivilegedActionException;
import java.util.Map;

/**
 * ESB rest handler for kerberos authentication in REST API request.
 */
public class KerberosAuthHandler extends AbstractHandler implements ManagedLifecycle {

    private static final Log log = LogFactory.getLog(KerberosAuthHandler.class);
    private static final String NEGOTIATE = "Negotiate";
    private static final String AUTHORIZATION = "Authorization";
    private KerberosAuthenticator kerberosAuthenticator;
    private String serverPrincipal;
    private String realm;
    private String keyTabFilePath;

    @Override
    public void init(SynapseEnvironment synapseEnvironment) {
        if (serverPrincipal == null) {
            throw new SynapseArtifactDeploymentException("KerberosAuthHandler serverPrincipal parameter is empty.");
        }
        if (realm == null) {
            throw new SynapseArtifactDeploymentException("KerberosAuthHandler realm parameter is empty.");
        }
        if (keyTabFilePath == null) {
            throw new SynapseArtifactDeploymentException("KerberosAuthHandler keyTabFilePath parameter is empty.");
        }
        try {
            kerberosAuthenticator = new KerberosAuthenticator(serverPrincipal, realm, keyTabFilePath);
        } catch (LoginException | PrivilegedActionException | GSSException ex) {
            log.error(ex.getMessage(), ex);
            throw new SynapseArtifactDeploymentException(ex.getMessage(), ex);
        }
    }

    public boolean handleResponse(MessageContext messageContext) {
        return true;
    }

    public boolean handleRequest(MessageContext messageContext) {
        byte[] serverToken = null;
        org.apache.axis2.context.MessageContext axis2MessageContext
                = ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        Object headers = axis2MessageContext.getProperty(
                org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
        if (headers != null && headers instanceof Map) {
            Map headersMap = (Map) headers;
            String authHeader = (String) headersMap.get(AUTHORIZATION);
            if (authHeader == null) {
                return unAuthorizedUser(headersMap, axis2MessageContext, messageContext, null);
            } else {
                if (kerberosAuthenticator != null) {
                    String negotiate = authHeader.substring(0, 10);
                    if (NEGOTIATE.equals(negotiate.trim())) {
                        String authToken = authHeader.substring(10).trim();
                        byte[] clientToken = Base64.decodeBase64(authToken.getBytes());
                        ValidationResponse validationResponse = new ValidationResponse();
                        try {
                            validationResponse = kerberosAuthenticator.processToken(clientToken);
                            serverToken = validationResponse.getInformation();
                        } catch (GSSException ex) {
                            if (log.isDebugEnabled()) {
                                log.debug(ex.getMessage(), ex);
                            }
                        }
                        if (validationResponse.isSuccessful()) {
                            return authorized(axis2MessageContext);
                        } else {
                            if (log.isDebugEnabled()) {
                                log.debug("More tokens are expected to be supplied to start the context creation " +
                                        "phase.");
                            }
                            return unAuthorizedUser(headersMap, axis2MessageContext, messageContext, serverToken);
                        }
                    } else {
                        if (log.isDebugEnabled()) {
                            log.debug("Negotiate header is missing.");
                        }
                        return unAuthorizedUser(headersMap, axis2MessageContext, messageContext, null);
                    }
                } else {
                    return accessForbidden(headersMap, axis2MessageContext, messageContext);
                }
            }
        }
        return true;
    }

    private boolean unAuthorizedUser(Map headersMap, org.apache.axis2.context.MessageContext axis2MessageContext,
                                     MessageContext messageContext, byte[] serverToken) {
        String outServerTokenString = null;
        headersMap.clear();
        try {
            if (serverToken != null) {
                byte[] outServerToken = Base64.encodeBase64(serverToken);
                outServerTokenString = new String(outServerToken, "UTF-8");
            }
            axis2MessageContext.setProperty("HTTP_SC", "401");
            if (outServerTokenString != null) {
                headersMap.put("WWW-Authenticate", "Negotiate " + outServerTokenString);
            } else {
                headersMap.put("WWW-Authenticate", "Negotiate");
            }
            axis2MessageContext.setProperty("NO_ENTITY_BODY", new Boolean("true"));
            messageContext.setProperty("RESPONSE", "true");
            messageContext.setTo(null);
            Axis2Sender.sendBack(messageContext);
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean authorized(org.apache.axis2.context.MessageContext axis2MessageContext) {
        axis2MessageContext.setProperty("HTTP_SC", "200");
        return true;
    }

    private boolean accessForbidden(Map headersMap, org.apache.axis2.context.MessageContext axis2MessageContext,
                                    MessageContext messageContext) {
        headersMap.clear();
        axis2MessageContext.setProperty("HTTP_SC", "403");
        axis2MessageContext.setProperty("NO_ENTITY_BODY", new Boolean("true"));
        messageContext.setProperty("RESPONSE", "true");
        messageContext.setTo(null);
        Axis2Sender.sendBack(messageContext);
        return false;
    }

    public void setServerPrincipal(String serverPrincipal) {
        this.serverPrincipal = serverPrincipal;
    }

    public void setRealm(String realm) {
        this.realm = realm;
    }

    public void setKeyTabFilePath(String keyTabFilePath) {
        this.keyTabFilePath = keyTabFilePath;
    }

    @Override
    public void destroy() {
    }
}
