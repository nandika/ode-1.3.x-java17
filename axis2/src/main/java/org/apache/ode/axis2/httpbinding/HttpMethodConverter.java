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

import org.apache.commons.lang.StringUtils;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.classic.methods.*;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.http.message.StatusLine;
import org.apache.hc.core5.net.URIBuilder;
import org.apache.ode.utils.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.ode.axis2.util.URLEncodedTransformer;
import org.apache.ode.axis2.util.UrlReplacementTransformer;
import org.apache.ode.bpel.epr.MutableEndpoint;
import org.apache.ode.bpel.iapi.PartnerRoleMessageExchange;
import org.apache.ode.utils.DOMUtils;
import org.apache.ode.utils.Namespaces;
import org.apache.ode.utils.http.HttpUtils;
import static org.apache.ode.utils.http.HttpUtils.bodyAllowed;
import static org.apache.ode.utils.http.StatusCode._202_ACCEPTED;
import org.apache.ode.utils.wsdl.Messages;
import org.apache.ode.utils.wsdl.WsdlUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.wsdl.Binding;
import javax.wsdl.BindingInput;
import javax.wsdl.BindingOperation;
import javax.wsdl.BindingOutput;
import javax.wsdl.Definition;
import javax.wsdl.Fault;
import javax.wsdl.Message;
import javax.wsdl.Operation;
import javax.wsdl.Part;
import javax.wsdl.extensions.UnknownExtensibilityElement;
import javax.wsdl.extensions.http.HTTPOperation;
import javax.wsdl.extensions.mime.MIMEContent;
import javax.xml.namespace.QName;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class HttpMethodConverter {

    private static final Logger log = LoggerFactory.getLogger(HttpMethodConverter.class);

    protected static final Messages msgs = Messages.getMessages(Messages.class);
    public static final String FORM_URL_ENCODED_CONTENT_TYPE = "application/x-www-form-urlencoded";


    protected Definition definition;
    protected Binding binding;
    protected QName serviceName;
    protected String portName;

    public HttpMethodConverter(Definition definition, QName serviceName, String portName) {
        this.definition = definition;
        this.binding = definition.getService(serviceName).getPort(portName).getBinding();
        this.serviceName = serviceName;
        this.portName = portName;
    }


    public ClassicHttpRequest createHttpRequest(PartnerRoleMessageExchange odeMex, Properties.HttpClient5.ConfigResult configResult) throws UnsupportedEncodingException {
        return createHttpRequest(odeMex, configResult, ((MutableEndpoint) odeMex.getEndpointReference()).getUrl());
    }
    
    public HttpUriRequestBase createHttpRequest(PartnerRoleMessageExchange odeMex, Properties.HttpClient5.ConfigResult configResult, String baseUrl) throws UnsupportedEncodingException {
        Operation operation = odeMex.getOperation();
        BindingOperation bindingOperation = binding.getBindingOperation(operation.getName(), operation.getInput().getName(), operation.getOutput().getName());

        // message to be sent
        Element message = odeMex.getRequest().getMessage();
        Message msgDef = operation.getInput().getMessage();

        // extract part values into a map and check that all parts are assigned a value
        Map<String, Element> partElements = extractPartElements(msgDef, message);

        // http method type
        // the operation may override the verb, this is an extension for RESTful BPEL
        String verb = WsdlUtils.resolveVerb(binding, bindingOperation);

        // build the http method itself
        HttpUriRequestBase request = null;
        try {
            request = prepareHttpMethod(bindingOperation, verb, partElements, odeMex.getRequest().getHeaderParts(), baseUrl, configResult);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

        return request;
    }


    /**
     * create and initialize the http method.
     * Http Headers that may been passed in the params are not set in this method.
     * Headers will be automatically set by HttpClient.
     * See usages of HostParams.DEFAULT_HEADERS
     * See org.apache.commons.httpclient.HttpMethodDirector#executeMethod(org.apache.commons.httpclient.HttpMethod)
     */
    protected HttpUriRequestBase prepareHttpMethod(BindingOperation opBinding, String verb, Map<String, Element> partValues, Map<String, Node> headers,
                                           final String rootUri, Properties.HttpClient5.ConfigResult configResult) throws UnsupportedEncodingException, URISyntaxException {
        if (log.isDebugEnabled()) log.debug("Preparing http request...");
        // convenience variables...
        BindingInput bindingInput = opBinding.getBindingInput();
        HTTPOperation httpOperation = (HTTPOperation) WsdlUtils.getOperationExtension(opBinding);
        MIMEContent content = WsdlUtils.getMimeContent(bindingInput.getExtensibilityElements());
        String contentType = content == null ? null : content.getType();
        boolean useUrlEncoded = WsdlUtils.useUrlEncoded(bindingInput) || FORM_URL_ENCODED_CONTENT_TYPE.equalsIgnoreCase(contentType);
        boolean useUrlReplacement = WsdlUtils.useUrlReplacement(bindingInput);

        // the http method to be built and returned

        // the 4 elements the http method may be made of
        String relativeUri = httpOperation.getLocationURI();
        String queryPath = null;
        HttpEntity requestEntity;
        String encodedParams = null;

        // ODE supports uri template in both port and operation location.
        // so assemble the final url *before* replacement
        String completeUri = rootUri;
        if (StringUtils.isNotEmpty(relativeUri)) {
            completeUri = completeUri + (completeUri.endsWith("/") || relativeUri.startsWith("/") ? "" : "/") + relativeUri;
        }

        if (useUrlReplacement) {
            // insert part values in the url
            completeUri = new UrlReplacementTransformer().transform(completeUri, partValues);
        } else if (useUrlEncoded) {
            // encode part values
            encodedParams = new URLEncodedTransformer().transform(partValues);
        }
        if(log.isDebugEnabled()) {
            log.debug("completeUri {}, encodedParams {}", completeUri, encodedParams);
        }
        // http-client api is not really neat
        // something similar to the following would save some if/else manipulations.
        // But we have to deal with it as-is.
        //
        //  method = new Method(verb);
        //  method.setRequestEnity(..)
        //  etc...

        HttpUriRequestBase request = null;

        if ("GET".equalsIgnoreCase(verb) || "DELETE".equalsIgnoreCase(verb)) {
            URIBuilder uriBuilder = new URIBuilder(completeUri);
            if (useUrlEncoded && encodedParams != null) {
                for (String param : encodedParams.split("&")) {
                    String[] kv = param.split("=", 2);
                    uriBuilder.addParameter(URLDecoder.decode(kv[0], StandardCharsets.UTF_8.name()),
                            kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8.name()) : "");
                }
            }
            if ("GET".equalsIgnoreCase(verb)) {
                request = new HttpGet(uriBuilder.toString());
            } else if ("DELETE".equalsIgnoreCase(verb)) {
                request = new HttpDelete(uriBuilder.toString());
            }
            // Let http-client manage the redirection
            // see org.apache.commons.httpclient.params.HttpClientParams.MAX_REDIRECTS
            // default is 100
            //configResult.requestConfigBuilder.setRedirectsEnabled(true);
           // method.setFollowRedirects(true);
        } else if ("POST".equalsIgnoreCase(verb) || "PUT".equalsIgnoreCase(verb)) {
            HttpUriRequestBase entityRequest = null;

            //handle content chunking :TODO
            boolean chunked = false;
            Object object = configResult.getParams().get(Properties.PROP_HTTP_REQUEST_CHUNK);
            if(object != null) {
                chunked = Boolean.parseBoolean(object.toString());
            }

            if ("POST".equalsIgnoreCase(verb)) {
                entityRequest = new HttpPost(completeUri);

            } else if ("PUT".equalsIgnoreCase(verb)) {
                entityRequest = new HttpPut(completeUri);
            }
            String contentCharset = StandardCharsets.UTF_8.name();
            ContentType ctype= null;
            if(content != null) {
                ctype = ContentType.create(contentType,  contentCharset);
            }
            //method.getParams().setDefaults(params);
            // some body-building...
            //final String contentCharset = method.getParams().getContentCharset();
            if (log.isDebugEnabled()) log.debug("Content-Type [" + contentType + "] Charset [" + contentCharset + "]");
            if (useUrlEncoded) {
                encodedParams = new URLEncodedTransformer().transform(partValues);

                requestEntity = new StringEntity(encodedParams, ctype);
            } else {
                // get the part to be put in the body
                Part part = opBinding.getOperation().getInput().getMessage().getPart(content.getPart());
                Element partValue = partValues.get(part.getName());

                if (part.getElementName() == null) {
                    String errMsg = "XML Types are not supported. Parts must use elements.";
                    if (log.isErrorEnabled()) log.error(errMsg);
                    throw new RuntimeException(errMsg);
                } else if (HttpUtils.isXml(contentType)) {
                    if (log.isDebugEnabled()) log.debug("Content-Type [" + contentType + "] equivalent to 'text/xml'");
                    // stringify the first element
                    String xmlString = DOMUtils.domToString(DOMUtils.getFirstChildElement(partValue));
                    requestEntity = new StringEntity(xmlString, ctype);
                } else {
                    if (log.isDebugEnabled())
                        log.debug("Content-Type [" + contentType + "] NOT equivalent to 'text/xml'. The text content of part value will be sent as text");
                    // encoding conversion is managed by StringRequestEntity if necessary
                    requestEntity = new StringEntity(DOMUtils.getTextContent(partValue), ctype);
                }
            }
            entityRequest.setEntity(requestEntity);
            // cast safely, PUT and POST are subclasses of EntityEnclosingMethod
            //    final EntityEnclosingMethod enclosingMethod = (EntityEnclosingMethod) method;
            //    enclosingMethod.setRequestEntity(requestEntity);
            //    enclosingMethod.setContentChunked(params.getBooleanParameter(Properties.PROP_HTTP_REQUEST_CHUNK, false));
            request = entityRequest;
        } else {
            // should not happen because of HttpBindingValidator, but never say never
            throw new IllegalArgumentException("Unsupported HTTP method: " + verb);
        }

        // this is handled when creating the request
        // method.setPath(completeUri); // assumes that the path is properly encoded (URL safe).
        // method.setQueryString(queryPath);
        setHttpRequestHeaders(request, opBinding, partValues, headers, configResult);
        return request;
    }

    public void translate(Map<String, String> endpointProperties, ClassicHttpRequest request) {

    }
    /**
     * First go through the list of default headers set in the method params. This param is then remove to avoid interference with HttpClient.
     * Actually the default headers should be overriden by any headers set from the process.
     * Not to mention that, for a given header, HttpClient do not overwrite any previous values but simply append the default value.<br/>
     *  See {@link //see org.apache.commons.httpclient.params.HostParams.DEFAULT_HEADERS}
     * <p/>
     * Then go through the list of message headers and set them if empty.
     * <p/>
     * Finally go through the list of {@linkplain //Namespaces.ODE_HTTP_EXTENSION_NS}{@code :header} elements included in the input binding.
     * For each of them, set the HTTP Request Header with the static value defined by the attribute {@linkplain //Namespaces.ODE_HTTP_EXTENSION_NS}{@code :value},
     * or the part value mentioned in the attribute {@linkplain //Namespaces.ODE_HTTP_EXTENSION_NS}{@code :part}.
     * <p/>
     * Finally, set the 'Accept' header if the output content type of the operation exists.
     * <p/>
     * Notice that the last header value overrides any values set previoulsy. Meaning that message headers might get overriden by parts bound to headers.
     *
     */
    public void setHttpRequestHeaders(ClassicHttpRequest request, BindingOperation opBinding, Map<String, Element> partValues, Map<String, Node> headers, Properties.HttpClient5.ConfigResult configResult)  {
        BindingInput inputBinding = opBinding.getBindingInput();
        Message inputMessage = opBinding.getOperation().getInput().getMessage();

        // Do not let HttpClient manage the default headers
        // Actually the default headers should be overriden by any headers set from the process.
        // (Not to mention that, for a given header, HttpClient do not overwrite any previous values but simply append the default value)
        // configure default headers at requestConfig
        Collection defaultHeaders = (Collection) configResult.getParams().get(Properties.DEFAULT_HEADERS);
        if (defaultHeaders != null) {
            for(Object header : defaultHeaders) {
                if (header instanceof Header) {
                    Header h = (Header) header;
                    request.setHeader(h.getName(), h.getValue());
                }
            }
        }

        // process message headers
        for (Iterator<Map.Entry<String, Node>> iterator = headers.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<String, Node> e = iterator.next();
            String headerName = e.getKey();
            Node headerNode = e.getValue();
            String headerValue = DOMUtils.domToString(headerNode);
            request.setHeader(headerName, HttpHelper.replaceCRLFwithLWS(headerValue));
        }

        // process parts that are bound to message parts
        Collection<UnknownExtensibilityElement> headerBindings = WsdlUtils.getHttpHeaders(inputBinding.getExtensibilityElements());
        for (Iterator<UnknownExtensibilityElement> iterator = headerBindings.iterator(); iterator.hasNext();) {
            Element binding = iterator.next().getElement();
            String headerName = binding.getAttribute("name");
            String partName = binding.getAttribute("part");
            String value = binding.getAttribute("value");

            /* Header binding may use a part or a static value */
            String headerValue;
            if (StringUtils.isNotEmpty(partName)) {
                // 'part' attribute is used
                // get the part to be put in the header
                Part part = inputMessage.getPart(partName);
                Element partWrapper = partValues.get(part.getName());
                if (DOMUtils.isEmptyElement(partWrapper)) {
                    headerValue = "";
                } else {
                    /*
                    The expected part value could be a simple type
                    or an element of a simple type.
                    So if a element is there, take its text content
                    else take the text content of the part element itself
                    */
                    Element childElement = DOMUtils.getFirstChildElement(partWrapper);
                    if (childElement != null) {
                        if (DOMUtils.getFirstChildElement(childElement) != null) {
                            String errMsg = "Complex types are not supported. Header Parts must be simple types or elements of a simple type.";
                            if (log.isErrorEnabled()) log.error(errMsg);
                            throw new RuntimeException(errMsg);
                        } else {
                            headerValue = DOMUtils.getTextContent(childElement);
                        }
                    } else {
                        headerValue = DOMUtils.getTextContent(partWrapper);
                    }
                }
            } else if (StringUtils.isNotEmpty(value)) {
                // 'value' attribute is used, this header is a static value
                headerValue = value;
            } else {
                String errMsg = "Invalid binding: missing attribute! Expecting " + new QName(Namespaces.ODE_HTTP_EXTENSION_NS, "part") + " or " + new QName(Namespaces.ODE_HTTP_EXTENSION_NS, "value");
                if (log.isErrorEnabled()) log.error(errMsg);
                throw new RuntimeException(errMsg);
            }
            // do not set the header isf the value is empty
            if (StringUtils.isNotEmpty(headerValue))
                request.setHeader(headerName, HttpHelper.replaceCRLFwithLWS(headerValue));
        }

        BindingOutput outputBinding = opBinding.getBindingOutput();
        if(outputBinding !=null) {
            MIMEContent outputContent = WsdlUtils.getMimeContent(outputBinding.getExtensibilityElements());
            // set Accept header if output content type is set
            if (outputContent != null) {
                request.setHeader("Accept", outputContent.getType());
            }
        }
    }


    protected Map<String, Element> extractPartElements(Message msgDef, Element message) {
        if (msgDef.getParts().size() != 0 && message == null) {
            throw new IllegalArgumentException(msgs.msgOdeMessageExpected());
        }
        Map<String, Element> partValues = new HashMap<String, Element>();
        for (Iterator iterator = msgDef.getParts().values().iterator(); iterator.hasNext();) {
            Part part = (Part) iterator.next();
            Element partEl = DOMUtils.findChildByName(message, new QName(null, part.getName()));
            if (partEl == null)
                throw new IllegalArgumentException(msgs.msgOdeMessageMissingRequiredPart(part.getName()));
            partValues.put(part.getName(), partEl);
        }
        return partValues;
    }


    /**
     * Create the element to be associated with this part into the {@link org.apache.ode.bpel.iapi.Message}.
     * <br/>An element named with the part name will be returned. the content of this element depends on the part.
     * <p/>If the part has a non-null element name, a new element will be created and named accordingly then the text value is inserted in this new element.
     * <br/>else the given text content is simply set on the part element.
     *
     * @param part
     * @param textContent
     * @return an element named with the part name will be returned
     */
    public Element createPartElement(Part part, String textContent) {
        Document doc = DOMUtils.newDocument();
        Element partElement = doc.createElementNS(null, part.getName());
        if (part.getElementName() != null) {
            Element element = doc.createElementNS(part.getElementName().getNamespaceURI(), part.getElementName().getLocalPart());
            element.setTextContent(textContent);
            partElement.appendChild(element);
        } else {
            partElement.setTextContent(textContent);
        }
        return partElement;
    }


    /**
     * Create the element to be associated with this part into the {@link org.apache.ode.bpel.iapi.Message}.
     * <p/>If the part has a non-null element name, the bodyElement is simply appended.
     * Else if the bodyElement has a text content, the value is set to the message.
     * Else append all nodes of bodyElement to the returned element. Attributes are ignored.
     * <p/>
     * The name of the returned element is the part name.
     *
     * @param part
     * @param receivedElement
     * @return the element to insert "as is" to ODE message
     */
    public Element createPartElement(Part part, Element receivedElement) {
        Document doc = DOMUtils.newDocument();
        Element partElement = doc.createElementNS(null, part.getName());
        if (part.getElementName() != null) {
            partElement.appendChild(doc.importNode(receivedElement, true));
        } else {
            if (DOMUtils.isEmptyElement(receivedElement)) {
                // Append an empty text node.
                // Warning! setting an empty string with setTextContent has not effect. See javadoc.
                partElement.appendChild(doc.createTextNode(""));
            } else {
                // No need to make the distinction between simple and complex types, importNode will handle it
                // !!! Attributes are ignored
                for (int m = 0; m < receivedElement.getChildNodes().getLength(); m++) {
                    Node child = receivedElement.getChildNodes().item(m);
                    partElement.appendChild(doc.importNode(child, true));
                }
            }
        }
        return partElement;
    }

    /**
     * Process the HTTP Response Headers.
     * <p/>
     * First go through the list of {@linkplain //Namespaces.ODE_HTTP_EXTENSION_NS}{@code :header} elements included in the output binding.
     * For each of them, set the header value as the value of the message part.
     * <p/>
     * Then add all HTTP headers as header part in the message. The name of the header would be the part name.
     * <p/>
     * Finally, insert a header names 'Status-Line'. This header contains an element as returned by {@link //HttpHelper#statusLineToElement(String)} .
     *
     * @param odeMessage
     * @param //method
     * @param operationDef
     */
    public void extractHttpResponseHeaders(org.apache.ode.bpel.iapi.Message odeMessage, HttpResponse response, Operation operationDef) throws ProtocolException {
        Message messageDef = operationDef.getOutput().getMessage();

        BindingOutput outputBinding = binding.getBindingOperation(operationDef.getName(), operationDef.getInput().getName(), operationDef.getOutput().getName()).getBindingOutput();
        Collection<UnknownExtensibilityElement> headerBindings = WsdlUtils.getHttpHeaders(outputBinding.getExtensibilityElements());

        // iterate through the list of header bindings
        // and set the message parts accordingly
        for (Iterator<UnknownExtensibilityElement> iterator = headerBindings.iterator(); iterator.hasNext();) {
            Element binding = iterator.next().getElement();
            String partName = binding.getAttribute("part");
            String headerName = binding.getAttribute("name");

            Part part = messageDef.getPart(partName);
            if (StringUtils.isNotEmpty(partName)) {
                //Header responseHeader = getCondensedHeader(response, headerName);
                Header responseHeader = response.getFirstHeader(headerName);
                    if (responseHeader != null) {
                        odeMessage.setPart(partName, createPartElement(part, responseHeader.getValue()));
                    }
            } else {
                String errMsg = "Invalid binding: missing required attribute! Part name: " + new QName(Namespaces.ODE_HTTP_EXTENSION_NS, "part");
                if (log.isErrorEnabled()) log.error(errMsg);
                throw new RuntimeException(errMsg);
            }
        }

        // add all HTTP response headers (in their condensed form) into the message as header parts
        Set<String> headerNames = new HashSet<String>();
        if(response!= null) {
            for (Header header : response.getHeaders())
                headerNames.add(header.getName());
            for (String hname : headerNames)
                odeMessage.setHeaderPart(hname, response.getFirstHeader(hname).getValue());

            // make the status line information available as a single element
            odeMessage.setHeaderPart("Status-Line", HttpHelper.statusLineToElement(
                    new StatusLine(response.getVersion(), response.getCode(), response.getReasonPhrase())));
        }
    }

    public void parseHttpResponse(org.apache.ode.bpel.iapi.Message odeResponse, HttpResponse response, Operation opDef) throws SAXException, IOException, ProtocolException {
        BindingOperation opBinding = binding.getBindingOperation(opDef.getName(), opDef.getInput().getName(), opDef.getOutput().getName());
        /* process headers */
        extractHttpResponseHeaders(odeResponse, response, opDef);

        /* process the body if any */

        // assumption is made that a response may have at most one body. HttpBindingValidator checks this.
        MIMEContent outputContent = WsdlUtils.getMimeContent(opBinding.getBindingOutput().getExtensibilityElements());
        int status = response.getCode();

        boolean xmlExpected = outputContent != null && HttpUtils.isXml(outputContent.getType());
        // '202/Accepted' and '204/No Content' status codes explicitly state that there is no body, so we should not fail even if a part is bound to the body response
        boolean isBodyExpected = outputContent != null;
        boolean isBodyMandatory = isBodyExpected && bodyAllowed(status) && status != _202_ACCEPTED;
        String body = null;
        if(response instanceof HttpEntityContainer) {
            HttpEntityContainer entityContainer = (HttpEntityContainer) response;
            if(entityContainer.getEntity() != null && entityContainer.getEntity().getContentLength() > 0){
                body = EntityUtils.toString(entityContainer.getEntity());
            } else {
                body ="";
            }
        }

        final boolean emptyBody = StringUtils.isEmpty(body);
        if (emptyBody) {
            if (isBodyMandatory) {
                throw new RuntimeException("Response body is mandatory but missing!");
            }
        } else {
            if (isBodyExpected) {
                Part partDef = opDef.getOutput().getMessage().getPart(outputContent.getPart());
                Element partElement;

                if (xmlExpected) {

                    Header h = response.getHeader("Content-Type");
                    String receivedType = h != null ? h.getValue() : null;
                    boolean contentTypeSet = receivedType != null;
                    boolean xmlReceived = contentTypeSet && HttpUtils.isXml(receivedType);

                    // a few checks
                    if (!contentTypeSet) {
                        if (log.isDebugEnabled())
                            log.debug("Received Response with a body but no 'Content-Type' header!");
                    } else if (!xmlReceived) {
                        if (log.isDebugEnabled())
                            log.debug("Xml type was expected but non-xml type received! Expected Content-Type=" + outputContent.getType() + " Received Content-Type=" + receivedType);
                    }

                    // parse the body and create the message part
                    Element bodyElement = DOMUtils.stringToDOM(body);
                    partElement = createPartElement(partDef, bodyElement);
                } else {
                    // if not xml, process it as text
                    partElement = createPartElement(partDef, body);
                }

                // set the part
                odeResponse.setPart(partDef.getName(), partElement);

            } else {
                // the body was not expected but we don't know how to deal with it
                if (log.isDebugEnabled()) log.debug("Body received but not mapped to any part! Body=\n" + body);
            }
        }
    }

    public Object[] parseFault(PartnerRoleMessageExchange odeMex, HttpResponse response) throws ProtocolException {
        Operation opDef = odeMex.getOperation();
        BindingOperation opBinding = binding.getBindingOperation(opDef.getName(), opDef.getInput().getName(), opDef.getOutput().getName());
        String body = null;
        if(response instanceof HttpEntityContainer) {
            HttpEntityContainer entityContainer = (HttpEntityContainer) response;
            try {
                body = EntityUtils.toString(entityContainer.getEntity());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        Header h = response.getHeader("Content-Type");
        String receivedType = h != null ? h.getValue() : null;
        if (opDef.getFaults().isEmpty()) {
            throw new RuntimeException("Operation [" + opDef.getName() + "] has no fault. This " + response.getCode() + " error will be considered as a failure.");
        } else if (opBinding.getBindingFaults().isEmpty()) {
            throw new RuntimeException("No fault binding. This " + response.getCode() + " error will be considered as a failure.");
        } else if (StringUtils.isEmpty(body)) {
            throw new RuntimeException("No body in the response. This " + response.getCode() + " error will be considered as a failure.");
        } else if (receivedType != null && !HttpUtils.isXml(receivedType)) {
            throw new RuntimeException("Response Content-Type [" + receivedType + "] does not describe XML entities. Faults must be XML. This " + response.getCode() + " error will be considered as a failure.");
        } else {

            if (receivedType == null) {
                if (log.isWarnEnabled())
                    log.warn("[Service: " + serviceName + ", Port: " + portName + ", Operation: " + opDef.getName() + "] Received Response with a body but no 'Content-Type' header! Will try to parse nevertheless.");
            }

            // try to parse body
            final Element bodyElement;
            try {
                bodyElement = DOMUtils.stringToDOM(body);
            } catch (Exception e) {
                throw new RuntimeException("Unable to parse the response body as xml. This " + response.getCode() + " error will be considered as a failure.", e);
            }

            // Guess which fault it is
            QName bodyName = new QName(bodyElement.getNamespaceURI(), bodyElement.getNodeName());
            Fault faultDef = WsdlUtils.inferFault(opDef, bodyName);

            if (faultDef == null) {
                throw new RuntimeException("Unknown Fault Type [" + bodyName + "] This " + response.getCode() + " error will be considered as a failure.");
            } else if (!WsdlUtils.isOdeFault(opBinding.getBindingFault(faultDef.getName()))) {
                // is this fault bound with ODE extension?
                throw new RuntimeException("Fault [" + bodyName + "] is not bound with " + new QName(Namespaces.ODE_HTTP_EXTENSION_NS, "fault") + ". This " + response.getCode() + " error will be considered as a failure.");
            } else {
                // a fault has only one part
                Part partDef = (Part) faultDef.getMessage().getParts().values().iterator().next();

                QName faultName = new QName(definition.getTargetNamespace(), faultDef.getName());
                QName faultType = faultDef.getMessage().getQName();

                // create the ODE Message now that we know the fault
                org.apache.ode.bpel.iapi.Message msgResponse = odeMex.createMessage(faultType);

                // build the element to be sent back
                Element partElement = createPartElement(partDef, bodyElement);
                msgResponse.setPart(partDef.getName(), partElement);

                // extract and set headers
                extractHttpResponseHeaders(msgResponse, response, opDef);
                return new Object[]{faultName, msgResponse};
            }
        }
    }

    public Header getCondensedHeader(HttpResponse response, String name) {
        Header[] headers = response.getHeaders(name);
        if (headers.length == 0) {
            return null;
        } else if (headers.length == 1) {
            return new BasicHeader(headers[0].getName(), headers[0].getValue());
        } else {
            StringBuffer valueBuffer = new StringBuffer(headers[0].getValue());

            for (int i = 1; i < headers.length; i++) {
                valueBuffer.append(", ");
                valueBuffer.append(headers[i].getValue());
            }
            return new BasicHeader(name.toLowerCase(Locale.ENGLISH), valueBuffer.toString());
        }
    }
}

