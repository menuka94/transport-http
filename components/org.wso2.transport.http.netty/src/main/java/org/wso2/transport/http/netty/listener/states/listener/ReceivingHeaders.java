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

package org.wso2.transport.http.netty.listener.states.listener;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.transport.http.netty.common.Constants;
import org.wso2.transport.http.netty.contract.ServerConnectorException;
import org.wso2.transport.http.netty.contract.ServerConnectorFuture;
import org.wso2.transport.http.netty.contractimpl.HttpOutboundRespListener;
import org.wso2.transport.http.netty.internal.HandlerExecutor;
import org.wso2.transport.http.netty.internal.HttpTransportContextHolder;
import org.wso2.transport.http.netty.listener.SourceHandler;
import org.wso2.transport.http.netty.listener.states.StateContext;
import org.wso2.transport.http.netty.message.HttpCarbonMessage;

import static io.netty.buffer.Unpooled.EMPTY_BUFFER;
import static io.netty.handler.codec.http.HttpResponseStatus.REQUEST_TIMEOUT;
import static org.wso2.transport.http.netty.common.Constants
        .IDLE_TIMEOUT_TRIGGERED_WHILE_READING_INBOUND_REQUEST_HEADERS;
import static org.wso2.transport.http.netty.common.Constants.REMOTE_CLIENT_CLOSED_WHILE_READING_INBOUND_REQUEST_HEADERS;
import static org.wso2.transport.http.netty.common.Util.is100ContinueRequest;
import static org.wso2.transport.http.netty.listener.states.StateUtil.handleIncompleteInboundMessage;
import static org.wso2.transport.http.netty.listener.states.StateUtil.sendRequestTimeoutResponse;

/**
 * State between start and end of inbound request headers read.
 */
public class ReceivingHeaders implements ListenerState {

    private static Logger log = LoggerFactory.getLogger(ReceivingHeaders.class);
    private final SourceHandler sourceHandler;
    private final HandlerExecutor handlerExecutor;
    private final StateContext stateContext;
    private HttpCarbonMessage inboundRequestMsg;
    private float httpVersion;

    public ReceivingHeaders(SourceHandler sourceHandler, StateContext stateContext) {
        this.sourceHandler = sourceHandler;
        this.handlerExecutor = HttpTransportContextHolder.getInstance().getHandlerExecutor();
        this.stateContext = stateContext;
    }

    @Override
    public void readInboundRequestHeaders(HttpCarbonMessage inboundRequestMsg, HttpRequest inboundRequestHeaders) {
        this.inboundRequestMsg = inboundRequestMsg;
        this.httpVersion = Float.parseFloat((String) inboundRequestMsg.getProperty(Constants.HTTP_VERSION));
        boolean continueRequest = is100ContinueRequest(inboundRequestMsg);
        if (continueRequest) {
            stateContext.setListenerState(new Expect100ContinueHeaderReceived(stateContext, sourceHandler));
        }
        notifyRequestListener(inboundRequestMsg);

        if (inboundRequestHeaders.decoderResult().isFailure()) {
            log.warn(inboundRequestHeaders.decoderResult().cause().getMessage());
        }
        if (handlerExecutor != null) {
            handlerExecutor.executeAtSourceRequestReceiving(inboundRequestMsg);
        }
    }

    private void notifyRequestListener(HttpCarbonMessage httpRequestMsg) {
        if (handlerExecutor != null) {
            handlerExecutor.executeAtSourceRequestReceiving(httpRequestMsg);
        }

        if (sourceHandler.getServerConnectorFuture() != null) {
            try {
                ServerConnectorFuture outboundRespFuture = httpRequestMsg.getHttpResponseFuture();
                outboundRespFuture.setHttpConnectorListener(
                        new HttpOutboundRespListener(httpRequestMsg, sourceHandler));
                sourceHandler.getServerConnectorFuture().notifyHttpListener(httpRequestMsg);
            } catch (Exception e) {
                log.error("Error while notifying listeners", e);
            }
        } else {
            log.error("Cannot find registered listener to forward the message");
        }
    }

    @Override
    public void readInboundRequestEntityBody(Object inboundRequestEntityBody) throws ServerConnectorException {
        stateContext.setListenerState(
                new ReceivingEntityBody(stateContext, inboundRequestMsg, sourceHandler, httpVersion));
        stateContext.getListenerState().readInboundRequestEntityBody(inboundRequestEntityBody);
    }

    @Override
    public void writeOutboundResponseHeaders(HttpCarbonMessage outboundResponseMsg, HttpContent httpContent) {
        // Not a dependant action of this state.
    }

    @Override
    public void writeOutboundResponseEntityBody(HttpOutboundRespListener outboundResponseListener,
                                                HttpCarbonMessage outboundResponseMsg, HttpContent httpContent) {
        // Not a dependant action of this state.
    }

    @Override
    public void handleAbruptChannelClosure(ServerConnectorFuture serverConnectorFuture) {
        handleIncompleteInboundMessage(inboundRequestMsg, REMOTE_CLIENT_CLOSED_WHILE_READING_INBOUND_REQUEST_HEADERS);
    }

    @Override
    public ChannelFuture handleIdleTimeoutConnectionClosure(ServerConnectorFuture serverConnectorFuture,
                                                            ChannelHandlerContext ctx) {
        ChannelFuture outboundRespFuture = sendRequestTimeoutResponse(ctx, REQUEST_TIMEOUT, EMPTY_BUFFER, 0,
                                                                      httpVersion, sourceHandler.getServerName());
        outboundRespFuture.addListener((ChannelFutureListener) channelFuture -> {
            Throwable cause = channelFuture.cause();
            if (cause != null) {
                log.warn("Failed to send: {}", cause.getMessage());
            }
            sourceHandler.channelInactive(ctx);
            handleIncompleteInboundMessage(inboundRequestMsg,
                                           IDLE_TIMEOUT_TRIGGERED_WHILE_READING_INBOUND_REQUEST_HEADERS);
        });
        return outboundRespFuture;
    }
}