package org.registryagent.auth;

import org.registryagent.model.AuthChallenge;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


public class ChallengeManager {

    private static final int NONCE_BYTES = 32;
    private static final long PRUNE_INTERVAL_SECONDS = 60;

    private final SecureRandom random = new SecureRandom();
    private final HexFormat hex = HexFormat.of();

    private final Map<String, AuthChallenge> pending = new ConcurrentHashMap<>();

    private final ScheduledExecutorService pruner =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "challenge-pruner");
                t.setDaemon(true);
                return t;
            });

    public ChallengeManager() {
        pruner.scheduleAtFixedRate(
                this::pruneExpired,
                PRUNE_INTERVAL_SECONDS,
                PRUNE_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
    }

    public AuthChallenge issue(String playerPubKey) {
        byte[] nonceBytes = new byte[NONCE_BYTES];
        random.nextBytes(nonceBytes);
        String nonce = hex.formatHex(nonceBytes);

        AuthChallenge challenge = new AuthChallenge(nonce, playerPubKey);
        pending.put(playerPubKey, challenge);
        return challenge;
    }


    public AuthChallenge consume(String playerPubKey) {
        AuthChallenge challenge = pending.remove(playerPubKey);
        if (challenge == null) return null;
        if (challenge.isExpired()) return null;
        return challenge;
    }

    public boolean hasPending(String playerPubKey) {
        AuthChallenge challenge = pending.get(playerPubKey);
        return challenge != null && !challenge.isExpired();
    }

    private void pruneExpired() {
        pending.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    public int pendingCount() {
        return pending.size();
    }

    public void shutdown() {
        pruner.shutdown();
    }
}
