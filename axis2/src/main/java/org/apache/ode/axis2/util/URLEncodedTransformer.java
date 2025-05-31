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

package org.apache.ode.axis2.util;

import org.apache.commons.codec.net.URLCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.ode.utils.DOMUtils;
import org.w3c.dom.Element;

import java.io.UnsupportedEncodingException;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

/**
 * @author <a href="mailto:midon@intalio.com">Alexis Midon</a>
 */
public class URLEncodedTransformer {

    /** Default content encoding chatset */
    private static final String DEFAULT_CHARSET = "ISO-8859-1";
    private static final Logger log = LoggerFactory.getLogger(URLEncodedTransformer.class);

    /**
     * @param values - a map<String, Element>, the key is a part name (without curly braces), the value the replacement value for the part name. If the value is not a simple type, it will be skipped.
     * @return the encoded params
     */
    public String transform(Map<String, Element> values) {
        if (values.isEmpty()) return null;
        List<NameValuePair> l = new ArrayList<NameValuePair>(values.size());
        for (Map.Entry<String, Element> e : values.entrySet()) {
            String partName = e.getKey();
            Element value = e.getValue();
            String textValue;
            if (DOMUtils.isEmptyElement(value)) {
                textValue = "";
            } else {
                /*
                The expected part value could be a simple type
                or an element of a simple type.
                So if a element is there, take its text content
                else take the text content of the part element itself
                */
                Element childElement = DOMUtils.getFirstChildElement(value);
                if (childElement != null) {
                    textValue = DOMUtils.getTextContent(childElement);
                } else {
                    textValue = DOMUtils.getTextContent(value);
                }
            }
            // if it is not a simple type, skip it
            if (textValue != null) {
                l.add(new NameValuePair(e.getKey(), textValue));
            }
        }
        return formUrlEncode(l.toArray(new NameValuePair[0]), "UTF-8");
    }

    public static String formUrlEncode(NameValuePair[] pairs, String charset) {
        try {
            return doFormUrlEncode(pairs, charset);
        } catch (UnsupportedEncodingException e) {
            log.error("Encoding not supported: " + charset);
            try {
                return doFormUrlEncode(pairs, DEFAULT_CHARSET);
            } catch (UnsupportedEncodingException fatal) {
                // Should never happen. ISO-8859-1 must be supported on all JVMs
//                throw new HttpClientError("Encoding not supported: " +
//                        DEFAULT_CHARSET);
                throw new RuntimeException(e);
            }
        }
    }

    /** Copied from org.apache.commons.httpclient.util.EncodingUtil
     * Form-urlencoding routine.
     *
     * The default encoding for all forms is `application/x-www-form-urlencoded'.
     * A form data set is represented in this media type as follows:
     *
     * The form field names and values are escaped: space characters are replaced
     * by `+', and then reserved characters are escaped as per [URL]; that is,
     * non-alphanumeric characters are replaced by `%HH', a percent sign and two
     * hexadecimal digits representing the ASCII code of the character. Line breaks,
     * as in multi-line text field values, are represented as CR LF pairs, i.e. `%0D%0A'.
     *
     * @param pairs the values to be encoded
     * @param charset the character set of pairs to be encoded
     *
     * @return the urlencoded pairs
     * @throws UnsupportedEncodingException if charset is not supported
     *
     * @since 2.0 final
     */
    private static String doFormUrlEncode(NameValuePair[] pairs, String charset) throws UnsupportedEncodingException {
        StringBuffer buf = new StringBuffer();
        for (int i = 0; i < pairs.length; i++) {
            URLCodec codec = new URLCodec();
            NameValuePair pair = pairs[i];
            if (pair.getName() != null) {
                if (i > 0) {
                    buf.append("&");
                }
                buf.append(codec.encode(pair.getName(), charset));
                buf.append("=");
                if (pair.getValue() != null) {
                    buf.append(codec.encode(pair.getValue(), charset));
                }
            }
        }
        return buf.toString();
    }


    static class NameValuePair {
        private final String name;
        private final String value;

        public NameValuePair(String name, String value) {
            this.name = name;
            this.value = value;
        }

        public String getName() {
            return name;
        }

        public String getValue() {
            return value;
        }
    }

}
