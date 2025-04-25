/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ode.axis2.httpbinding;

import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.core5.http.message.StatusLine;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.ode.axis2.ExternalService;
import org.apache.ode.axis2.ODEService;
import org.apache.ode.utils.Properties;
import org.apache.ode.axis2.OdeFault;
import org.apache.ode.axis2.util.ClusterUrlTransformer;
import org.apache.ode.bpel.epr.EndpointFactory;
import org.apache.ode.bpel.epr.WSAEndpoint;
import org.apache.ode.bpel.epr.MutableEndpoint;
import org.apache.ode.bpel.iapi.BpelServer;
import org.apache.ode.bpel.iapi.EndpointReference;
import org.apache.ode.bpel.iapi.Message;
import org.apache.ode.bpel.iapi.MessageExchange;
import org.apache.ode.bpel.iapi.PartnerRoleMessageExchange;
import org.apache.ode.bpel.iapi.ProcessConf;
import org.apache.ode.bpel.iapi.Scheduler;
import org.apache.ode.utils.DOMUtils;
import org.apache.ode.utils.wsdl.Messages;
import org.apache.ode.utils.wsdl.WsdlUtils;
import org.w3c.dom.Element;

import javax.wsdl.Binding;
import javax.wsdl.Definition;
import javax.wsdl.Operation;
import javax.wsdl.Port;
import javax.wsdl.Service;
import javax.xml.namespace.QName;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.net.URL;
import java.net.MalformedURLException;


/**
 * @author <a href="mailto:midon@intalio.com">Alexis Midon</a>
 */
public class HttpExternalService implements ExternalService {

    private static final Logger log = LoggerFactory.getLogger(ExternalService.class);
    private static final Messages msgs = Messages.getMessages(Messages.class);

    //private MultiThreadedHttpConnectionManager connections;
    private HttpClientConnectionManager connections;
    private CloseableHttpClient httpClient;

    protected ExecutorService executorService;
    protected Scheduler scheduler;
    protected BpelServer server;
    protected ProcessConf pconf;
    protected QName serviceName;
    protected String portName;
    protected WSAEndpoint endpointReference;

    protected HttpMethodConverter httpMethodConverter;

    protected Binding portBinding;
    private URL endpointUrl;

    private ClusterUrlTransformer clusterUrlTransformer;

    public HttpExternalService(ProcessConf pconf, QName serviceName, String portName,
                               ExecutorService executorService, Scheduler scheduler, BpelServer server,
                               HttpClientConnectionManager connManager, ClusterUrlTransformer clusterUrlTransformer) throws OdeFault {
        if (log.isDebugEnabled())
            log.debug("new HTTP External service, service name=[" + serviceName + "]; port name=[" + portName + "]");

        this.portName = portName;
        this.serviceName = serviceName;
        this.executorService = executorService;
        this.scheduler = scheduler;
        this.server = server;
        this.pconf = pconf;
        this.clusterUrlTransformer = clusterUrlTransformer;
        Definition definition = pconf.getDefinitionForService(serviceName);
        Service serviceDef = definition.getService(serviceName);
        if (serviceDef == null)
            throw new IllegalArgumentException(msgs.msgServiceDefinitionNotFound(serviceName));
        Port port = serviceDef.getPort(portName);
        if (port == null)
            throw new IllegalArgumentException(msgs.msgPortDefinitionNotFound(serviceName, portName));
        portBinding = port.getBinding();
        if (portBinding == null)
            throw new IllegalArgumentException(msgs.msgBindingNotFound(portName));

        // validate the http binding
        if (!WsdlUtils.useHTTPBinding(port)) {
            throw new IllegalArgumentException(msgs.msgNoHTTPBindingForPort(portName));
        }
        // throws an IllegalArgumentException if not valid
        new HttpBindingValidator(this.portBinding).validate();

        // initial endpoint reference
        Element eprElmt = ODEService.genEPRfromWSDL(definition, serviceName, portName);
        if (eprElmt == null)
            throw new IllegalArgumentException(msgs.msgPortDefinitionNotFound(serviceName, portName));
        endpointReference = EndpointFactory.convertToWSA(ODEService.createServiceRef(eprElmt));
        try {
            endpointUrl = new URL(endpointReference.getUrl());
        } catch (MalformedURLException e) {
            throw new OdeFault(e);
        }

        httpMethodConverter = new HttpMethodConverter(definition, serviceName, portName);
        connections = connManager;
    }

    public String getPortName() {
        return portName;
    }

    public QName getServiceName() {
        return serviceName;
    }

    public void close() {
        try {
            httpClient.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        httpClient = null;
    }

    public EndpointReference getInitialEndpointReference() {
        return endpointReference;
    }

    public void invoke(PartnerRoleMessageExchange odeMex) {
        if (log.isDebugEnabled()) log.debug("Preparing " + getClass().getSimpleName() + " invocation...");
        try {
            // note: don't make this map an instance attribute, so we always get the latest version
            final Map<String, String> properties = pconf.getEndpointProperties(endpointReference);
            // Handle this logic using the properties map and at the request building
            final Properties.HttpClient5.ConfigResult configResult = Properties.HttpClient5.translate(properties);

            // base baseUrl
            String mexEndpointUrl = ((MutableEndpoint) odeMex.getEndpointReference()).getUrl();
            String baseUrl = mexEndpointUrl;
            // The endpoint URL might be overridden from the properties file(s)
            // The order of precedence is (in descending order): process, property, wsdl.

            if(endpointUrl.equals(new URL(mexEndpointUrl))){
                String address = (String) configResult.getParams().get(Properties.PROP_ADDRESS);
                // directly pull from the properties hashmap instead of the params
                //String address =  properties.get(Properties.PROP_ADDRESS);
                if(address!=null) {
                    if (log.isDebugEnabled()) log.debug("Endpoint URL overridden by property files. "+mexEndpointUrl+" => "+address);
                    baseUrl = address;
                }
            }else{
                if (log.isDebugEnabled()) log.debug("Endpoint URL overridden by process. "+endpointUrl+" => "+mexEndpointUrl);
            }

            baseUrl = clusterUrlTransformer.rewriteOutgoingClusterURL(baseUrl);
            
            // build the request, handle the translate method related logic here
            final HttpUriRequestBase request = httpMethodConverter.createHttpRequest(odeMex, configResult, baseUrl);
            // create a client
            HttpClientConfig clientConfig = new HttpClientConfig();

            // configure the client (proxy, security, etc)
            Element message = odeMex.getRequest().getMessage();
            Element authenticatePart = message == null ? null : DOMUtils.findChildByName(message, new QName(null, "WWW-Authenticate"));
            HttpHelper.configure(clientConfig, request, authenticatePart, configResult);

            RequestConfig requestConfig = configResult.requestConfigBuilder.
                    setProxy(clientConfig.getProxy()).
                    setConnectionRequestTimeout(Timeout.ofMilliseconds(10000)).
                    setResponseTimeout(Timeout.ofMilliseconds(30000)).build();
            request.setConfig(requestConfig);
            HttpClientContext context = clientConfig.getContext();
            context.setRequestConfig(requestConfig);
            context.setCredentialsProvider(clientConfig.getCredentialsProvider());

            //if client is null, create client
            Collection<Header> headers = ((Collection) configResult.getParams().get(Properties.DEFAULT_HEADERS));
            if (httpClient == null) {
                httpClient = HttpClients.custom()
                        .setConnectionManager(connections)
                        .setDefaultHeaders(headers)
                        .build();
            }

            // this callable encapsulates the http method execution and the process of the response
            final Callable executionCallable;

            // execute it
            boolean isTwoWay = odeMex.getMessageExchangePattern() == MessageExchange.MessageExchangePattern.REQUEST_RESPONSE;
            if (isTwoWay) {
                // two way
                executionCallable = new HttpExternalService.TwoWayCallable(context, request, odeMex.getMessageExchangeId(), odeMex.getOperation());
                scheduler.registerSynchronizer(new Scheduler.Synchronizer() {
                    public void afterCompletion(boolean success) {
                        // If the TX is rolled back, then we don't send the request.
                        if (!success) return;
                        // The invocation must happen in a separate thread
                        executorService.submit(executionCallable);
                    }

                    public void beforeCompletion() {
                    }
                });
                odeMex.replyAsync();
            } else {
                // one way, just execute and forget
                executionCallable = new HttpExternalService.OneWayCallable(context, request, odeMex.getMessageExchangeId(), odeMex.getOperation());
                executorService.submit(executionCallable);
                odeMex.replyOneWayOk();
            }
        } catch (UnsupportedEncodingException e) {
            String errmsg = "The returned HTTP encoding isn't supported " + odeMex;
            log.error("[Service: " + serviceName + ", Port: " + portName + ", Operation: " + odeMex.getOperationName() + "] " + errmsg, e);
            odeMex.replyWithFailure(MessageExchange.FailureType.FORMAT_ERROR, errmsg, null);
        } catch (URISyntaxException e) {
            String errmsg = "Error sending message to " + getClass().getSimpleName() + " for ODE mex " + odeMex;
            log.error("[Service: " + serviceName + ", Port: " + portName + ", Operation: " + odeMex.getOperationName() + "] " + errmsg, e);
            odeMex.replyWithFailure(MessageExchange.FailureType.FORMAT_ERROR, errmsg, null);
        } catch (Exception e) {
            String errmsg = "Unknown HTTP call error for ODE mex " + odeMex;
            log.error("[Service: " + serviceName + ", Port: " + portName + ", Operation: " + odeMex.getOperationName() + "] " + errmsg, e);
            odeMex.replyWithFailure(MessageExchange.FailureType.OTHER, errmsg, null);
        }
    }

    private class OneWayCallable implements Callable<Void> {
        HttpRequest httpRequest;
        HttpResponse httpResponse;
        String mexId;
        Operation operation;
        CloseableHttpClient client;
        HttpClientContext context;

        public OneWayCallable(HttpClientContext context, HttpUriRequestBase request, String mexId, Operation operation) {
            this.httpRequest = request;
            this.mexId = mexId;
            this.operation = operation;
            this.httpResponse = null;
            this.context = context;
            this.client = httpClient;
        }

        public Void call() throws Exception {
            try {
                // simply execute the http method
                if (log.isDebugEnabled()) {
                    log.debug("Executing HTTP Request : " + httpRequest.getVersion() + " " + httpRequest.getUri());
                    log.debug(HttpHelper.requestToString((HttpUriRequestBase) httpRequest));
                }

                httpResponse = client.execute((ClassicHttpRequest) httpRequest, context);
                int status = httpResponse.getCode();

                // invoke getResponseBody to force the loading of the body
                // Actually the processResponse may happen in a separate thread and
                // as a result the connection might be closed before the body processing (see the finally clause below).
//                if(response instanceof HttpEntityContainer){
//                    HttpEntity entity = ((HttpEntityContainer) response).getEntity();
//                    if (entity != null) {
//                        byte[] responseBody = entity.toString().getBytes();
//
//                        if (log.isDebugEnabled()) {
//                            log.debug("Received response body for MEX " + mexId);
//                            log.debug(new String(responseBody));
//                        }
//                    }
//                } else {
//                    throw new IOException("Response is not an instance of HttpEntityContainer");
//                }
                //String responseBody = EntityUtils.toString(httpResponse.getEntity());

                // ... and process the response
                if (log.isDebugEnabled()) {
                    log.debug("Received response for MEX " + mexId);
                   // log.debug(HttpHelper.responseToString(response, (HttpUriRequestBase) request, responseBody));
                }
                processResponse(httpResponse.getCode());
            } catch (final IOException e) {
                // ODE MEX needs to be invoked in a TX.
                try {
                    scheduler.execTransaction(new Callable<Void>() {
                        public Void call() throws Exception {
                            PartnerRoleMessageExchange odeMex = (PartnerRoleMessageExchange) server.getEngine().getMessageExchange(mexId);
                            String errmsg = "Unable to execute http request : " + e.getMessage();
                            log.error("[Service: " + serviceName + ", Port: " + portName + ", Operation: " + operation.getName() + "] " + errmsg, e);
                            odeMex.replyWithFailure(MessageExchange.FailureType.COMMUNICATION_ERROR, errmsg, null);
                            return null;
                        }
                    });
                } catch (Exception e1) {
                    String errmsg = "[Service: " + serviceName + ", Port: " + portName + ", Operation: " + operation.getName() + "] Error executing reply transaction; reply will be lost.";
                    log.error(errmsg, e);
                }
            } finally {
                if(httpResponse != null) {
                    ((CloseableHttpResponse) httpResponse).close();
               }
            }
            return null;
        }

        public void processResponse(int statusCode) {
            // a one-way message does not care about the response
            try {
                // log the URI since the engine may have moved on while this One Way request was executing
                if (statusCode >= 400) {
                    log.error("OneWay HTTP Request failed, Status-Line: " + new StatusLine(httpResponse.getVersion(), httpResponse.getCode(), httpResponse.getReasonPhrase()).toString() + " for " + httpRequest.getUri());
                } else {
                    if (log.isDebugEnabled())
                        log.debug("OneWay HTTP Request, Status-Line: " + new StatusLine(httpResponse.getVersion(), httpResponse.getCode(), httpResponse.getReasonPhrase()).toString() + " for " + httpRequest.getUri());
                }
            } catch (Exception e) {
                String errmsg = "[Service: " + serviceName + ", Port: " + portName + ", Operation: " + operation.getName() + "] Exception occured while processing the HTTP response of a one-way request: " + e.getMessage();
                log.error(errmsg, e);
            } finally {
                try {
                    if(httpResponse != null)
                        ((CloseableHttpResponse) httpResponse).close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private class TwoWayCallable extends OneWayCallable {
        public TwoWayCallable(HttpClientContext context, HttpUriRequestBase request, String mexId, Operation operation) {
            super(context, request, mexId, operation);
        }

        public void processResponse(final int statusCode) {
            // ODE MEX needs to be invoked in a TX.
            try {
                scheduler.execTransaction(new Callable<Void>() {
                    public Void call() throws Exception {
                        try {
                            if (statusCode >= 200 && statusCode < 300) {
                                _2xx_success();
                            } else if (statusCode >= 300 && statusCode < 400) {
                                _3xx_redirection();
                            } else if (statusCode >= 400 && statusCode < 600) {
                                _4xx_5xx_error();
                            } else {
                                unmanagedStatus();
                            }
                        } catch (Exception e) {
                            replyWithFailure("Exception occured while processing the HTTP response of a two-way request. mexId= " + mexId, e);
                        }
                        return null;
                    }
                });
            } catch (Exception transactionException) {
                String errmsg = "[Service: " + serviceName + ", Port: " + portName + ", Operation: " + operation.getName() + "] Error executing reply transaction; reply will be lost.";
                log.error(errmsg, transactionException);
            } finally {
                try {
                    if(httpResponse != null)
                        ((CloseableHttpResponse) httpResponse).close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        private void unmanagedStatus() throws Exception {
            replyWithFailure("Unmanaged Status Code! Status-Line: " + new StatusLine(httpResponse.getVersion(), httpResponse.getCode(), httpResponse.getReasonPhrase()).toString()+ " for " + httpRequest.getUri());
        }

        private void _4xx_5xx_error() throws Exception {
            int status = httpResponse.getCode();
            if (HttpHelper.isFaultOrFailure(status) > 0) {
                // reply with a fault, meaning the request should not be repeated
                replyWithFault();
            } else {
                // reply with a failure, meaning the request might be repeated later
                replyWithFailure("HTTP Status-Line: " + new StatusLine(httpResponse.getVersion(), httpResponse.getCode(), httpResponse.getReasonPhrase()).toString() + " for " + httpRequest.getUri());
            }
        }

        private void _3xx_redirection() throws Exception {
            // redirections should be handled transparently by http-client
            replyWithFailure("Redirections disabled! HTTP Status-Line: " + new StatusLine(httpResponse.getVersion(), httpResponse.getCode(), httpResponse.getReasonPhrase()).toString() + " for " +  httpRequest.getUri());
        }

        private void _2xx_success() throws Exception {
            PartnerRoleMessageExchange odeMex = (PartnerRoleMessageExchange) server.getEngine().getMessageExchange(mexId);
            if (log.isDebugEnabled())
                log.debug("[Service: " + serviceName + ", Port: " + portName + ", Operation: " + operation.getName() + "] HTTP Status-Line: " +new StatusLine(httpResponse.getVersion(), httpResponse.getCode(), httpResponse.getReasonPhrase()).toString()+ " for " + httpRequest.getUri());
            if (log.isDebugEnabled()) log.debug("Received response for MEX " + odeMex);

            Operation opDef = odeMex.getOperation();

            // this is the message to populate and send to ODE
            QName outputMsgName = odeMex.getOperation().getOutput().getMessage().getQName();
            Message odeResponse = odeMex.createMessage(outputMsgName);

            httpMethodConverter.parseHttpResponse(odeResponse, httpResponse, opDef);

            // finally send the message
            try {
                if (log.isInfoEnabled())
                    log.info("Response: " + (odeResponse.getMessage() != null ? DOMUtils.domToString(odeResponse.getMessage()) : "empty"));
                odeMex.reply(odeResponse);
            } catch (Exception ex) {
                replyWithFailure("Unable to process response: " + ex.getMessage(), ex);
            }
        }

        void replyWithFault() throws ProtocolException {
            PartnerRoleMessageExchange odeMex = (PartnerRoleMessageExchange) server.getEngine().getMessageExchange(mexId);
            Object[] fault = httpMethodConverter.parseFault(odeMex, httpResponse);
            Message response = (Message) fault[1];
            QName faultName = (QName) fault[0];

            // finally send the fault. We did it!
            if (log.isWarnEnabled())
                log.warn("[Service: " + serviceName + ", Port: " + portName + ", Operation: " + operation.getName() + "] Fault response: faultName=" + faultName + " faultType=" + response.getType() + "\n" + DOMUtils.domToString(response.getMessage()));

            odeMex.replyWithFault(faultName, response);
        }


        void replyWithFailure(String errmsg) {
            replyWithFailure(errmsg, null);
        }

        void replyWithFailure(String errmsg, Throwable t) {
            log.error("[Service: " + serviceName + ", Port: " + portName + ", Operation: " + operation.getName() + "] " + errmsg, t);
            PartnerRoleMessageExchange odeMex = (PartnerRoleMessageExchange) server.getEngine().getMessageExchange(mexId);
            odeMex.replyWithFailure(MessageExchange.FailureType.OTHER, errmsg, HttpHelper.prepareDetailsElement(httpResponse));
        }
    }

    public static class HttpClientConfig {

        HttpHost proxy;
        BasicCredentialsProvider credentialsProvider;
        HttpRequest request;
        HttpClientContext context;

        public HttpClientConfig(){
            this.credentialsProvider = new BasicCredentialsProvider();
            this.context = HttpClientContext.create();
            this.proxy = null;
        }

        public HttpClientContext getContext() {
            return context;
        }

        public void setContext(HttpClientContext context) {
            this.context = context;
        }

        public HttpHost getProxy() {
            return proxy;
        }

        public void setProxy(HttpHost proxy) {
            this.proxy = proxy;
        }

        public BasicCredentialsProvider getCredentialsProvider() {
            return credentialsProvider;
        }
    }
}
