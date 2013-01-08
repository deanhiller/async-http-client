/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.ning.http.client.providers.chanmgr;

import static com.ning.http.util.AsyncHttpProviderUtils.DEFAULT_CHARSET;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLEngine;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBufferOutputStream;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureProgressListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.DefaultChannelFuture;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.FileRegion;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.handler.codec.http.CookieEncoder;
import org.jboss.netty.handler.codec.http.DefaultCookie;
import org.jboss.netty.handler.codec.http.DefaultHttpChunkTrailer;
import org.jboss.netty.handler.codec.http.DefaultHttpRequest;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpChunkTrailer;
import org.jboss.netty.handler.codec.http.HttpClientCodec;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.jboss.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.WebSocket08FrameDecoder;
import org.jboss.netty.handler.codec.http.websocketx.WebSocket08FrameEncoder;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.jboss.netty.handler.ssl.SslHandler;
import org.jboss.netty.handler.stream.ChunkedFile;
import org.jboss.netty.handler.stream.ChunkedWriteHandler;
import org.playorm.nio.api.ChannelManager;
import org.playorm.nio.api.ChannelManagerFactory;
import org.playorm.nio.api.channels.TCPChannel;
import org.playorm.nio.api.handlers.FutureOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.AsyncHandler.STATE;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.AsyncHttpProvider;
import com.ning.http.client.Body;
import com.ning.http.client.BodyGenerator;
import com.ning.http.client.ConnectionsPool;
import com.ning.http.client.Cookie;
import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.ListenableFuture;
import com.ning.http.client.MaxRedirectException;
import com.ning.http.client.PerRequestConfig;
import com.ning.http.client.ProgressAsyncHandler;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.RandomAccessBody;
import com.ning.http.client.Realm;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.Response;
import com.ning.http.client.filter.FilterContext;
import com.ning.http.client.filter.FilterException;
import com.ning.http.client.filter.IOExceptionFilter;
import com.ning.http.client.filter.ResponseFilter;
import com.ning.http.client.generators.InputStreamBodyGenerator;
import com.ning.http.client.listener.TransferCompletionHandler;
import com.ning.http.client.ntlm.NTLMEngine;
import com.ning.http.client.ntlm.NTLMEngineException;
import com.ning.http.client.providers.chanmgr.FeedableBodyGenerator.FeedListener;
import com.ning.http.client.providers.chanmgr.chain.AConnectListener;
import com.ning.http.client.providers.chanmgr.spnego.SpnegoEngine;
import com.ning.http.client.providers.chanmgr.util.CleanupChannelGroup;
import com.ning.http.client.websocket.WebSocketUpgradeHandler;
import com.ning.http.multipart.MultipartBody;
import com.ning.http.multipart.MultipartRequestEntity;
import com.ning.http.util.AsyncHttpProviderUtils;
import com.ning.http.util.AuthenticatorUtils;
import com.ning.http.util.ProxyUtils;
import com.ning.http.util.SslUtils;
import com.ning.http.util.UTF8UrlEncoder;

public class ChanMgrAsyncHttpProvider extends SimpleChannelUpstreamHandler implements AsyncHttpProvider {
    private final static String WEBSOCKET_KEY = "Sec-WebSocket-Key";
    private final static String HTTP_HANDLER = "httpHandler";
    protected final static String SSL_HANDLER = "sslHandler";
    private final static String HTTPS = "https";
    private final static String HTTP = "http";
    private static final String WEBSOCKET = "ws";
    private static final String WEBSOCKET_SSL = "wss";

    private final static Logger log = LoggerFactory.getLogger(ChanMgrAsyncHttpProvider.class);
    private final static Charset UTF8 = Charset.forName("UTF-8");

    private final static int MAX_BUFFERED_BYTES = 8192;
    private final AsyncHttpClientConfig config;
    private final AtomicBoolean isClose = new AtomicBoolean(false);

    private final ConnectionsPool<String, TCPChannel> connectionsPool;
    private Semaphore freeConnections = null;
    private final ChanMgrAsyncHttpProviderConfig asyncHttpProviderConfig;
    private final boolean trackConnections;
    private final boolean useRawUrl;
    private final static NTLMEngine ntlmEngine = new NTLMEngine();
    private final static SpnegoEngine spnegoEngine = new SpnegoEngine();
    //private final Protocol httpProtocol = new HttpProtocol();
    //private final Protocol webSocketProtocol = new WebSocketProtocol();
	private ChannelManager chanMgr;
	private int chanCount;

    public ChanMgrAsyncHttpProvider(AsyncHttpClientConfig config) {

        if (config.getAsyncHttpProviderConfig() != null
                && ChanMgrAsyncHttpProviderConfig.class.isAssignableFrom(config.getAsyncHttpProviderConfig().getClass())) {
            asyncHttpProviderConfig = ChanMgrAsyncHttpProviderConfig.class.cast(config.getAsyncHttpProviderConfig());
        } else {
            asyncHttpProviderConfig = new ChanMgrAsyncHttpProviderConfig();
        }

        Map<String, Object> props = null;
		chanMgr = ChannelManagerFactory.createChannelManager("client", props);

        this.config = config;

        // This is dangerous as we can't catch a wrong typed ConnectionsPool
        ConnectionsPool<String, TCPChannel> cp = (ConnectionsPool<String, TCPChannel>) config.getConnectionsPool();
        if (cp == null && config.getAllowPoolingConnection()) {
            cp = new ChanMgrConnectionsPool(this);
        } else if (cp == null) {
            cp = new NonConnectionsPool();
        }
        this.connectionsPool = cp;

        if (config.getMaxTotalConnections() != -1) {
            trackConnections = true;
            freeConnections = new Semaphore(config.getMaxTotalConnections());
        } else {
            trackConnections = false;
        }

        useRawUrl = config.isUseRawUrl();
    }

    @Override
    public String toString() {
        return String.format("ChanMgrAsyncHttpProvider:\n\t- maxConnections: %d\n\t- connectionPools: %s",
                config.getMaxTotalConnections() - freeConnections.availablePermits(),
                connectionsPool.toString());
    }

    private TCPChannel lookupInCache(URI uri) {
        final TCPChannel channel = connectionsPool.poll(AsyncHttpProviderUtils.getBaseUrl(uri));

//        if (channel != null) {
//            log.debug("Using cached Channel {}\n for uri {}\n", channel, uri);
//
//            try {
//                // Always make sure the channel who got cached support the proper protocol. It could
//                // only occurs when a HttpMethod.CONNECT is used agains a proxy that require upgrading from http to
//                // https.
//                return verifyChannelPipeline(channel, uri.getScheme());
//            } catch (Exception ex) {
//                log.debug(ex.getMessage(), ex);
//            }
//        }
        return null;
    }

    private SSLEngine createSSLEngine() throws IOException, GeneralSecurityException {
        SSLEngine sslEngine = config.getSSLEngineFactory().newSSLEngine();
        if (sslEngine == null) {
            sslEngine = SslUtils.getSSLEngine();
        }
        return sslEngine;
    }

    private TCPChannel verifyChannelPipeline(TCPChannel channel, String scheme) throws IOException, GeneralSecurityException {

//        if (channel.getPipeline().get(SSL_HANDLER) != null && HTTP.equalsIgnoreCase(scheme)) {
//            channel.getPipeline().remove(SSL_HANDLER);
//        } else if (channel.getPipeline().get(HTTP_HANDLER) != null && HTTP.equalsIgnoreCase(scheme)) {
//            return channel;
//        } else if (channel.getPipeline().get(SSL_HANDLER) == null && isSecure(scheme)) {
//            channel.getPipeline().addFirst(SSL_HANDLER, new SslHandler(createSSLEngine()));
//        }
        return channel;
    }

    private static boolean isProxyServer(AsyncHttpClientConfig config, Request request) {
        return request.getProxyServer() != null || config.getProxyServer() != null;
    }

    protected final static HttpRequest buildRequest(AsyncHttpClientConfig config, Request request, URI uri,
                                                    boolean allowConnect, ChannelBuffer buffer) throws IOException {

        String method = request.getMethod();
        if (allowConnect && (isProxyServer(config, request) && isSecure(uri))) {
            method = HttpMethod.CONNECT.toString();
        }
        return construct(config, request, new HttpMethod(method), uri, buffer);
    }

    private static HttpRequest construct(AsyncHttpClientConfig config,
                                         Request request,
                                         HttpMethod m,
                                         URI uri,
                                         ChannelBuffer buffer) throws IOException {

        String host = AsyncHttpProviderUtils.getHost(uri);
        boolean webSocket = isWebSocket(uri);

        if (request.getVirtualHost() != null) {
            host = request.getVirtualHost();
        }

        HttpRequest nettyRequest;
        if (m.equals(HttpMethod.CONNECT)) {
            nettyRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_0, m, AsyncHttpProviderUtils.getAuthority(uri));
        } else {
            StringBuilder path = null;
            if (isProxyServer(config, request))
                path = new StringBuilder(uri.toString());
            else {
                path = new StringBuilder(uri.getRawPath());
                if (uri.getQuery() != null) {
                    path.append("?").append(uri.getRawQuery());
                }
            }
            nettyRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, m, path.toString());
        }

        if (webSocket) {
            nettyRequest.addHeader(HttpHeaders.Names.UPGRADE, HttpHeaders.Values.WEBSOCKET);
            nettyRequest.addHeader(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.UPGRADE);
            nettyRequest.addHeader("Origin", "http://" + uri.getHost() + ":"
                    + (uri.getPort() == -1 ? isSecure(uri) ? 443 : 80 : uri.getPort()));
            nettyRequest.addHeader(WEBSOCKET_KEY, WebSocketUtil.getKey());
            nettyRequest.addHeader("Sec-WebSocket-Version", "13");
        }

        if (host != null) {
            if (uri.getPort() == -1) {
                nettyRequest.setHeader(HttpHeaders.Names.HOST, host);
            } else if (request.getVirtualHost() != null) {
                nettyRequest.setHeader(HttpHeaders.Names.HOST, host);
            } else {
                nettyRequest.setHeader(HttpHeaders.Names.HOST, host + ":" + uri.getPort());
            }
        } else {
            host = "127.0.0.1";
        }

        if (!m.equals(HttpMethod.CONNECT)) {
            FluentCaseInsensitiveStringsMap h = request.getHeaders();
            if (h != null) {
                for (String name : h.keySet()) {
                    if (!"host".equalsIgnoreCase(name)) {
                        for (String value : h.get(name)) {
                            nettyRequest.addHeader(name, value);
                        }
                    }
                }
            }

            if (config.isCompressionEnabled()) {
                nettyRequest.setHeader(HttpHeaders.Names.ACCEPT_ENCODING, HttpHeaders.Values.GZIP);
            }
        } else {
            List<String> auth = request.getHeaders().get(HttpHeaders.Names.PROXY_AUTHORIZATION);
            if (auth != null && auth.size() > 0 && auth.get(0).startsWith("NTLM")) {
                nettyRequest.addHeader(HttpHeaders.Names.PROXY_AUTHORIZATION, auth.get(0));
            }
        }
        ProxyServer proxyServer = request.getProxyServer() != null ? request.getProxyServer() : config.getProxyServer();
        Realm realm = request.getRealm() != null ? request.getRealm() : config.getRealm();

        if (realm != null && realm.getUsePreemptiveAuth()) {

            String domain = realm.getNtlmDomain();
            if (proxyServer != null && proxyServer.getNtlmDomain() != null) {
                domain = proxyServer.getNtlmDomain();
            }

            String authHost = realm.getNtlmHost();
            if (proxyServer != null && proxyServer.getHost() != null) {
                host = proxyServer.getHost();
            }

            switch (realm.getAuthScheme()) {
                case BASIC:
                    nettyRequest.setHeader(HttpHeaders.Names.AUTHORIZATION,
                            AuthenticatorUtils.computeBasicAuthentication(realm));
                    break;
                case DIGEST:
                    if (realm.getNonce() != null && !realm.getNonce().equals("")) {
                        try {
                            nettyRequest.setHeader(HttpHeaders.Names.AUTHORIZATION,
                                    AuthenticatorUtils.computeDigestAuthentication(realm));
                        } catch (NoSuchAlgorithmException e) {
                            throw new SecurityException(e);
                        }
                    }
                    break;
                case NTLM:
                    try {
                        String msg = ntlmEngine.generateType1Msg("NTLM " + domain, authHost);
                        nettyRequest.setHeader(HttpHeaders.Names.AUTHORIZATION, "NTLM " + msg);
                    } catch (NTLMEngineException e) {
                        IOException ie = new IOException();
                        ie.initCause(e);
                        throw ie;
                    }
                    break;
                case KERBEROS:
                case SPNEGO:
                    String challengeHeader = null;
                    String server = proxyServer == null ? host : proxyServer.getHost();
                    try {
                        challengeHeader = spnegoEngine.generateToken(server);
                    } catch (Throwable e) {
                        IOException ie = new IOException();
                        ie.initCause(e);
                        throw ie;
                    }
                    nettyRequest.setHeader(HttpHeaders.Names.AUTHORIZATION, "Negotiate " + challengeHeader);
                    break;
                case NONE:
                    break;
                default:
                    throw new IllegalStateException(String.format("Invalid Authentication %s", realm.toString()));
            }
        }

        if (!webSocket && !request.getHeaders().containsKey(HttpHeaders.Names.CONNECTION)) {
            nettyRequest.setHeader(HttpHeaders.Names.CONNECTION, "keep-alive");
        }

        boolean avoidProxy = ProxyUtils.avoidProxy(proxyServer, request);
        if (!avoidProxy) {
            if (!request.getHeaders().containsKey("Proxy-Connection")) {
                nettyRequest.setHeader("Proxy-Connection", "keep-alive");
            }

            if (proxyServer.getPrincipal() != null) {
                if (proxyServer.getNtlmDomain() != null && proxyServer.getNtlmDomain().length() > 0) {

                    List<String> auth = request.getHeaders().get(HttpHeaders.Names.PROXY_AUTHORIZATION);
                    if (!(auth != null && auth.size() > 0 && auth.get(0).startsWith("NTLM"))) {
                        try {
                            String msg = ntlmEngine.generateType1Msg(proxyServer.getNtlmDomain(),
                                    proxyServer.getHost());
                            nettyRequest.setHeader(HttpHeaders.Names.PROXY_AUTHORIZATION, "NTLM " + msg);
                        } catch (NTLMEngineException e) {
                            IOException ie = new IOException();
                            ie.initCause(e);
                            throw ie;
                        }
                    }
                } else {
                    nettyRequest.setHeader(HttpHeaders.Names.PROXY_AUTHORIZATION,
                            AuthenticatorUtils.computeBasicAuthentication(proxyServer));
                }
            }
        }

        // Add default accept headers.
        if (request.getHeaders().getFirstValue("Accept") == null) {
            nettyRequest.setHeader(HttpHeaders.Names.ACCEPT, "*/*");
        }

        if (request.getHeaders().getFirstValue("User-Agent") != null) {
            nettyRequest.setHeader("User-Agent", request.getHeaders().getFirstValue("User-Agent"));
        } else if (config.getUserAgent() != null) {
            nettyRequest.setHeader("User-Agent", config.getUserAgent());
        } else {
            nettyRequest.setHeader("User-Agent",
                         AsyncHttpProviderUtils.constructUserAgent(ChanMgrAsyncHttpProvider.class,
                                                                   config));
        }

        if (!m.equals(HttpMethod.CONNECT)) {
            if (request.getCookies() != null && !request.getCookies().isEmpty()) {
                CookieEncoder httpCookieEncoder = new CookieEncoder(false);
                Iterator<Cookie> ic = request.getCookies().iterator();
                Cookie c;
                org.jboss.netty.handler.codec.http.Cookie cookie;
                while (ic.hasNext()) {
                    c = ic.next();
                    cookie = new DefaultCookie(c.getName(), c.getValue());
                    cookie.setPath(c.getPath());
                    cookie.setMaxAge(c.getMaxAge());
                    cookie.setDomain(c.getDomain());
                    httpCookieEncoder.addCookie(cookie);
                }
                nettyRequest.setHeader(HttpHeaders.Names.COOKIE, httpCookieEncoder.encode());
            }

            String reqType = request.getMethod();
            if (!"GET".equals(reqType) && !"HEAD".equals(reqType) && !"OPTION".equals(reqType) && !"TRACE".equals(reqType)) {

                String bodyCharset = request.getBodyEncoding() == null ? DEFAULT_CHARSET : request.getBodyEncoding();

                // We already have processed the body.
                if (buffer != null && buffer.writerIndex() != 0) {
                    nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, buffer.writerIndex());
                    nettyRequest.setContent(buffer);
                } else if (request.getByteData() != null) {
                    nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(request.getByteData().length));
                    nettyRequest.setContent(ChannelBuffers.wrappedBuffer(request.getByteData()));
                } else if (request.getStringData() != null) {
                    nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(request.getStringData().getBytes(bodyCharset).length));
                    nettyRequest.setContent(ChannelBuffers.wrappedBuffer(request.getStringData().getBytes(bodyCharset)));
                } else if (request.getStreamData() != null) {
                    int[] lengthWrapper = new int[1];
                    byte[] bytes = AsyncHttpProviderUtils.readFully(request.getStreamData(), lengthWrapper);
                    int length = lengthWrapper[0];
                    nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(length));
                    nettyRequest.setContent(ChannelBuffers.wrappedBuffer(bytes, 0, length));
                } else if (request.getParams() != null && !request.getParams().isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (final Entry<String, List<String>> paramEntry : request.getParams()) {
                        final String key = paramEntry.getKey();
                        for (final String value : paramEntry.getValue()) {
                            if (sb.length() > 0) {
                                sb.append("&");
                            }
                            UTF8UrlEncoder.appendEncoded(sb, key);
                            sb.append("=");
                            UTF8UrlEncoder.appendEncoded(sb, value);
                        }
                    }
                    nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(sb.length()));
                    nettyRequest.setContent(ChannelBuffers.wrappedBuffer(sb.toString().getBytes(bodyCharset)));

                    if (!request.getHeaders().containsKey(HttpHeaders.Names.CONTENT_TYPE)) {
                        nettyRequest.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/x-www-form-urlencoded");
                    }

                } else if (request.getParts() != null) {
                    int lenght = computeAndSetContentLength(request, nettyRequest);

                    if (lenght == -1) {
                        lenght = MAX_BUFFERED_BYTES;
                    }

                    MultipartRequestEntity mre = AsyncHttpProviderUtils.createMultipartRequestEntity(request.getParts(), request.getParams());

                    nettyRequest.setHeader(HttpHeaders.Names.CONTENT_TYPE, mre.getContentType());
                    nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(mre.getContentLength()));

                    /**
                     * TODO: AHC-78: SSL + zero copy isn't supported by the MultiPart class and pretty complex to implements.
                     */

                    if (isSecure(uri)) {
                        ChannelBuffer b = ChannelBuffers.dynamicBuffer(lenght);
                        mre.writeRequest(new ChannelBufferOutputStream(b));
                        nettyRequest.setContent(b);
                    }
                } else if (request.getEntityWriter() != null) {
                    int lenght = computeAndSetContentLength(request, nettyRequest);

                    if (lenght == -1) {
                        lenght = MAX_BUFFERED_BYTES;
                    }

                    ChannelBuffer b = ChannelBuffers.dynamicBuffer(lenght);
                    request.getEntityWriter().writeEntity(new ChannelBufferOutputStream(b));
                    nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, b.writerIndex());
                    nettyRequest.setContent(b);
                } else if (request.getFile() != null) {
                    File file = request.getFile();
                    if (!file.isFile()) {
                        throw new IOException(String.format("File %s is not a file or doesn't exist", file.getAbsolutePath()));
                    }
                    nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, file.length());
                }
            }
        }
        return nettyRequest;
    }

    private static boolean isWebSocket(URI uri) {
		// TODO Auto-generated method stub
		return false;
	}

	private static boolean isSecure(URI uri) {
		// TODO Auto-generated method stub
		return false;
	}

	public void close() {
        isClose.set(true);
        try {
            connectionsPool.destroy();
            config.executorService().shutdown();
            config.reaper().shutdown();
            chanMgr.stop();
        } catch (Throwable t) {
            log.warn("Unexpected error on close", t);
        }
    }

    /* @Override */

    public Response prepareResponse(final HttpResponseStatus status,
                                    final HttpResponseHeaders headers,
                                    final List<HttpResponseBodyPart> bodyParts) {
        return new NettyResponse(status, headers, bodyParts);
    }

    /* @Override */

    public <T> ListenableFuture<T> execute(Request request, final AsyncHandler<T> asyncHandler) throws IOException {
    	TCPChannel channel = chanMgr.createTCPChannel("channel"+getNextNum());
    	
        String requestUrl = request.getUrl();
        URI uri = AsyncHttpProviderUtils.createUri(requestUrl);

        InetSocketAddress remoteAddress = null;
        if (request.getInetAddress() != null) {
            remoteAddress = new InetSocketAddress(request.getInetAddress(), AsyncHttpProviderUtils.getPort(uri));
        }

       // connectionsPool.
        
        HttpRequest nettyRequest = null;

        ChanMgrResponseFuture<T> future = new ChanMgrResponseFuture<T>(uri, request, asyncHandler, nettyRequest, config.getRequestTimeoutInMs(), config.getIdleConnectionTimeoutInMs(), this);
        
        AConnectListener<T> listener = new AConnectListener<T>(future);
        boolean fromPool = false;
        
        if(!fromPool) {
        	FutureOperation futureOp = channel.connect(remoteAddress);
        	futureOp.setListener(listener);
        } else {
        	//we are re-using the channel, so just perform the write only then...
        	
        	//no need to wait for connection so just write the request out
        	listener.performWrite(channel);
        }
    	
    	return future;
    }

    private synchronized int getNextNum() {
    	return chanCount++;
    }
    protected static int requestTimeout(AsyncHttpClientConfig config, PerRequestConfig perRequestConfig) {
        int result;
        if (perRequestConfig != null) {
            int prRequestTimeout = perRequestConfig.getRequestTimeoutInMs();
            result = (prRequestTimeout != 0 ? prRequestTimeout : config.getRequestTimeoutInMs());
        } else {
            result = config.getRequestTimeoutInMs();
        }
        return result;
    }

    private Realm kerberosChallenge(List<String> proxyAuth,
                                    Request request,
                                    ProxyServer proxyServer,
                                    FluentCaseInsensitiveStringsMap headers,
                                    Realm realm,
                                    ChanMgrResponseFuture<?> future) throws NTLMEngineException {

        URI uri = URI.create(request.getUrl());
        String host = request.getVirtualHost() == null ? AsyncHttpProviderUtils.getHost(uri) : request.getVirtualHost();
        String server = proxyServer == null ? host : proxyServer.getHost();
        try {
            String challengeHeader = spnegoEngine.generateToken(server);
            headers.remove(HttpHeaders.Names.AUTHORIZATION);
            headers.add(HttpHeaders.Names.AUTHORIZATION, "Negotiate " + challengeHeader);

            Realm.RealmBuilder realmBuilder;
            if (realm != null) {
                realmBuilder = new Realm.RealmBuilder().clone(realm);
            } else {
                realmBuilder = new Realm.RealmBuilder();
            }
            return realmBuilder.setUri(uri.getPath())
                    .setMethodName(request.getMethod())
                    .setScheme(Realm.AuthScheme.KERBEROS)
                    .build();
        } catch (Throwable throwable) {
            if (proxyAuth.contains("NTLM")) {
                return ntlmChallenge(proxyAuth, request, proxyServer, headers, realm, future);
            }
            abort(future, throwable);
            return null;
        }
    }

    private void abort(ChanMgrResponseFuture<?> future, Throwable throwable) {
		// TODO Auto-generated method stub
		
	}

	private Realm ntlmChallenge(List<String> wwwAuth,
                                Request request,
                                ProxyServer proxyServer,
                                FluentCaseInsensitiveStringsMap headers,
                                Realm realm,
                                ChanMgrResponseFuture<?> future) throws NTLMEngineException {

        boolean useRealm = (proxyServer == null && realm != null);

        String ntlmDomain = useRealm ? realm.getNtlmDomain() : proxyServer.getNtlmDomain();
        String ntlmHost = useRealm ? realm.getNtlmHost() : proxyServer.getHost();
        String principal = useRealm ? realm.getPrincipal() : proxyServer.getPrincipal();
        String password = useRealm ? realm.getPassword() : proxyServer.getPassword();

        Realm newRealm;
        if (realm != null && !realm.isNtlmMessageType2Received()) {
            String challengeHeader = ntlmEngine.generateType1Msg(ntlmDomain, ntlmHost);

            headers.add(HttpHeaders.Names.AUTHORIZATION, "NTLM " + challengeHeader);
            newRealm = new Realm.RealmBuilder().clone(realm).setScheme(realm.getAuthScheme())
                    .setUri(URI.create(request.getUrl()).getPath())
                    .setMethodName(request.getMethod())
                    .setNtlmMessageType2Received(true)
                    .build();
            future.getAndSetAuth(false);
        } else {
            headers.remove(HttpHeaders.Names.AUTHORIZATION);

            if (wwwAuth.get(0).startsWith("NTLM ")) {
                String serverChallenge = wwwAuth.get(0).trim().substring("NTLM ".length());
                String challengeHeader = ntlmEngine.generateType3Msg(principal, password,
                        ntlmDomain, ntlmHost, serverChallenge);

                headers.add(HttpHeaders.Names.AUTHORIZATION, "NTLM " + challengeHeader);
            }

            Realm.RealmBuilder realmBuilder;
            Realm.AuthScheme authScheme;
            if (realm != null) {
                realmBuilder = new Realm.RealmBuilder().clone(realm);
                authScheme = realm.getAuthScheme();
            } else {
                realmBuilder = new Realm.RealmBuilder();
                authScheme = Realm.AuthScheme.NTLM;
            }
            newRealm = realmBuilder.setScheme(authScheme)
                    .setUri(URI.create(request.getUrl()).getPath())
                    .setMethodName(request.getMethod())
                    .build();
        }

        return newRealm;
    }

    private Realm ntlmProxyChallenge(List<String> wwwAuth,
                                     Request request,
                                     ProxyServer proxyServer,
                                     FluentCaseInsensitiveStringsMap headers,
                                     Realm realm,
                                     ChanMgrResponseFuture<?> future) throws NTLMEngineException {
        future.getAndSetAuth(false);
        headers.remove(HttpHeaders.Names.PROXY_AUTHORIZATION);

        if (wwwAuth.get(0).startsWith("NTLM ")) {
            String serverChallenge = wwwAuth.get(0).trim().substring("NTLM ".length());
            String challengeHeader = ntlmEngine.generateType3Msg(proxyServer.getPrincipal(),
                    proxyServer.getPassword(),
                    proxyServer.getNtlmDomain(),
                    proxyServer.getHost(),
                    serverChallenge);
            headers.add(HttpHeaders.Names.PROXY_AUTHORIZATION, "NTLM " + challengeHeader);
        }
        Realm newRealm;
        Realm.RealmBuilder realmBuilder;
        if (realm != null) {
            realmBuilder = new Realm.RealmBuilder().clone(realm);
        } else {
            realmBuilder = new Realm.RealmBuilder();
        }
        newRealm = realmBuilder//.setScheme(realm.getAuthScheme())
                .setUri(URI.create(request.getUrl()).getPath())
                .setMethodName(request.getMethod())
                .build();

        return newRealm;
    }

    //Simple marker for stopping publishing bytes.

    final static class DiscardEvent {
    }

    private final static int computeAndSetContentLength(Request request, HttpRequest r) {
        int length = (int) request.getContentLength();
        if (length == -1 && r.getHeader(HttpHeaders.Names.CONTENT_LENGTH) != null) {
            length = Integer.valueOf(r.getHeader(HttpHeaders.Names.CONTENT_LENGTH));
        }

        if (length >= 0) {
            r.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(length));
        }
        return length;
    }

    public AsyncHttpClientConfig getConfig() {
        return config;
    }

    private static class NonConnectionsPool implements ConnectionsPool<String, TCPChannel> {

        public boolean offer(String uri, TCPChannel connection) {
            return false;
        }

        public TCPChannel poll(String uri) {
            return null;
        }

        public boolean removeAll(TCPChannel connection) {
            return false;
        }

        public boolean canCacheConnection() {
            return true;
        }

        public void destroy() {
        }
    }
   
}
