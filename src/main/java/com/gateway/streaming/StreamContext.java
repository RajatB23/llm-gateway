package com.gateway.streaming;

import java.util.concurrent.atomic.AtomicBoolean;

public class StreamContext {

    private final String requestId;
    private final AtomicBoolean clientConnected = new AtomicBoolean(true);
    private final AtomicBoolean upstreamOpen = new AtomicBoolean(false);
    private final AtomicBoolean contentSent = new AtomicBoolean(false);

    public StreamContext(String requestId) {
        this.requestId = requestId;
    }

    public String getRequestId() {
        return requestId;
    }

    public boolean isClientConnected() {
        return clientConnected.get();
    }

    public void markClientDisconnected() {
        clientConnected.set(false);
    }

    public boolean isUpstreamOpen() {
        return upstreamOpen.get();
    }

    public void markUpstreamOpen() {
        upstreamOpen.set(true);
    }

    public void markUpstreamClosed() {
        upstreamOpen.set(false);
    }

    public boolean hasContentSent() {
        return contentSent.get();
    }

    public void markContentSent() {
        contentSent.set(true);
    }
}
