package org.registryagent.snapshot;

import org.registryagent.adapter.ServerAdapter;
import org.registryagent.auth.AuthServer;
import org.registryagent.model.CharacterPayload;
import org.registryagent.model.LoginSession;
import org.registryagent.model.SnapshotRecord;
import org.registryagent.registry.RegistryClient;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class SnapshotAgent {

    private static final Logger log = Logger.getLogger(SnapshotAgent.class.getName());

    private static final long DEBOUNCE_MS        = 5_000L;   // 5 seconds
    private static final long HEARTBEAT_INTERVAL = 15;        // minutes
    private static final long HEARTBEAT_INITIAL  = 15;        // minutes before first heartbeat

    private final AuthServer      authServer;
    private final ServerAdapter   adapter;
    private final SnapshotBuilder builder;
    private final RegistryClient  registryClient;

    private volatile TrustStore         trustStore;
    private volatile ImportProfileLoader profileLoader;

    private final Map<String, ScheduledFuture<?>> debouncedPushes = new ConcurrentHashMap<>();

    private final Map<String, String> createdAtCache = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "snapshot-agent");
                t.setDaemon(true);
                return t;
            });

    public SnapshotAgent(AuthServer authServer,
                         ServerAdapter adapter,
                         SnapshotBuilder builder,
                         RegistryClient registryClient) {
        this.authServer     = authServer;
        this.adapter        = adapter;
        this.builder        = builder;
        this.registryClient = registryClient;
    }

    public void setTrustStore(TrustStore trustStore) {
        this.trustStore = trustStore;
    }

    public void setProfileLoader(ImportProfileLoader profileLoader) {
        this.profileLoader = profileLoader;
    }

    public void onEvent(String sessionToken, GameEvent event) {
        LoginSession session = authServer.getSession(sessionToken);

        if (session == null) {
            log.warning("onEvent called with unknown session token — ignoring. event=" + event);
            return;
        }

        log.fine("Event received — characterId=" + session.getCharacterId() + " event=" + event);

        if (event.isImmediate()) {
            pushNow(session, event);
        } else {
            scheduleDebouncedPush(session, event);
        }
    }

    public void onEventByCharacterId(String characterId, GameEvent event) {
    	authServer.getActiveSessions().stream()
                .filter(s -> s.getCharacterId().equals(characterId))
                .findFirst()
                .ifPresentOrElse(
                        session -> onEvent(session.getSessionToken(), event),
                        () -> log.warning("No active session for characterId=" + characterId)
                );
    }

    private void startHeartbeat(LoginSession session) {
        scheduler.scheduleAtFixedRate(
                () -> {
                    if (authServer.isSessionActive(session.getSessionToken())) {
                        log.fine("Heartbeat firing — characterId=" + session.getCharacterId());
                        pushNow(session, GameEvent.HEARTBEAT);
                    }
                },
                HEARTBEAT_INITIAL,
                HEARTBEAT_INTERVAL,
                TimeUnit.MINUTES
        );
    }

    private void pushNow(LoginSession session, GameEvent trigger) {
        try {
            if (trigger == GameEvent.LOGIN) {
                SnapshotRecord existing = registryClient.fetchLatest(
                        session.getCharacterId(), session.getPlayerPubKey());
                if (existing != null) {
                    builder.seedSequence(session.getCharacterId(), existing.getSequence());
                    createdAtCache.putIfAbsent(session.getCharacterId(), existing.getCreatedAt());
                    log.info("Seeded sequence=" + existing.getSequence() +
                             " for characterId=" + session.getCharacterId());
                }
            }

            CharacterPayload payload = adapter.readCharacter(session.getCharacterId());

            if (payload == null) {
                log.warning("Adapter returned null payload — skipping snapshot. " +
                            "characterId=" + session.getCharacterId());
                return;
            }

            String existingCreatedAt = createdAtCache.get(session.getCharacterId());

            SnapshotRecord record = builder.build(session, payload, existingCreatedAt);

            registryClient.broadcast(record);

            log.info("Snapshot pushed — characterId=" + session.getCharacterId() +
                     " trigger=" + trigger +
                     " sequence=" + record.getSequence());

            if (trigger == GameEvent.LOGIN) {
                startHeartbeat(session);
            }

        } catch (Exception e) {
            log.severe("Snapshot push failed — characterId=" + session.getCharacterId() +
                       " trigger=" + trigger + " error=" + e.getMessage());
        }
    }

    private void scheduleDebouncedPush(LoginSession session, GameEvent trigger) {
        String characterId = session.getCharacterId();

        ScheduledFuture<?> existing = debouncedPushes.remove(characterId);
        if (existing != null) {
            existing.cancel(false);
        }

        ScheduledFuture<?> future = scheduler.schedule(
                () -> {
                    debouncedPushes.remove(characterId);
                    pushNow(session, trigger);
                },
                DEBOUNCE_MS,
                TimeUnit.MILLISECONDS
        );

        debouncedPushes.put(characterId, future);
    }

    public boolean importFromRegistry(String characterId, String playerPubKey) {
        return importFromRegistry(characterId, playerPubKey, null);
    }

    public boolean importFromRegistry(String characterId, String playerPubKey, ImportProfile explicitProfile) {
        log.info("Import requested — characterId=" + characterId
                 + " explicitProfile=" + (explicitProfile != null ? explicitProfile.getName() : "none"));

        SnapshotRecord record = registryClient.fetchLatest(characterId, playerPubKey);
        if (record == null) {
            log.info("Import: no snapshot found on registry for characterId=" + characterId);
            return false;
        }

        CharacterPayload payload = record.getPayload();
        if (payload == null) {
            log.warning("Import: snapshot has null payload — characterId=" + characterId);
            return false;
        }

        TrustStore ts = this.trustStore;
        if (ts != null && !ts.isTrusted(record.getServerPubKey())) {
            log.warning("Import BLOCKED — source server is not trusted."
                    + " characterId=" + characterId
                    + " sourceServerId=" + record.getServerId()
                    + " sourceServerPubKey=" + abbrev(record.getServerPubKey())
                    + " — add this key via POST /admin/trust to allow imports from this server.");
            return false;
        }

        ImportProfile profile = explicitProfile != null
                ? explicitProfile
                : resolveServerDefaultProfile(record.getServerPubKey());

        CharacterPayload filtered = ImportProfileFilter.apply(payload, profile);

        String profileName = profile != null ? profile.getName() : "permissive";
        boolean ok = adapter.writeCharacter(characterId, filtered);
        if (ok) {
            log.info("Import complete — characterId=" + characterId
                    + " source=" + record.getServerId()
                    + " sequence=" + record.getSequence()
                    + " updatedAt=" + record.getUpdatedAt()
                    + " profile=" + profileName);
            if (record.getCreatedAt() != null) {
                createdAtCache.putIfAbsent(characterId, record.getCreatedAt());
            }
        } else {
            log.warning("Import failed (adapter write error) — characterId=" + characterId);
        }
        return ok;
    }

    private ImportProfile resolveServerDefaultProfile(String serverPubKey) {
        TrustStore ts = this.trustStore;
        ImportProfileLoader loader = this.profileLoader;
        if (ts == null || loader == null) return null;

        return ts.getEntry(serverPubKey)
                .map(TrustedServer::getDefaultProfile)
                .filter(name -> name != null && !name.isBlank())
                .map(name -> {
                    ImportProfile p = loader.load(name);
                    if (p == null) {
                        log.warning("Import: server default profile '" + name
                                + "' not found — using permissive");
                    }
                    return p;
                })
                .orElse(null);
    }

    private static String abbrev(String hex) {
        if (hex == null) return "null";
        return hex.length() > 16 ? hex.substring(0, 16) + "..." : hex;
    }

    public void seedCreatedAt(String characterId, String createdAt) {
        createdAtCache.put(characterId, createdAt);
    }

    public void shutdown() {
        log.info("SnapshotAgent shutting down — flushing " +
                 debouncedPushes.size() + " pending snapshots...");

        debouncedPushes.forEach((characterId, future) -> {
            future.cancel(false);
            LoginSession session = authServer.getActiveSessions().stream()
                    .filter(s -> s.getCharacterId().equals(characterId))
                    .findFirst()
                    .orElse(null);
            if (session != null) {
                pushNow(session, GameEvent.LOGOUT);
            }
        });

        debouncedPushes.clear();
        scheduler.shutdown();
    }
}
