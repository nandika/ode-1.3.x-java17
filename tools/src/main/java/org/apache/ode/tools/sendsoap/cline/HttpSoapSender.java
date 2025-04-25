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
package org.apache.ode.tools.sendsoap.cline;


import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.Credentials;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.Closer;
import org.apache.hc.core5.util.Timeout;
import org.apache.ode.tools.ToolMessages;
import org.apache.ode.utils.StreamUtils;
import org.apache.ode.utils.cli.Argument;
import org.apache.ode.utils.cli.BaseCommandlineTool;
import org.apache.ode.utils.cli.CommandlineFragment;
import org.apache.ode.utils.cli.ConsoleFormatter;
import org.apache.ode.utils.cli.FlagWithArgument;
import org.apache.ode.utils.cli.Fragments;
import org.apache.ode.utils.msg.CommonMessages;
import org.apache.ode.utils.msg.MessageBundle;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <p>
 * Commandline tool to send the contents of a (binary) file to a URL.
 * </p>
 */
public class HttpSoapSender extends BaseCommandlineTool {

    private static final int RETURN_BAD_URL = 2;
    private static final int RETURN_SEND_ERROR = 3;
    private static final int RETURN_CANT_READ = 4;
    private static final int RETURN_CANT_WRITE = 5;
    private static final int RETURN_BAD_PORT = 6;

    private static Pattern SEQ = Pattern.compile("\\$sequence\\$");

    private static final ToolMessages MESSAGES = MessageBundle.getMessages(ToolMessages.class);
    private static final CommonMessages COMMON = MessageBundle.getMessages(CommonMessages.class);

    private static final Argument URL_A = new Argument("url","the URL to send the SOAP to.",false);
    private static final Argument FILE_A = new Argument("file","the file that contains the SOAP to send.",false);

    private static final FlagWithArgument PROXY_SERVER = new FlagWithArgument("s", "proxyServer",
            "server to use for proxy authentication.",true);
    private static final FlagWithArgument PROXY_PORT = new FlagWithArgument("p", "proxyPort",
            "port to use for proxy authentication.",true);
    private static final FlagWithArgument PROXY_USER = new FlagWithArgument("u", "username",
            "username to use for proxy authentication.",true);
    private static final FlagWithArgument PROXY_PASS = new FlagWithArgument("w", "password",
            "password to use for proxy authentication.",true);
    private static final FlagWithArgument SOAP_ACTION = new FlagWithArgument("a", "soapAction",
            "SOAP action to include in the message header.",true);
    private static final FlagWithArgument OUTFILE_FWA = new FlagWithArgument("o","outfile",
            "a file to write the output to (instead of standard out).",true);

    private static final Fragments CLINE = new Fragments(new CommandlineFragment[] {
            OUTFILE_FWA, URL_A, FILE_A, PROXY_SERVER, PROXY_PORT, PROXY_USER, PROXY_PASS, SOAP_ACTION
    });

    private static final String SYNOPSIS =
            "send the contents of a file to a URL as a SOAP request and print the response (if any) to the console or a file.";

    protected static String getProgramName() {
      return "sendsoap";
    }

    public static String doSend(URL u, InputStream is, String proxyServer, int proxyPort,
                              String username, String password, String soapAction) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
        StreamUtils.copy(bos, is);
        String now = Long.toString(System.currentTimeMillis());
        int c = 1;
        String data = new String(bos.toByteArray());
        Matcher m = SEQ.matcher(data);
        StringBuffer sb = new StringBuffer(8192);
        while (m.find()) {
            m.appendReplacement(sb, now + "-" + c++);
        }
        m.appendTail(sb);
        //SimpleHttpConnectionManager mgr = new SimpleHttpConnectionManager();

        SocketConfig socketConfig = SocketConfig.custom()
                .setSoTimeout(Timeout.ofMilliseconds(60000))
                .build();

        PoolingHttpClientConnectionManager mgr = new PoolingHttpClientConnectionManager();
        mgr.setDefaultSocketConfig(socketConfig);


        HttpClientContext context = HttpClientContext.create();
        HttpHost proxy = null;
        if (proxyServer != null && proxyServer.length() > 0) {
             proxy = new HttpHost(proxyServer, proxyPort);
            BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
            AuthScope authScope = new AuthScope(proxyServer, proxyPort);
            Credentials credentials = new UsernamePasswordCredentials(username, password.toCharArray());
            credsProvider.setCredentials( authScope, credentials);
            context.setCredentialsProvider(credsProvider);
            // httpPostMethod.setDoAuthentication(true);
        }
        RequestConfig   config = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(60000))
                .setResponseTimeout(Timeout.ofMilliseconds(60000))
                .setProxy(proxy)
                .build();

        HttpPost post = new HttpPost(u.toExternalForm());
        if (soapAction == null) soapAction = "";
        post.addHeader(new BasicHeader("SOAPAction", "\"" + soapAction + "\""));
        post.addHeader(new BasicHeader("Content-Type", "text/xml"));
        ContentType contentType = ContentType.create("text/xml", "UTF-8");
        StringEntity entity = new StringEntity(sb.toString(), contentType);
        post.setEntity(entity);

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(mgr)
                .setDefaultRequestConfig(config)
                .build();
        try{
            CloseableHttpResponse response = httpClient.execute(post, context);
            if(response != null && response.getEntity() != null && response.getEntity().getContent() != null) {
                String responseStr = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8) + "\n";
                return responseStr;
            }
        } catch (ParseException e) {
            throw new RuntimeException(e);
        } finally {
            try {
                if (httpClient != null) httpClient.close();
                if(mgr != null) mgr.close();
            } catch (IOException e) {
                // ignore
            }
        }
        return null;
    }

    public static void main(String[] argv) {
        if (argv.length == 0 || HELP.matches(argv)) {
            ConsoleFormatter.printSynopsis(getProgramName(),SYNOPSIS,new Fragments[] {
                    CLINE,HELP});
            System.exit(0);
        } else if (!CLINE.matches(argv)) {
            consoleErr("INVALID COMMANDLINE: Try \"" + getProgramName() + " -h\" for help.");
            System.exit(-1);
        }
        File fout = null;
        if (OUTFILE_FWA.isSet()) {
            String outfile = OUTFILE_FWA.getValue();
            fout = new File(outfile);
        }

        URL u = null;
        try {
            u = new URL(URL_A.getValue());
        } catch (MalformedURLException mue) {
            consoleErr(MESSAGES.msgBadUrl(URL_A.getValue(),mue.getMessage()));
            System.exit(RETURN_BAD_URL);
        }

        InputStream is = null;

        String src = FILE_A.getValue();
        if (src.equals("-")) {
            is = System.in;
        } else {
            File f = new File(src);
            try {
                is = new FileInputStream(f);
            } catch (FileNotFoundException fnfe) {
                consoleErr(COMMON.msgCannotReadFromFile(src));
                System.exit(RETURN_CANT_READ);
            }
        }

        boolean hasProxy = PROXY_SERVER.getValue() != null && PROXY_SERVER.getValue().length() > 0;
        if (hasProxy) {
            String proxyPort = PROXY_PORT.getValue();
            try {
                Integer.parseInt(proxyPort);
            } catch (NumberFormatException e) {
                consoleErr(COMMON.msgBadPort(proxyPort));
                System.exit(RETURN_BAD_PORT);
            }
        }

        initLogging();
        try{
            String result = doSend(u,is, PROXY_SERVER.getValue(), hasProxy ? Integer.parseInt(PROXY_PORT.getValue()) : 0,
                    PROXY_USER.getValue(), PROXY_PASS.getValue(), SOAP_ACTION.getValue());
            if (OUTFILE_FWA.isSet()) {
                FileOutputStream fos = new FileOutputStream(fout);
                fos.write(result.getBytes());
                fos.close();
            } else System.out.println(result);

        } catch (IOException ioe) {
            consoleErr(MESSAGES.msgIoErrorOnSend(ioe.getMessage()));
            System.exit(RETURN_SEND_ERROR);
        }
    }
}
