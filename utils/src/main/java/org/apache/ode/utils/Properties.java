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

package org.apache.ode.utils;

import org.apache.axis2.Constants;
import org.apache.axis2.addressing.AddressingConstants;
import org.apache.axis2.util.JavaUtils;
import org.apache.axis2.client.Options;
import org.apache.axis2.kernel.http.HTTPConstants;
import org.apache.axis2.transport.http.HttpTransportProperties;
import org.apache.axis2.transport.jms.JMSConstants;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.http.*;

import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * @author <a href="mailto:midon@intalio.com">Alexis Midon</a>
 */
public class Properties {

    /**
     * Property used to define how long (in milliseconds) the message will wait for a response. Default value is {@link #DEFAULT_MEX_TIMEOUT}
     */
    public static final String PROP_MEX_TIMEOUT = "mex.timeout";

    /**
     * Property used to define how long (in milliseconds) the message will wait for a response for process-to-process invocations.
     */
    public static final String PROP_P2P_MEX_TIMEOUT = "p2p.mex.timeout";

    // its default value
    public static final int DEFAULT_MEX_TIMEOUT = 2 * 60 * 1000;

    public static final String PROP_HTTP_CONNECTION_TIMEOUT = "http.connection.timeout";
    public static final String PROP_HTTP_SOCKET_TIMEOUT = "http.socket.timeout";
    public static final String PROP_HTTP_PROTOCOL_VERSION = "http.protocol.version";
    public static final String PROP_HTTP_HEADER_PREFIX = "http.default-headers.";
    public static final String PROP_HTTP_PROXY_PREFIX = "http.proxy.";
    public static final String PROP_HTTP_PROXY_HOST = PROP_HTTP_PROXY_PREFIX + "host";
    public static final String PROP_HTTP_PROXY_PORT = PROP_HTTP_PROXY_PREFIX + "port";
    public static final String PROP_HTTP_PROXY_DOMAIN = PROP_HTTP_PROXY_PREFIX + "domain";
    public static final String PROP_HTTP_PROXY_USER = PROP_HTTP_PROXY_PREFIX + "user";
    public static final String PROP_HTTP_PROXY_PASSWORD = PROP_HTTP_PROXY_PREFIX + "password";
    /**
     * @deprecated use org.apache.commons.httpclient.params.HttpMethodParams#HTTP_CONTENT_CHARSET (="http.protocol.content-charset")
     */
    public static final String PROP_HTTP_PROTOCOL_ENCODING = "http.protocol.encoding";

    /**
     * Property to override the location set in soap:address or http:address
     */
    public static final String PROP_ADDRESS = "address";

    // Httpclient specific
    public static final String PROP_HTTP_MAX_REDIRECTS = "http.protocol.max-redirects";

    // Axis2-specific
    public static final String PROP_HTTP_REQUEST_CHUNK = "http.request.chunk";
    public static final String PROP_HTTP_REQUEST_GZIP = "http.request.gzip";
    public static final String PROP_HTTP_ACCEPT_GZIP = "http.accept.gzip";
    public static final String PROP_SECURITY_POLICY = "security.policy.file";
    public static final String PROP_JMS_REPLY_DESTINATION = "jms.reply.destination";
    public static final String PROP_JMS_REPLY_TIMEOUT = "jms.reply.timeout";
    public static final String PROP_JMS_DESTINATION_TYPE = "jms.destination.type";
    public static final String PROP_SEND_WS_ADDRESSING_HEADERS = "ws-addressing.headers";

    public static final String HTTP_CONTENT_CHARSET = "http.protocol.content-charset";
    public static final String DEFAULT_HEADERS = "http.default-headers";
    public static final String CONNECTION_TIMEOUT = "http.connection.timeout";
    public static final String SO_TIMEOUT = "http.socket.timeout";
    public static final String PROTOCOL_VERSION = "http.protocol.version";
    public static final String MAX_REDIRECTS = "http.protocol.max-redirects";

    protected static final Logger log = LoggerFactory.getLogger(Properties.class);

    public static Object[] getProxyAndHeaders(Map<String, String> properties) {
        ArrayList<Header> headers = null; // /!\ Axis2 requires an ArrayList (not a List implementation)
        HttpTransportProperties.ProxyProperties proxy = null;
        for (Map.Entry<String, String> e : properties.entrySet()) {
            final String k = e.getKey();
            final String v = e.getValue();
            if (k.startsWith(PROP_HTTP_HEADER_PREFIX)) {
                if (headers == null) headers = new ArrayList<Header>();
                // extract the header name
                String name = k.substring(PROP_HTTP_HEADER_PREFIX.length());
                headers.add(new BasicHeader(name, v));
            } else if (k.startsWith(PROP_HTTP_PROXY_PREFIX)) {
                if (proxy == null) proxy = new HttpTransportProperties.ProxyProperties();

                if (PROP_HTTP_PROXY_HOST.equals(k)) proxy.setProxyName(v);
                else if (PROP_HTTP_PROXY_PORT.equals(k)) proxy.setProxyPort(Integer.parseInt(v));
                else if (PROP_HTTP_PROXY_DOMAIN.equals(k)) proxy.setDomain(v);
                else if (PROP_HTTP_PROXY_USER.equals(k)) proxy.setUserName(v);
                else if (PROP_HTTP_PROXY_PASSWORD.equals(k)) proxy.setPassWord(v);
                else if (log.isWarnEnabled())
                    log.warn("Unknown proxy properties [" + k + "]. " + PROP_HTTP_PROXY_PREFIX + " is a refix reserved for proxy properties.");
            }
        }
        if (proxy != null) {
            String host = proxy.getProxyHostName();
            if (host == null || host.length() == 0) {
                // disable proxy if the host is not null
                proxy = null;
                if (log.isDebugEnabled()) log.debug("Proxy host is null. Proxy will not be taken into account.");
            }
        }

        return new Object[]{proxy, headers};
    }

    public static class Axis2 {

        public static Options translate(Map<String, String> properties) {
            return translate(properties, new Options());
        }

        public static Options translate(Map<String, String> properties, Options options) {
            if (log.isDebugEnabled()) log.debug("Translating Properties for Axis2");
            if (properties.isEmpty()) return options;

            // First set any default values to make sure they can be overwriten
            // set the default encoding for HttpClient (HttpClient uses ISO-8859-1 by default)
            options.setProperty(Constants.Configuration.CHARACTER_SET_ENCODING, "UTF-8");

            /*then add all property pairs so that new properties (with string value)
                are automatically handled (i.e no translation needed) */
            for (Map.Entry<String, String> e : properties.entrySet()) {
                options.setProperty(e.getKey(), e.getValue());
            }
            if (properties.containsKey(PROP_HTTP_CONNECTION_TIMEOUT)) {
                final String value = properties.get(PROP_HTTP_CONNECTION_TIMEOUT);
                try {
                    options.setProperty(HTTPConstants.CONNECTION_TIMEOUT, Integer.valueOf(value));
                } catch (NumberFormatException e) {
                    if (log.isWarnEnabled())
                        log.warn("Mal-formatted Property: [" + Properties.PROP_HTTP_CONNECTION_TIMEOUT + "=" + value + "]. Integer expected. Property will be skipped.");
                }
            }
            if (properties.containsKey(PROP_HTTP_SOCKET_TIMEOUT)) {
                final String value = properties.get(PROP_HTTP_SOCKET_TIMEOUT);
                try {
                    options.setProperty(HTTPConstants.SO_TIMEOUT, Integer.valueOf(value));
                } catch (NumberFormatException e) {
                    if (log.isWarnEnabled())
                        log.warn("Mal-formatted Property: [" + Properties.PROP_HTTP_SOCKET_TIMEOUT + "=" + value + "]. Integer expected. Property will be skipped.");
                }
            }
            if (properties.containsKey(PROP_HTTP_PROTOCOL_ENCODING)) {
                if(log.isWarnEnabled())log.warn("Deprecated property: http.protocol.encoding. Use http.protocol.content-charset");
                options.setProperty(Constants.Configuration.CHARACTER_SET_ENCODING, properties.get(PROP_HTTP_PROTOCOL_ENCODING));
            }
            if (properties.containsKey(HTTP_CONTENT_CHARSET)) {
                options.setProperty(Constants.Configuration.CHARACTER_SET_ENCODING, properties.get(HTTP_CONTENT_CHARSET));
            }
            if (properties.containsKey(PROP_HTTP_PROTOCOL_VERSION)) {
                options.setProperty(HTTPConstants.HTTP_PROTOCOL_VERSION, properties.get(PROP_HTTP_PROTOCOL_VERSION));
            }
            if (properties.containsKey(PROP_HTTP_REQUEST_CHUNK)) {
                options.setProperty(HTTPConstants.CHUNKED, properties.get(PROP_HTTP_REQUEST_CHUNK));
            }
            if (properties.containsKey(PROP_HTTP_REQUEST_GZIP)) {
                options.setProperty(HTTPConstants.MC_GZIP_REQUEST, properties.get(PROP_HTTP_REQUEST_GZIP));
            }
            if (properties.containsKey(PROP_HTTP_ACCEPT_GZIP)) {
                options.setProperty(HTTPConstants.MC_ACCEPT_GZIP, properties.get(PROP_HTTP_ACCEPT_GZIP));
            }
            if (properties.containsKey(PROP_HTTP_MAX_REDIRECTS)) {
                if (log.isWarnEnabled()) log.warn("Property Not Supported: " + PROP_HTTP_MAX_REDIRECTS);
            }
            if (properties.containsKey(PROP_JMS_REPLY_DESTINATION)) {
                options.setProperty(JMSConstants.PARAM_REPLY_DESTINATION, properties.get(PROP_JMS_REPLY_DESTINATION));
            }
            if (properties.containsKey(PROP_JMS_REPLY_TIMEOUT)) {
                String value = properties.get(PROP_JMS_REPLY_TIMEOUT);
                options.setProperty(JMSConstants.JMS_WAIT_REPLY, value);
                // The value of this property must be a string object, not a long object. 
//                try {
//                    options.setProperty(JMSConstants.JMS_WAIT_REPLY, Long.valueOf(value));
//                } catch (NumberFormatException e) {
//                    if (log.isWarnEnabled())
//                        log.warn("Mal-formatted Property: [" + Properties.PROP_JMS_REPLY_TIMEOUT + "=" + value + "]. Long expected. Property will be skipped.");
//                }
            }
            if (properties.containsKey(PROP_JMS_DESTINATION_TYPE)) {
                String value = properties.get(PROP_JMS_DESTINATION_TYPE);
                try {
                    options.setProperty(JMSConstants.PARAM_DEST_TYPE, Long.valueOf(value));
                } catch (NumberFormatException e) {
                    if (log.isWarnEnabled())
                        log.warn("Mal-formatted Property: [" + Properties.PROP_JMS_DESTINATION_TYPE + "=" + value + "]. Long expected. Property will be skipped.");
                }
            }
            if(properties.containsKey(PROP_SEND_WS_ADDRESSING_HEADERS)){
                String value = properties.get(PROP_SEND_WS_ADDRESSING_HEADERS);
                options.setProperty(AddressingConstants.DISABLE_ADDRESSING_FOR_OUT_MESSAGES, !Boolean.parseBoolean(value));
            }
            if (properties.containsKey("ws-adddressing.headers")) {
                if(log.isWarnEnabled())log.warn("Deprecated property: ws-adddressing.headers (Mind the 3 d's). Use ws-addressing.headers");                
                String value = properties.get("ws-adddressing.headers");
                options.setProperty(AddressingConstants.DISABLE_ADDRESSING_FOR_OUT_MESSAGES, !Boolean.parseBoolean(value));
            }

            // iterate through the properties to get Headers & Proxy information
            Object[] o = getProxyAndHeaders(properties);
            HttpTransportProperties.ProxyProperties proxy = (HttpTransportProperties.ProxyProperties) o[0];
            ArrayList<Header> headers = (ArrayList<Header>) o[1]; // /!\ Axis2 requires an ArrayList (not a List implementation)
            if (headers != null && !headers.isEmpty()) options.setProperty(HTTPConstants.HTTP_HEADERS, headers);
            if (proxy != null) options.setProperty(HTTPConstants.PROXY, proxy);

            // Set properties that canNOT be overridden
            if(JavaUtils.isTrueExplicitly(options.getProperty(HTTPConstants.REUSE_HTTP_CLIENT))){
                if (log.isWarnEnabled()) log.warn("This property cannot be overidden, and must always be false. "+ HTTPConstants.REUSE_HTTP_CLIENT);
            }
            options.setProperty(HTTPConstants.REUSE_HTTP_CLIENT, "false");
            return options;
        }
    }
    public static class HttpClient5 {

        public static class ConfigResult {
            public RequestConfig.Builder requestConfigBuilder;
            public List<Header> headers;
           // public HttpTransportProperties.ProxyProperties proxy;

            public ConfigResult(RequestConfig.Builder builder, List<Header> headers) {
                this.requestConfigBuilder = builder;
                this.headers = headers;
            }
        }

        public static ConfigResult translate(Map<String, String> properties) {
            RequestConfig.Builder configBuilder = RequestConfig.custom();
            List<Header> headers = new ArrayList<>();

            // Set default encoding
            headers.add(new BasicHeader(HttpHeaders.CONTENT_ENCODING, "UTF-8"));

            // Generic properties
            for (Map.Entry<String, String> e : properties.entrySet()) {
                String key = e.getKey();
                String value = e.getValue();

                switch (key) {
                    case Properties.PROP_HTTP_CONNECTION_TIMEOUT:
                        try {
                            configBuilder.setConnectTimeout(Timeout.ofMilliseconds(Integer.parseInt(value)));
                        } catch (NumberFormatException ex) {
                            log.warn("Invalid value for connection timeout: " + value, ex);
                        }
                        break;

                    case Properties.PROP_HTTP_SOCKET_TIMEOUT:
                        try {
                            configBuilder.setResponseTimeout(Timeout.ofMilliseconds(Integer.parseInt(value)));
                        } catch (NumberFormatException ex) {
                            log.warn("Invalid value for socket timeout: " + value, ex);
                        }
                        break;

                    case Properties.PROP_HTTP_PROTOCOL_ENCODING:
                    case HTTP_CONTENT_CHARSET:
                        headers.add(new BasicHeader(HttpHeaders.CONTENT_ENCODING, value));
                        break;

                    case Properties.PROP_HTTP_PROTOCOL_VERSION:
                        try {
                            ProtocolVersion version = HttpVersion.parse(value);
                            // HttpClient 5 does not use protocol version directly in config
                            // You must set it on the HttpRequest if needed
                            log.warn("Protocol version config ignored in HttpClient 5. Set per request.");
                        } catch (Exception ex) {
                            log.warn("Invalid protocol version: " + value, ex);
                        }
                        break;

                    case Properties.PROP_HTTP_REQUEST_CHUNK:
                        // Controlled at the entity/request level
                        log.warn("Chunked transfer encoding should be set at the HttpEntity/request level in HttpClient 5.");
                        break;

                    case Properties.PROP_HTTP_REQUEST_GZIP:
                    case Properties.PROP_HTTP_ACCEPT_GZIP:
                        log.warn("GZIP compression is not automatically handled. Use GZIP compression manually.");
                        break;

                    case Properties.PROP_HTTP_MAX_REDIRECTS:
                        try {
                            int redirects = Integer.parseInt(value);
                            configBuilder.setMaxRedirects(redirects);
                        } catch (NumberFormatException ex) {
                            log.warn("Invalid max redirects: " + value, ex);
                        }
                        break;

                    default:
                        headers.add(new BasicHeader(key, value));
                        break;
                }
            }

            // Handle proxy and headers (assuming you have a utility like before)
//            Object[] proxyAndHeaders = getProxyAndHeaders(properties);
//            HttpTransportProperties.ProxyProperties proxy = (HttpTransportProperties.ProxyProperties) proxyAndHeaders[0];
//            Collection<?> customHeaders = (Collection<?>) proxyAndHeaders[1];
//
//            if (customHeaders != null) {
//                for (Object obj : customHeaders) {
//                    if (obj instanceof Header) {
//                        headers.add((Header) obj);
//                    }
//                }
//            }

            return new ConfigResult(configBuilder, headers);
        }

    }

    public static class HttpClient {
        public static HttpParams translate(Map<String, String> properties) {
            //return translate(properties, new DefaultHttpParams());
            HashMap<String, String> map = new HashMap<>();
            // Populate default http param properties
            return translate(properties, null);
        }


        public static HttpParams translate(Map<String, String> properties, HttpParams p) {
            if (log.isDebugEnabled())
                log.debug("Translating Properties for HttpClient. Properties size=" + properties.size());
            if (properties.isEmpty()) return p;

            // First set any default values to make sure they can be overwriten
            // set the default encoding for HttpClient (HttpClient uses ISO-8859-1 by default)
            p.setParameter(HTTP_CONTENT_CHARSET, "UTF-8");

            /*then all property pairs so that new properties (with string value)
             are automatically handled (i.e no translation needed) */
            for (Map.Entry<String, String> e : properties.entrySet()) {
                p.setParameter(e.getKey(), e.getValue());
            }

            // initialize the collection of headers
            p.setParameter(DEFAULT_HEADERS, new ArrayList());

            if (properties.containsKey(PROP_HTTP_CONNECTION_TIMEOUT)) {
                final String value = properties.get(PROP_HTTP_CONNECTION_TIMEOUT);
                try {
                    p.setParameter(CONNECTION_TIMEOUT, Integer.valueOf(value));
                } catch (NumberFormatException e) {
                    if (log.isWarnEnabled())
                        log.warn("Mal-formatted Property: [" + Properties.PROP_HTTP_CONNECTION_TIMEOUT + "=" + value + "] Property will be skipped.");
                }
            }
            if (properties.containsKey(PROP_HTTP_SOCKET_TIMEOUT)) {
                final String value = properties.get(PROP_HTTP_SOCKET_TIMEOUT);
                try {
                    p.setParameter(SO_TIMEOUT, Integer.valueOf(value));
                } catch (NumberFormatException e) {
                    if (log.isWarnEnabled())
                        log.warn("Mal-formatted Property: [" + Properties.PROP_HTTP_SOCKET_TIMEOUT + "=" + value + "] Property will be skipped.");
                }
            }

            if (properties.containsKey(PROP_HTTP_PROTOCOL_ENCODING)) {
                if(log.isWarnEnabled())log.warn("Deprecated property: http.protocol.encoding. Use http.protocol.content-charset");
                p.setParameter(HTTP_CONTENT_CHARSET, properties.get(PROP_HTTP_PROTOCOL_ENCODING));
            }
            // the next one is redundant because HttpMethodParams.HTTP_CONTENT_CHARSET accepts a string and we use the same property name
            // so the property has already been added.
            if (properties.containsKey(HTTP_CONTENT_CHARSET)) {
                p.setParameter(HTTP_CONTENT_CHARSET, properties.get(HTTP_CONTENT_CHARSET));
            }

            if (properties.containsKey(PROP_HTTP_PROTOCOL_VERSION)) {
                try {
                    p.setParameter(PROTOCOL_VERSION, HttpVersion.parse(properties.get(PROP_HTTP_PROTOCOL_VERSION)));
                } catch (ProtocolException e) {
                    if (log.isWarnEnabled())

                    
                        log.warn("Mal-formatted Property: [" + PROP_HTTP_PROTOCOL_VERSION + "]", e);
                }
            }
            if (properties.containsKey(PROP_HTTP_REQUEST_CHUNK)) {
                // see org.apache.commons.httpclient.methods.EntityEnclosingMethod.setContentChunked()
                p.setBooleanParameter(PROP_HTTP_REQUEST_CHUNK, Boolean.parseBoolean(properties.get(PROP_HTTP_REQUEST_CHUNK)));
            }
            if (properties.containsKey(PROP_HTTP_REQUEST_GZIP)) {
                if (log.isWarnEnabled())
                    log.warn("Property not supported by HTTP External Services: " + PROP_HTTP_REQUEST_GZIP);
            }

            if (Boolean.parseBoolean(properties.get(PROP_HTTP_ACCEPT_GZIP))) {
                // append gzip to the list of accepted encoding
                // HttpClient does not support compression natively
                // Additional code would be necessary to handle it.
//                ((Collection) p.getParameter(HostParams.DEFAULT_HEADERS)).add(new Header("Accept-Encoding", "gzip"));
                if (log.isWarnEnabled())
                    log.warn("Property not supported by HTTP External Services: " + PROP_HTTP_ACCEPT_GZIP);
            }

            if (properties.containsKey(PROP_HTTP_MAX_REDIRECTS)) {
                final String value = properties.get(PROP_HTTP_MAX_REDIRECTS);
                try {
                    p.setParameter(MAX_REDIRECTS, Integer.valueOf(value));
                } catch (NumberFormatException e) {
                    if (log.isWarnEnabled())
                        log.warn("Mal-formatted Property: [" + Properties.PROP_HTTP_MAX_REDIRECTS + "=" + value + "] Property will be skipped.");
                }
            }

            Object[] o = getProxyAndHeaders(properties);
            HttpTransportProperties.ProxyProperties proxy = (HttpTransportProperties.ProxyProperties) o[0];
            Collection headers = (Collection) o[1];
            if (headers != null && !headers.isEmpty())
                ((Collection) p.getParameter(DEFAULT_HEADERS)).addAll(headers);
            if (proxy != null) p.setParameter(PROP_HTTP_PROXY_PREFIX, proxy);

            return new UnmodifiableHttpParams(p);
        }



        static class UnmodifiableHttpParams implements HttpParams {

            final HttpParams p;

            private UnmodifiableHttpParams(HttpParams p) {
                this.p = p;
            }

            public void setBooleanParameter(String name, boolean value) {
                throw new UnsupportedOperationException();
            }

            public void setDefaults(HttpParams params) {
                throw new UnsupportedOperationException();
            }

            public void setDoubleParameter(String name, double value) {
                throw new UnsupportedOperationException();
            }

            public void setIntParameter(String name, int value) {
                throw new UnsupportedOperationException();
            }

            public void setLongParameter(String name, long value) {
                throw new UnsupportedOperationException();
            }

            public void setParameter(String name, Object value) {
                throw new UnsupportedOperationException();
            }

            public boolean getBooleanParameter(String name, boolean defaultValue) {
                return p.getBooleanParameter(name, defaultValue);
            }

            public HttpParams getDefaults() {
                return null;
            }

            public double getDoubleParameter(String name, double defaultValue) {
                return p.getDoubleParameter(name, defaultValue);
            }

            public int getIntParameter(String name, int defaultValue) {
                return p.getIntParameter(name, defaultValue);
            }

            public long getLongParameter(String name, long defaultValue) {
                return p.getLongParameter(name, defaultValue);
            }

            public Object getParameter(String name) {
                return p.getParameter(name);
            }

            public boolean isParameterFalse(String name) {
                return p.isParameterFalse(name);
            }

            public boolean isParameterSet(String name) {
                return p.isParameterSet(name);
            }

            public boolean isParameterSetLocally(String name) {
                return p.isParameterSetLocally(name);
            }

            public boolean isParameterTrue(String name) {
                return p.isParameterTrue(name);
            }
        }
        public interface HttpParams {

            /**
             * Returns the parent collection that this collection will defer to
             * for a default value if a particular parameter is not explicitly
             * set in the collection itself
             *
             * @return the parent collection to defer to, if a particular parameter
             * is not explictly set in the collection itself.
             *
             * @see #setDefaults(HttpParams)
             */
            public HttpParams getDefaults();

            /**
             * Assigns the parent collection that this collection will defer to
             * for a default value if a particular parameter is not explicitly
             * set in the collection itself
             *
             * @param params the parent collection to defer to, if a particular
             * parameter is not explictly set in the collection itself.
             *
             * @see #getDefaults()
             */
            public void setDefaults(final HttpParams params);

            /**
             * Returns a parameter value with the given name. If the parameter is
             * not explicitly defined in this collection, its value will be drawn
             * from a higer level collection at which this parameter is defined.
             * If the parameter is not explicitly set anywhere up the hierarchy,
             * <tt>null</tt> value is returned.
             *
             * @param name the parent name.
             *
             * @return an object that represents the value of the parameter.
             *
             * @see #setParameter(String, Object)
             */
            public Object getParameter(final String name);

            /**
             * Assigns the value to the parameter with the given name
             *
             * @param name parameter name
             * @param value parameter value
             */
            public void setParameter(final String name, final Object value);

            /**
             * Returns a {@link Long} parameter value with the given name.
             * If the parameter is not explicitly defined in this collection, its
             * value will be drawn from a higer level collection at which this parameter
             * is defined. If the parameter is not explicitly set anywhere up the hierarchy,
             * the default value is returned.
             *
             * @param name the parent name.
             * @param defaultValue the default value.
             *
             * @return a {@link Long} that represents the value of the parameter.
             *
             * @see #setLongParameter(String, long)
             */
            public long getLongParameter(final String name, long defaultValue);

            /**
             * Assigns a {@link Long} to the parameter with the given name
             *
             * @param name parameter name
             * @param value parameter value
             */
            public void setLongParameter(final String name, long value);

            /**
             * Returns an {@link Integer} parameter value with the given name.
             * If the parameter is not explicitly defined in this collection, its
             * value will be drawn from a higer level collection at which this parameter
             * is defined. If the parameter is not explicitly set anywhere up the hierarchy,
             * the default value is returned.
             *
             * @param name the parent name.
             * @param defaultValue the default value.
             *
             * @return a {@link Integer} that represents the value of the parameter.
             *
             * @see #setIntParameter(String, int)
             */
            public int getIntParameter(final String name, int defaultValue);

            /**
             * Assigns an {@link Integer} to the parameter with the given name
             *
             * @param name parameter name
             * @param value parameter value
             */
            public void setIntParameter(final String name, int value);

            /**
             * Returns a {@link Double} parameter value with the given name.
             * If the parameter is not explicitly defined in this collection, its
             * value will be drawn from a higer level collection at which this parameter
             * is defined. If the parameter is not explicitly set anywhere up the hierarchy,
             * the default value is returned.
             *
             * @param name the parent name.
             * @param defaultValue the default value.
             *
             * @return a {@link Double} that represents the value of the parameter.
             *
             * @see #setDoubleParameter(String, double)
             */
            public double getDoubleParameter(final String name, double defaultValue);

            /**
             * Assigns a {@link Double} to the parameter with the given name
             *
             * @param name parameter name
             * @param value parameter value
             */
            public void setDoubleParameter(final String name, double value);

            /**
             * Returns a {@link Boolean} parameter value with the given name.
             * If the parameter is not explicitly defined in this collection, its
             * value will be drawn from a higer level collection at which this parameter
             * is defined. If the parameter is not explicitly set anywhere up the hierarchy,
             * the default value is returned.
             *
             * @param name the parent name.
             * @param defaultValue the default value.
             *
             * @return a {@link Boolean} that represents the value of the parameter.
             *
             * @see #setBooleanParameter(String, boolean)
             */
            public boolean getBooleanParameter(final String name, boolean defaultValue);

            /**
             * Assigns a {@link Boolean} to the parameter with the given name
             *
             * @param name parameter name
             * @param value parameter value
             */
            public void setBooleanParameter(final String name, boolean value);

            /**
             * Returns <tt>true</tt> if the parameter is set at any level, <tt>false</tt> otherwise.
             *
             * @param name parameter name
             *
             * @return <tt>true</tt> if the parameter is set at any level, <tt>false</tt>
             * otherwise.
             */
            public boolean isParameterSet(final String name);

            /**
             * Returns <tt>true</tt> if the parameter is set locally, <tt>false</tt> otherwise.
             *
             * @param name parameter name
             *
             * @return <tt>true</tt> if the parameter is set locally, <tt>false</tt>
             * otherwise.
             */
            public boolean isParameterSetLocally(final String name);

            /**
             * Returns <tt>true</tt> if the parameter is set and is <tt>true</tt>, <tt>false</tt>
             * otherwise.
             *
             * @param name parameter name
             *
             * @return <tt>true</tt> if the parameter is set and is <tt>true</tt>, <tt>false</tt>
             * otherwise.
             */
            public boolean isParameterTrue(final String name);

            /**
             * Returns <tt>true</tt> if the parameter is either not set or is <tt>false</tt>,
             * <tt>false</tt> otherwise.
             *
             * @param name parameter name
             *
             * @return <tt>true</tt> if the parameter is either not set or is <tt>false</tt>,
             * <tt>false</tt> otherwise.
             */
            public boolean isParameterFalse(final String name);

        }
    }
}
