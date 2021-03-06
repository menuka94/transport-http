/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.transport.http.netty.http2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.wso2.transport.http.netty.contentaware.listeners.EchoMessageListener;
import org.wso2.transport.http.netty.contract.HttpClientConnector;
import org.wso2.transport.http.netty.contract.HttpWsConnectorFactory;
import org.wso2.transport.http.netty.contract.ServerConnector;
import org.wso2.transport.http.netty.contract.ServerConnectorException;
import org.wso2.transport.http.netty.contract.ServerConnectorFuture;
import org.wso2.transport.http.netty.contract.config.ListenerConfiguration;
import org.wso2.transport.http.netty.contract.config.Parameter;
import org.wso2.transport.http.netty.contract.config.SenderConfiguration;
import org.wso2.transport.http.netty.contractimpl.DefaultHttpWsConnectorFactory;
import org.wso2.transport.http.netty.util.TestUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.wso2.transport.http.netty.contract.Constants.HTTPS_SCHEME;
import static org.wso2.transport.http.netty.contract.Constants.HTTP_1_1;
import static org.wso2.transport.http.netty.contract.Constants.HTTP_2_0;
import static org.wso2.transport.http.netty.contract.Constants.OPTIONAL;

/**
 * A test case consisting of a http2 client and server communicating over TLS.
 */
public class TestHttp2WithALPN {

    private static final Logger LOG = LoggerFactory.getLogger(TestHttp2WithALPN.class);
    private ServerConnector serverConnector;
    private HttpClientConnector http1ClientConnector;
    private HttpClientConnector http2ClientConnector;
    private HttpWsConnectorFactory connectorFactory;

    @BeforeClass
    public void setup() throws InterruptedException {

        HttpWsConnectorFactory factory = new DefaultHttpWsConnectorFactory();
        serverConnector = factory
                .createServerConnector(TestUtil.getDefaultServerBootstrapConfig(), getListenerConfigs());
        ServerConnectorFuture future = serverConnector.start();
        future.setHttpConnectorListener(new EchoMessageListener());
        future.sync();

        connectorFactory = new DefaultHttpWsConnectorFactory();
        http2ClientConnector = connectorFactory
                .createHttpClientConnector(new HashMap<>(), getSenderConfigs(String.valueOf(HTTP_2_0)));
        http1ClientConnector = connectorFactory
                .createHttpClientConnector(new HashMap<>(), getSenderConfigs(String.valueOf(HTTP_1_1)));
    }

    /**
     * This test case will have ALPN negotiation for HTTP/2 request.
     */
    @Test
    public void testHttp2Post() {
        TestUtil.testHttpsPost(http2ClientConnector, TestUtil.SERVER_PORT1);
    }

    /**
     * This test case will have ALPN negotiation for HTTP/1.1 request.
     */
    @Test
    public void testHttp1_1Post() {
        TestUtil.testHttpsPost(http1ClientConnector, TestUtil.SERVER_PORT1);
    }

    private ListenerConfiguration getListenerConfigs() {
        Parameter paramServerCiphers = new Parameter("ciphers", "TLS_RSA_WITH_AES_128_CBC_SHA");
        List<Parameter> serverParams = new ArrayList<>(1);
        serverParams.add(paramServerCiphers);
        ListenerConfiguration listenerConfiguration = new ListenerConfiguration();
        listenerConfiguration.setParameters(serverParams);
        listenerConfiguration.setPort(TestUtil.SERVER_PORT1);
        listenerConfiguration.setScheme(HTTPS_SCHEME);
        listenerConfiguration.setVersion(String.valueOf(HTTP_2_0));
        listenerConfiguration.setVerifyClient(OPTIONAL);
        listenerConfiguration.setSslSessionTimeOut(TestUtil.SSL_SESSION_TIMEOUT);
        listenerConfiguration.setSslHandshakeTimeOut(TestUtil.SSL_HANDSHAKE_TIMEOUT);
        listenerConfiguration.setKeyStoreFile(TestUtil.getAbsolutePath(TestUtil.KEY_STORE_FILE_PATH));
        listenerConfiguration.setKeyStorePass(TestUtil.KEY_STORE_PASSWORD);
        return listenerConfiguration;
    }

    private SenderConfiguration getSenderConfigs(String httpVersion) {
        Parameter paramClientCiphers = new Parameter("ciphers", "TLS_RSA_WITH_AES_128_CBC_SHA");
        List<Parameter> clientParams = new ArrayList<>(1);
        clientParams.add(paramClientCiphers);
        SenderConfiguration senderConfiguration = new SenderConfiguration();
        senderConfiguration.setParameters(clientParams);
        senderConfiguration.setTrustStoreFile(TestUtil.getAbsolutePath(TestUtil.KEY_STORE_FILE_PATH));
        senderConfiguration.setTrustStorePass(TestUtil.KEY_STORE_PASSWORD);
        senderConfiguration.setHttpVersion(httpVersion);
        senderConfiguration.setSslSessionTimeOut(TestUtil.SSL_SESSION_TIMEOUT);
        senderConfiguration.setSslHandshakeTimeOut(TestUtil.SSL_HANDSHAKE_TIMEOUT);
        senderConfiguration.setScheme(HTTPS_SCHEME);
        return senderConfiguration;
    }

    @AfterClass
    public void cleanUp() throws ServerConnectorException {
        http2ClientConnector.close();
        http1ClientConnector.close();
        serverConnector.stop();
        try {
            connectorFactory.shutdown();
        } catch (InterruptedException e) {
            LOG.error("Interrupted while waiting for HttpWsFactory to shutdown", e);
        }
    }
}

