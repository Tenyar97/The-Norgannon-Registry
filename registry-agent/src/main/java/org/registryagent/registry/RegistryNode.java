package org.registryagent.registry;

import java.time.Instant;

public class RegistryNode {

    private static final int  MAX_FAILURES        = 3;
    private static final long CIRCUIT_BREAK_MS    = 60_000L;

    private final String baseUrl;

    private int     failureCount    = 0;
    private long    lastFailureTime = 0;
    private boolean healthy         = true;

    public RegistryNode(String baseUrl) {
        this.baseUrl = baseUrl.stripTrailing();
    }

    public String fetchUrl(String characterId) {
        return baseUrl + "/character/" + characterId;
    }

    public String pushUrl() {
        return baseUrl + "/snapshot";
    }

    public String pingUrl() {
        return baseUrl + "/ping";
    }

    public synchronized void recordSuccess() {
        failureCount = 0;
        healthy      = true;
    }

    public synchronized void recordFailure() {
        failureCount++;
        lastFailureTime = System.currentTimeMillis();
        if (failureCount >= MAX_FAILURES) {
            healthy = false;
        }
    }

    public synchronized boolean isAvailable() {
        if (healthy) return true;

        long elapsed = System.currentTimeMillis() - lastFailureTime;
        return elapsed >= CIRCUIT_BREAK_MS;
    }

    public String getBaseUrl()    { return baseUrl; }
    public boolean isHealthy()    { return healthy; }
    public int getFailureCount()  { return failureCount; }

    @Override
    public String toString() {
        return "RegistryNode{url='" + baseUrl + "', healthy=" + healthy +
               ", failures=" + failureCount + "}";
    }
}
