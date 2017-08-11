/*
 * Copyright 2017, Backblaze Inc. All Rights Reserved.
 * License https://www.backblaze.com/using_b2_code.html
 */
package com.backblaze.b2.client.webApiHttpClient;

import com.backblaze.b2.client.exceptions.B2Exception;
import com.backblaze.b2.util.B2Preconditions;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.ssl.SSLContexts;

import javax.net.ssl.SSLContext;

/**
 * This is the default HttpClientFactory implementation.
 * It's not very configurable from the outside yet.
 */
public class HttpClientFactoryImpl implements HttpClientFactory {
    private final HttpClientConnectionManager connectionManager;
    private final RequestConfig requestConfig;

    private HttpClientFactoryImpl(HttpClientConnectionManager connectionManager,
                          RequestConfig requestConfig) {
        this.connectionManager = connectionManager;
        this.requestConfig = requestConfig;
    }

    @SuppressWarnings("WeakerAccess")
    public static HttpClientFactoryImpl build() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public CloseableHttpClient create() throws B2Exception {
        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .build();
    }

    @Override
    public void close() {
        connectionManager.shutdown();
    }

    /**
     * The factory we're building will have close() called on it and when it
     * does, it will close its connection manager.  Since we don't want to
     * close a connection manager out from under another factory, each Builder
     * is only allowed to execute build() once.
     */
    public static class Builder {
        private static final int DEFAULT_CONNECTION_REQUEST_TIMEOUT_SECONDS = 5;
        private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 5;
        private static final int DEFAULT_SOCKET_TIMEOUT_SECONDS = 20;

        private static final int DEFAULT_MAX_TOTAL_CONNECTIONS_IN_POOL = 100;
        private static final int DEFAULT_MAX_CONNECTIONS_PER_ROUTE = 100;

        private boolean builtOneAlready;

        // for RequestConfig
        private int connectionRequestTimeoutSeconds = DEFAULT_CONNECTION_REQUEST_TIMEOUT_SECONDS;
        private int connectTimeoutSeconds = DEFAULT_CONNECT_TIMEOUT_SECONDS;
        private int socketTimeoutSeconds = DEFAULT_SOCKET_TIMEOUT_SECONDS;

        // for connection pool
        private int maxTotalConnectionsInPool = DEFAULT_MAX_TOTAL_CONNECTIONS_IN_POOL;
        private int maxConnectionsPerRoute = DEFAULT_MAX_CONNECTIONS_PER_ROUTE;

        public Builder setConnectionRequestTimeoutSeconds(int connectionRequestTimeoutSeconds) {
            this.connectionRequestTimeoutSeconds = connectionRequestTimeoutSeconds;
            return this;
        }

        public Builder setConnectTimeoutSeconds(int connectTimeoutSeconds) {
            this.connectTimeoutSeconds = connectTimeoutSeconds;
            return this;
        }

        public Builder setSocketTimeoutSeconds(int socketTimeoutSeconds) {
            this.socketTimeoutSeconds = socketTimeoutSeconds;
            return this;
        }

        public Builder setMaxTotalConnectionsInPool(int maxTotalConnectionsInPool) {
            this.maxTotalConnectionsInPool = maxTotalConnectionsInPool;
            return this;
        }

        public Builder setMaxConnectionsPerRoute(int maxConnectionsPerRoute) {
            this.maxConnectionsPerRoute = maxConnectionsPerRoute;
            return this;
        }


        @SuppressWarnings("WeakerAccess")
        public HttpClientFactoryImpl build() {
            B2Preconditions.checkState(!builtOneAlready, "called build() more than once?!");
            builtOneAlready = true;

            return new HttpClientFactoryImpl(
                    createConnectionManager(),
                    createRequestConfig());
        }

        private RequestConfig createRequestConfig() {
            return RequestConfig.custom()
                    .setConnectionRequestTimeout(connectionRequestTimeoutSeconds * 1000) // time waiting for cxn from pool
                    .setConnectTimeout(connectTimeoutSeconds * 1000) // time waiting for remote server to connect
                    .setSocketTimeout(socketTimeoutSeconds * 1000) // time waiting for answer after connecting
                    .build();

        }

        private HttpClientConnectionManager createConnectionManager() {
            // For SSL/TLS,
            //   HttpClient says it uses Java Secure Socket Extension:
            //     https://hc.apache.org/httpcomponents-client-ga/tutorial/html/connmgmt.html
            //   java 8 defaults to TLS/1.2.
            //     https://blogs.oracle.com/java-platform-group/jdk-8-will-use-tls-12-as-default
            //   for hostname verification,
            //     HttpClient's DefaultHostnameVerifier is fine and allows wildcard names.
            //     i've seen some code use ALLOW_ALL_HOSTNAME_VERIFIER, but that's deprecated and bad.
            //
            // we are NOT using the default registry because the default supports http
            // and there's no reason for the SDK to do http, ever.

            SSLContext sslcontext = SSLContexts.createDefault();
            ConnectionSocketFactory sslFactory = new SSLConnectionSocketFactory(sslcontext);

            // Copied from: https://hc.apache.org/httpcomponents-client-ga/tutorial/html/connmgmt.html
            Registry<ConnectionSocketFactory> registry =
                    RegistryBuilder.<ConnectionSocketFactory>create()
                            .register("https", sslFactory)
                            .build();

            final PoolingHttpClientConnectionManager mgr = new PoolingHttpClientConnectionManager(registry);
            mgr.setMaxTotal(maxTotalConnectionsInPool);
            mgr.setDefaultMaxPerRoute(maxConnectionsPerRoute);
            return mgr;
        }
    }
}
