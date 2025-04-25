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

import org.apache.axis2.transport.http.HttpTransportProperties;
import org.apache.hc.client5.http.auth.AuthCache;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.CredentialsProvider;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.auth.BasicAuthCache;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.auth.BasicScheme;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.http.message.StatusLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.lang.StringUtils;
import org.apache.ode.utils.Properties;
import org.apache.ode.utils.DOMUtils;
import org.apache.ode.utils.http.HttpUtils;
import static org.apache.ode.utils.http.StatusCode.*;
import org.w3c.dom.Element;
import org.w3c.dom.Document;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Collections;

public class HttpHelper {

    private static final Logger log = LoggerFactory.getLogger(HttpHelper.class);



    public static void configure(HttpExternalService.HttpClientConfig clientConfig, ClassicHttpRequest request, Element authPart, Properties.HttpClient5.ConfigResult configResult) throws URISyntaxException {
        if (log.isDebugEnabled()) log.debug("Configuring http client...");

        /* Do not forget to wire params so that endpoint properties are passed around
           Down the road, when the request will be executed, the hierarchy of parameters will be the following:
             (-> means "is parent of")
             default params -> params from endpoint properties -> HttpClient -> HostConfig -> Method
           This wiring is done by HttpClient.
        */
        //clientBuilder.setDefaultHeaders(Collections.EMPTY_LIST);

        //client.getParams().setDefaults(params);

        // Here we make sure HttpClient will not handle the default headers. 
        // Actually HttpClient *appends* default headers while we want them to be ignored if the process assign them 
        //client.getParams().setParameter(HostParams.DEFAULT_HEADERS, Collections.EMPTY_LIST);

         HttpTransportProperties.ProxyProperties proxy = configResult.proxy;


        if (ProxyConf.isProxyEnabled((null != proxy), request.getUri().getHost())) {
            if (log.isDebugEnabled()) log.debug("ProxyConf");
            // handle proxy logic from properties.translate() method
            ProxyConf.configure(clientConfig, proxy);
        }

        // security
        // ...

        // authentication
        /*
        We're expecting the following element:
        <xs:complexType name="credentialType">
            <xs:attribute name="scheme" type="xs:string" default="server-decide" />
            <xs:attribute name="username" type="xs:string" />
            <xs:attribute name="password" type="xs:string" />
        </xs:complexType>
        <xs:element type="rest_connector:credentialType" name="credentials" />
         */
        if (authPart != null) {
            // the part must be defined with an element, so take the fist child
            Element credentialsElement = DOMUtils.getFirstChildElement(authPart);
            if (credentialsElement != null && credentialsElement.getAttributes().getLength() != 0) {
                String scheme = DOMUtils.getAttribute(credentialsElement, "scheme");
                String username = DOMUtils.getAttribute(credentialsElement, "username");
                String password = DOMUtils.getAttribute(credentialsElement, "password");

                if (scheme != null
                        && !"server-decides".equalsIgnoreCase(scheme)
                        && !"basic".equalsIgnoreCase(scheme)
                        && !"digest".equalsIgnoreCase(scheme)) {
                    throw new IllegalArgumentException("Unknown Authentication scheme: [" + scheme + "] Accepted values are: Basic, Digest, Server-Decides");
                } else {
                    if(log.isDebugEnabled()) log.debug("credentials provided: scheme="+ scheme+" user="+ username +" password=********");
                    BasicCredentialsProvider credentialsProvider = clientConfig.getCredentialsProvider();
                    credentialsProvider.setCredentials(
                            new AuthScope(null, request.getUri().getHost(), request.getUri().getPort(), null, scheme),
                            new UsernamePasswordCredentials(username, password.toCharArray()));

                    // If Basic, add preemptive interceptor
                    // save one round trip if basic
                    //   client.getParams().setAuthenticationPreemptive("basic".equalsIgnoreCase(scheme));
                    if("basic".equalsIgnoreCase(scheme)){
                        HttpHost target = new HttpHost(request.getUri().getHost(), request.getUri().getPort());
                        AuthCache authCache = new BasicAuthCache();
                        authCache.put(target, new BasicScheme());
                        HttpClientContext context = clientConfig.getContext();
                        context.setAuthCache(authCache);
                    }
                }
            }
        }
    }

    /**
     * Parse and convert a HTTP status line into an aml element.
     *
     * @param statusLine
     * @return
     * @throws HttpException
     * @see #statusLineToElement(org.w3c.dom.Document, org.apache.commons.httpclient.StatusLine)
     */
//    public static Element statusLineToElement
//            (String statusLine) throws HttpException {
//        return statusLineToElement(new StatusLine(statusLine));
//    }

    public static Element statusLineToElement
            (StatusLine statusLine) {
        return statusLineToElement(DOMUtils.newDocument(), statusLine);
    }

    /**
     * Convert a <a href="http://www.w3.org/Protocols/rfc2616/rfc2616-sec6.html#sec6.1">HTTP status line</a> into an xml element like this:
     * <p/>
     * < Status-line>
     * < HTTP-Version>HTTP/1.1< /HTTP-Version>
     * < Status-Code>200< /Status-Code>
     * < Reason-Phrase>Success - The action was successfully received, understood, and accepted< /Reason-Phrase>
     * < /Status-line></br>
     *
     * @param statusLine - the {@link org.apache.commons.httpclient.StatusLine} instance to be converted
     * @param doc        - the document to use to create new nodes
     * @return an Element
     */
    public static Element statusLineToElement (Document doc, StatusLine statusLine) {
        Element statusLineEl = doc.createElementNS(null, "Status-Line");
        Element versionEl = doc.createElementNS(null, "HTTP-Version");
        Element codeEl = doc.createElementNS(null, "Status-Code");
        Element reasonEl = doc.createElementNS(null, "Reason-Phrase");
        Element originalEl = doc.createElementNS(null, "original");

        // wiring
        doc.appendChild(statusLineEl);
        statusLineEl.appendChild(versionEl);
        statusLineEl.appendChild(codeEl);
        statusLineEl.appendChild(reasonEl);
        statusLineEl.appendChild(originalEl);

        // values
        versionEl.setTextContent(statusLine.getProtocolVersion().toString());
        codeEl.setTextContent(String.valueOf(statusLine.getStatusCode()));
        reasonEl.setTextContent(statusLine.getReasonPhrase());
        // the line as received, not parsed
        originalEl.setTextContent(statusLine.toString());

        return statusLineEl;
    }

    /**
     * Build a "details" element that looks like this:
     *
     * @param method
     * @return
     * @throws IOException
     */
//    public static Element prepareDetailsElement
//            (HttpMethod
//                    method) {
//        Header h = method.getResponseHeader("Content-Type");
//        String receivedType = h != null ? h.getValue() : null;
//        boolean bodyIsXml = receivedType != null && HttpUtils.isXml(receivedType);
//
//
//        Document doc = DOMUtils.newDocument();
//        Element detailsEl = doc.createElementNS(null, "details");
//        Element statusLineEl = statusLineToElement(doc, method.getStatusLine());
//        detailsEl.appendChild(statusLineEl);
//
//        // set the body if any
//        try {
//            final String body = method.getResponseBodyAsString();
//            if (StringUtils.isNotEmpty(body)) {
//                Element bodyEl = doc.createElementNS(null, "responseBody");
//                detailsEl.appendChild(bodyEl);
//                // first, try to parse the body as xml
//                // if it fails, put it as string in the body element
//                boolean exceptionDuringParsing = false;
//                if (bodyIsXml) {
//                    try {
//                        Element parsedBodyEl = DOMUtils.stringToDOM(body);
//                        bodyEl.appendChild(doc.importNode(parsedBodyEl, true));
//                    } catch (Exception e) {
//                        String errmsg = "Unable to parse the response body as xml. Body will be inserted as string.";
//                        if (log.isDebugEnabled()) log.debug(errmsg, e);
//                        exceptionDuringParsing = true;
//                    }
//                }
//                if (!bodyIsXml || exceptionDuringParsing) {
//                    bodyEl.setTextContent(body);
//                }
//            }
//        } catch (IOException e) {
//            if (log.isWarnEnabled()) log.warn("Exception while loading response body", e);
//        }
//        return detailsEl;
//    }

    public static Element prepareDetailsElement(HttpResponse response) {

        // Get Content-Type header
        Header h = response.getFirstHeader("Content-Type");
        String receivedType = h != null ? h.getValue() : null;
        boolean bodyIsXml = receivedType != null && HttpUtils.isXml(receivedType);

        // Create DOM document and details element
        Document doc = DOMUtils.newDocument();
        Element detailsEl = doc.createElementNS(null, "details");

        // Add status line
        Element statusLineEl = statusLineToElement(doc, new StatusLine(response));
        detailsEl.appendChild(statusLineEl);

        // Set the body if any
        if (response instanceof org.apache.hc.core5.http.HttpEntityContainer) {
            HttpEntityContainer entityResponse = (HttpEntityContainer) response;
            try {
                String body = EntityUtils.toString(entityResponse.getEntity(), "UTF-8");
                if (StringUtils.isNotEmpty(body)) {
                    Element bodyEl = doc.createElementNS(null, "responseBody");
                    detailsEl.appendChild(bodyEl);
                    // Try to parse the body as XML; if it fails, insert as string
                    boolean exceptionDuringParsing = false;
                    if (bodyIsXml) {
                        try {
                            Element parsedBodyEl = DOMUtils.stringToDOM(body);
                            bodyEl.appendChild(doc.importNode(parsedBodyEl, true));
                        } catch (Exception e) {
                            String errmsg = "Unable to parse the response body as xml. Body will be inserted as string.";
                            if (log.isDebugEnabled()) {
                                log.debug(errmsg, e);
                            }
                            exceptionDuringParsing = true;
                        }
                    }
                    if (!bodyIsXml || exceptionDuringParsing) {
                        bodyEl.setTextContent(body);
                    }
                }
            } catch (IOException e) {
                if (log.isWarnEnabled()) {
                    log.warn("Exception while loading response body", e);
                }
            } catch (ParseException e) {
                throw new RuntimeException(e);
            } finally {
                // Ensure entity is consumed to prevent resource leaks
                try {
                    EntityUtils.consume(entityResponse.getEntity());
                } catch (IOException e) {
                    if (log.isWarnEnabled()) {
                        log.warn("Failed to consume response entity", e);
                    }
                }
            }
        }

        return detailsEl;
    }

    private static final Pattern NON_LWS_PATTERN = Pattern.compile("\r\n([^\\s])");

    /**
     * This method ensures that a header value containing CRLF does not mess up the HTTP request.
     * Actually CRLF is the end-of-line marker for headers.
     * <p/>
     * To do so, all CRLF followed by a non-whitespace character are replaced by CRLF HT.
     * <p/>
     * This is possible because the
     * <a href="http://www.w3.org/Protocols/rfc2616/rfc2616-sec2.html#sec2.2">Section 2.2</a> of HTTP standard (RFC2626) states that:
     * <p/>
     * <quote>
     * HTTP/1.1 header field values can be folded onto multiple lines if the
     * continuation line begins with a space or horizontal tab. All linear
     * white space, including folding, has the same semantics as SP. A
     * recipient MAY replace any linear white space with a single SP before
     * interpreting the field value or forwarding the message downstream.
     * <p/>
     * LWS            = [CRLF] 1*( SP | HT )
     * <p/>
     * </quote>
     * <p/>
     * FYI, HttpClient 3.x.x does not check this.
     *
     * @param header
     * @return the string properly ready to be used as an HTTP header field-content
     */
    public static String replaceCRLFwithLWS
            (String
                    header) {
        Matcher m = NON_LWS_PATTERN.matcher(header);
        StringBuffer sb = new StringBuffer(header.length());
        while (m.find()) {
            m.appendReplacement(sb, "\r\n\t");
            sb.append(m.group(1));
        }
        m.appendTail(sb);
        return sb.toString();
    }

//    public static String requestToString( m) {
//        StringBuilder sb = new StringBuilder(256);
//        try {
//            sb.append("HTTP Request Details: \n").append(m.getName()).append(" ").append(m.getURI());
//        } catch (URIException e) {
//            // not that important
//            if (log.isDebugEnabled()) log.debug("",e);
//        }
//        sb.append("\nRequest Headers:");
//        Header[] headers = m.getRequestHeaders();
//        if (headers.length == 0) sb.append(" n/a");
//        for (int i = 0; i < headers.length; i++) {
//            Header h = headers[i];
//            sb.append("\n\t").append(h.getName()).append(": ").append(h.getValue());
//        }
//        if (m instanceof EntityEnclosingMethod) {
//            EntityEnclosingMethod eem = (EntityEnclosingMethod) m;
//            if (eem.getRequestEntity() != null) {
//                sb.append("\nRequest Entity:");
//                sb.append("\n\tContent-Type:").append(eem.getRequestEntity().getContentType());
//                sb.append("\n\tContent-Length:").append(eem.getRequestEntity().getContentLength());
//                if (eem.getRequestEntity() instanceof StringRequestEntity) {
//                    StringRequestEntity sre = (StringRequestEntity) eem.getRequestEntity();
//                    sb.append("\n\tContent-Charset:").append(sre.getCharset());
//                    sb.append("\n\tRequest Entity:\n").append(sre.getContent());
//                }
//            }
//        }
//        return sb.toString();
//    }
public static String requestToString(HttpUriRequestBase request) throws URISyntaxException {
    StringBuilder sb = new StringBuilder(256);

    // — Request line
    sb.append("HTTP Request Details:\n")
            .append(request.getMethod())
            .append(" ")
            .append(request.getUri());

    // — Request headers
    sb.append("\nRequest Headers:");
    Header[] headers = request.getHeaders();
    if (headers.length == 0) {
        sb.append(" n/a");
    } else {
        for (Header h : headers) {
            sb.append("\n\t")
                    .append(h.getName())
                    .append(": ")
                    .append(h.getValue());
        }
    }

    // — Request entity (if any)
    if (request instanceof HttpEntityContainer) {
        HttpEntity entity = ((HttpEntityContainer) request).getEntity();  // :contentReference[oaicite:0]{index=0}
        if (entity != null) {
            sb.append("\nRequest Entity:");
            ContentType contentType = ContentType.parse(entity.getContentType());
            sb.append("\n\tContent-Type:").append(contentType != null ? contentType.toString() : "n/a");
            sb.append("\n\tContent-Length:").append(entity.getContentLength());

            if (entity instanceof StringEntity) {
                StringEntity sre = (StringEntity) entity;
                sb.append("\n\tContent-Charset:").append(contentType != null && contentType.getCharset() != null ? contentType.getCharset().name() : "n/a");
                String ct = ((StringEntity) entity).getContentType();
                // pull out the body text
                try {
                    String body = EntityUtils.toString(entity);
                    sb.append("\n\tRequest Entity:\n").append(body);
                } catch (IOException | ParseException ioe) {
                    log.error("Error reading request entity", ioe);
                }
            }
        }
    }
    return sb.toString();
}

//    public static String responseToString (HttpMethod m) {
//        StringBuilder sb = new StringBuilder(256);
//        try {
//            sb.append("HTTP Response Details: \n").append(m.getName()).append(" ").append(m.getURI());
//        } catch (URIException e) {
//            // not that important
//            if (log.isDebugEnabled()) log.debug("",e);
//        }
//        sb.append("\nStatus-Line: ").append(m.getStatusLine());
//        Header[] headers = m.getResponseHeaders();
//        if (headers.length != 0) sb.append("\nResponse Headers: ");
//        for (int i = 0; i < headers.length; i++) {
//            Header h = headers[i];
//            sb.append("\n\t").append(h.getName()).append(": ").append(h.getValue());
//        }
//        try {
//            if (StringUtils.isNotEmpty(m.getResponseBodyAsString())) {
//                sb.append("\nResponse Entity:\n").append(m.getResponseBodyAsString());
//            }
//        } catch (IOException e) {
//            log.error("",e);
//        }
//        Header[] footers = m.getResponseFooters();
//        if (footers.length != 0) sb.append("\nResponse Footers: ");
//        for (int i = 0; i < footers.length; i++) {
//            Header h = footers[i];
//            sb.append("\n\t").append(h.getName()).append(": ").append(h.getValue());
//        }
//        return sb.toString();
//    }

    public static String responseToString(ClassicHttpResponse response, HttpUriRequestBase request, String responseBody) {
        StringBuilder sb = new StringBuilder(256);
        try {
            sb.append("HTTP Response Details: \n")
                    .append(request.getMethod()).append(" ").append(request.getUri());
        } catch (Exception e) {
            if (log.isDebugEnabled()) log.debug("Error getting URI", e);
        }

        String statusLine = (response.getVersion() != null ? response.getVersion().toString() : "HTTP/1.1")
                + " " + response.getCode() + " " + response.getReasonPhrase();
        sb.append("\nStatus-Line: ").append(statusLine);

        Header[] headers = response.getHeaders();
        if (headers.length != 0) sb.append("\nResponse Headers:");
        for (Header h : headers) {
            sb.append("\n\t").append(h.getName()).append(": ").append(h.getValue());
        }

//        HttpEntity entity = response.getEntity();
//        if (entity != null) {
//            try {
//                String body = EntityUtils.toString(entity);
//                if (StringUtils.isNotEmpty(body)) {
//                    sb.append("\nResponse Entity:\n").append(body);
//                }
//            } catch (IOException e) {
//                log.error("Error reading response body", e);
//            } catch (ParseException e) {
//                throw new RuntimeException(e);
//            }
//        }

        // HttpClient 5 does not support footers directly like Commons HttpClient did.
        // Footers are usually used in chunked encoding, but are not exposed by default.
        // You can remove this part or log a note instead:
        sb.append("\nResponse Footers: Not available in HttpClient 5");

        return sb.toString();
    }
    /**
     * @param s, the status code to test, must be in [400, 600[
     * @return 1 if fault, -1 if failure, 0 if undetermined
     */
    public static int isFaultOrFailure (int s) {
        if (s < 400 || s >= 600) {
            throw new IllegalArgumentException("Status-Code must be in interval [400;600[");
        }
        if (s == _500_INTERNAL_SERVER_ERROR
                || s == _501_NOT_IMPLEMENTED
                || s == _502_BAD_GATEWAY
                || s == _505_HTTP_VERSION_NOT_SUPPORTED
                || s == _400_BAD_REQUEST
                || s == _402_PAYMENT_REQUIRED
                || s == _403_FORBIDDEN
                || s == _404_NOT_FOUND
                || s == _405_METHOD_NOT_ALLOWED
                || s == _406_NOT_ACCEPTABLE
                || s == _407_PROXY_AUTHENTICATION_REQUIRED
                || s == _409_CONFLICT
                || s == _410_GONE
                || s == _412_PRECONDITION_FAILED
                || s == _413_REQUEST_TOO_LONG
                || s == _414_REQUEST_URI_TOO_LONG
                || s == _415_UNSUPPORTED_MEDIA_TYPE
                || s == _411_LENGTH_REQUIRED
                || s == _416_REQUESTED_RANGE_NOT_SATISFIABLE
                || s == _417_EXPECTATION_FAILED) {
            return 1;
        } else if (s == _503_SERVICE_UNAVAILABLE
                || s == _504_GATEWAY_TIMEOUT
                || s == _401_UNAUTHORIZED
                || s == _408_REQUEST_TIMEOUT) {
            return -1;
        } else {
            return 0;
        }
    }
}
