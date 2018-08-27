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

package org.wso2.transport.http.netty.listener.states;


import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.transport.http.netty.common.Constants;
import org.wso2.transport.http.netty.common.Util;
import org.wso2.transport.http.netty.config.ChunkConfig;
import org.wso2.transport.http.netty.contract.HttpResponseFuture;
import org.wso2.transport.http.netty.message.HttpCarbonMessage;
import org.wso2.transport.http.netty.sender.channel.TargetChannel;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;

import static org.wso2.transport.http.netty.common.Constants.CLIENT_TO_REMOTE_HOST_CONNECTION_CLOSED;

/**
 * Utility functions for states
 */
public class StateUtil {

    private static Logger log = LoggerFactory.getLogger(StateUtil.class);

    public static boolean checkChunkingCompatibility(String httpVersion, ChunkConfig chunkConfig) {
        return Util.isVersionCompatibleForChunking(httpVersion) || Util
                .shouldEnforceChunkingforHttpOneZero(chunkConfig, httpVersion);
    }

    public static void notifyIfHeaderWriteFailure(HttpResponseFuture httpResponseStatusFuture,
                                                  ChannelFuture outboundHeaderFuture, String errorMsg) {
        outboundHeaderFuture.addListener(writeOperationPromise -> {
            Throwable throwable = writeOperationPromise.cause();
            if (throwable != null) {
                if (throwable instanceof ClosedChannelException) {
                    throwable = new IOException(errorMsg);
                }
                httpResponseStatusFuture.notifyHttpListener(throwable);
            }
        });
    }

    public static void writeRequestHeaders(HttpCarbonMessage httpOutboundRequest,
                                           HttpResponseFuture httpInboundResponseFuture, String httpVersion,
                                           TargetChannel targetChannel) {
        setHttpVersionProperty(httpOutboundRequest, httpVersion);
        HttpRequest httpRequest = Util.createHttpRequest(httpOutboundRequest);
        targetChannel.setRequestHeaderWritten(true);
        ChannelFuture outboundHeaderFuture = targetChannel.getChannel().write(httpRequest);
        notifyIfHeaderWriteFailure(httpInboundResponseFuture, outboundHeaderFuture,
                                   CLIENT_TO_REMOTE_HOST_CONNECTION_CLOSED);
    }

    private static void setHttpVersionProperty(HttpCarbonMessage httpOutboundRequest, String httpVersion) {
        if (Float.valueOf(httpVersion) == Constants.HTTP_2_0) {
            // Upgrade request of HTTP/2 should be a HTTP/1.1 request
            httpOutboundRequest.setProperty(Constants.HTTP_VERSION, String.valueOf(Constants.HTTP_1_1));
        } else {
            httpOutboundRequest.setProperty(Constants.HTTP_VERSION, httpVersion);
        }
    }

    public static ChannelFuture sendRequestTimeoutResponse(ChannelHandlerContext ctx, HttpResponseStatus status,
                                                           ByteBuf content, int length, float httpVersion,
                                                           String serverName) {
        HttpResponse outboundResponse;
        if (httpVersion == Constants.HTTP_1_0) {
            outboundResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_0, status, content);
        } else {
            outboundResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content);
        }

        outboundResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, length);
        if (length != 0) {
            outboundResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, Constants.TEXT_PLAIN);
        }
        outboundResponse.headers().set(HttpHeaderNames.CONNECTION.toString(), Constants.CONNECTION_CLOSE);
        outboundResponse.headers().set(HttpHeaderNames.SERVER.toString(), serverName);
        return ctx.channel().writeAndFlush(outboundResponse);
    }

    public static void handleIncompleteInboundMessage(HttpCarbonMessage inboundRequestMsg, String errorMessage) {
        LastHttpContent lastHttpContent = new DefaultLastHttpContent();
        lastHttpContent.setDecoderResult(DecoderResult.failure(new DecoderException(errorMessage)));
        inboundRequestMsg.addHttpContent(lastHttpContent);
        log.warn(errorMessage);
    }
}