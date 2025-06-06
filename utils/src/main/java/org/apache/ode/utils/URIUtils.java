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

import org.apache.commons.codec.net.URLCodec;

import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.util.BitSet;

public class URIUtils {
    protected static final BitSet percent = new BitSet(256);
    // Static initializer for percent
    static {
        percent.set('%');
    }


    protected static final BitSet reserved = new BitSet(256);
    // Static initializer for reserved
    static {
        reserved.set(';');
        reserved.set('/');
        reserved.set('?');
        reserved.set(':');
        reserved.set('@');
        reserved.set('&');
        reserved.set('=');
        reserved.set('+');
        reserved.set('$');
        reserved.set(',');
    }

    protected static final BitSet digit = new BitSet(256);
    // Static initializer for digit
    static {
        for (int i = '0'; i <= '9'; i++) {
            digit.set(i);
        }
    }

    protected static final BitSet hex = new BitSet(256);
    // Static initializer for hex
    static {
        hex.or(digit);
        for (int i = 'a'; i <= 'f'; i++) {
            hex.set(i);
        }
        for (int i = 'A'; i <= 'F'; i++) {
            hex.set(i);
        }
    }

    protected static final BitSet escaped = new BitSet(256);
    // Static initializer for escaped
    static {
        escaped.or(percent);
        escaped.or(hex);
    }

    protected static final BitSet alpha = new BitSet(256);
    // Static initializer for alpha
    static {
        for (int i = 'a'; i <= 'z'; i++) {
            alpha.set(i);
        }
        for (int i = 'A'; i <= 'Z'; i++) {
            alpha.set(i);
        }
    }


    /**
     * BitSet for alphanum (join of alpha &amp; digit).
     * <p><blockquote><pre>
     *  alphanum      = alpha | digit
     * </pre></blockquote><p>
     */
    protected static final BitSet alphanum = new BitSet(256);
    // Static initializer for alphanum
    static {
        alphanum.or(alpha);
        alphanum.or(digit);
    }

    protected static final BitSet mark = new BitSet(256);
    // Static initializer for mark
    static {
        mark.set('-');
        mark.set('_');
        mark.set('.');
        mark.set('!');
        mark.set('~');
        mark.set('*');
        mark.set('\'');
        mark.set('(');
        mark.set(')');
    }


    /**
     * Data characters that are allowed in a URI but do not have a reserved
     * purpose are called unreserved.
     * <p><blockquote><pre>
     * unreserved    = alphanum | mark
     * </pre></blockquote><p>
     */
    protected static final BitSet unreserved = new BitSet(256);
    // Static initializer for unreserved
    static {
        unreserved.or(alphanum);
        unreserved.or(mark);
    }
    /**
     * BitSet for uric.
     * <p><blockquote><pre>
     * uric          = reserved | unreserved | escaped
     * </pre></blockquote><p>
     */
    protected static final BitSet uric = new BitSet(256);
    // Static initializer for uric
    static {
        uric.or(reserved);
        uric.or(unreserved);
        uric.or(escaped);
    }


    public static final BitSet allowed_query = new BitSet(256);
    // Static initializer for allowed_query
    static {
        allowed_query.or(uric);
        allowed_query.clear('%');
    }


    public static final BitSet allowed_within_query = new BitSet(256);
    // Static initializer for allowed_within_query
    static {
        allowed_within_query.or(allowed_query);
        allowed_within_query.andNot(reserved); // excluded 'reserved'
    }

    public static byte[] getBytes(final String data, String charset) {

        if (data == null) {
            throw new IllegalArgumentException("data may not be null");
        }

        if (charset == null || charset.length() == 0) {
            throw new IllegalArgumentException("charset may not be null or empty");
        }

        try {
            return data.getBytes(charset);
        } catch (UnsupportedEncodingException e) {

            return data.getBytes();
        }
    }

    public static String encode(String unescaped, BitSet allowed,
                                String charset) throws URISyntaxException, UnsupportedEncodingException {
        byte[] rawdata = URLCodec.encodeUrl(allowed,
                getBytes(unescaped, charset));
        String str = new String(rawdata, 0, rawdata.length, "US-ASCII");
        return str;
    }

    public static String encodeWithinQuery(String unescaped, String charset)
            throws URISyntaxException, UnsupportedEncodingException {

        return encode(unescaped, allowed_within_query, charset);
    }
}
