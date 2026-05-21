package org.registryagent;

import org.registryagent.snapshot.GameEvent;
import org.registryagent.snapshot.SnapshotAgent;

import java.sql.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class DbQueueProcessor {

    private static final Logger log = Logger.getLogger(DbQueueProcessor.class.getName());

    private final AgentAuthServer agentAuthServer;
    private final SnapshotAgent   snapshotAgent;
    private final String dbUrl;
    private final String dbUser;
    private final String dbPassword;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "db-queue-processor");
                t.setDaemon(true);
                return t;
            });

    private Connection conn;

    public DbQueueProcessor(AgentAuthServer agentAuthServer, SnapshotAgent snapshotAgent,
                            String dbUrl, String dbUser, String dbPassword) {
        this.agentAuthServer = agentAuthServer;
        this.snapshotAgent   = snapshotAgent;
        this.dbUrl      = dbUrl;
        this.dbUser     = dbUser;
        this.dbPassword = dbPassword;
    }

    public void start() throws SQLException {
        conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
        ensureTables();
        scheduler.scheduleAtFixedRate(this::processQueues, 0, 500, TimeUnit.MILLISECONDS);
        log.info("DbQueueProcessor started - polling every 500 ms");
    }

    private void ensureTables() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS registry_auth_queue (
                    id               INT AUTO_INCREMENT PRIMARY KEY,
                    player_guid      INT         NOT NULL,
                    pubkey           VARCHAR(64) NOT NULL,
                    character_id     VARCHAR(36) DEFAULT NULL,
                    status           VARCHAR(12) NOT NULL DEFAULT 'pending',
                    session_token    VARCHAR(36) DEFAULT NULL,
                    resolved_char_id VARCHAR(36) DEFAULT NULL,
                    reject_reason    VARCHAR(255) DEFAULT NULL,
                    created_at       BIGINT      NOT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS registry_event_queue (
                    id             INT AUTO_INCREMENT PRIMARY KEY,
                    session_token  VARCHAR(36) NOT NULL,
                    event_name     VARCHAR(32) NOT NULL,
                    processed      TINYINT(1)  NOT NULL DEFAULT 0,
                    created_at     BIGINT      NOT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS registry_character_map (
                    registry_uuid  VARCHAR(36) NOT NULL PRIMARY KEY,
                    char_guid      INT         NOT NULL,
                    UNIQUE KEY uq_char_guid (char_guid)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS registry_character_stats (
                    guid          INT UNSIGNED  NOT NULL PRIMARY KEY,
                    strength      INT UNSIGNED  NOT NULL DEFAULT 0,
                    agility       INT UNSIGNED  NOT NULL DEFAULT 0,
                    stamina       INT UNSIGNED  NOT NULL DEFAULT 0,
                    intellect     INT UNSIGNED  NOT NULL DEFAULT 0,
                    spirit        INT UNSIGNED  NOT NULL DEFAULT 0,
                    maxhealth     INT UNSIGNED  NOT NULL DEFAULT 0,
                    maxmana       INT UNSIGNED  NOT NULL DEFAULT 0,
                    armor         INT UNSIGNED  NOT NULL DEFAULT 0,
                    attack_power  INT UNSIGNED  NOT NULL DEFAULT 0,
                    updated_at    BIGINT        NOT NULL DEFAULT 0
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        }
        log.info("DB queue tables ready");
    }

    private void processQueues() {
        try {
            processAuthQueue();
            processEventQueue();
        } catch (Exception e) {
            log.warning("DbQueueProcessor error: " + e.getMessage());
            reconnectIfNeeded();
        }
    }

    private void processAuthQueue() throws SQLException {
        String select = "SELECT id, player_guid, pubkey, character_id, character_id IS NULL AS is_new"
                      + " FROM registry_auth_queue WHERE status = 'pending' LIMIT 10";

        try (PreparedStatement ps = conn.prepareStatement(select);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                int    id          = rs.getInt("id");
                int    playerGuid  = rs.getInt("player_guid");
                String pubkey      = rs.getString("pubkey");
                String characterId = rs.getString("character_id");
                boolean isNew      = rs.getBoolean("is_new");

                // Claim the row to prevent double-processing
                try (PreparedStatement claim = conn.prepareStatement(
                        "UPDATE registry_auth_queue SET status='processing' WHERE id=? AND status='pending'")) {
                    claim.setInt(1, id);
                    if (claim.executeUpdate() == 0) continue;
                }

                AgentAuthServer.LoginResult result = agentAuthServer.processLogin(pubkey, characterId);

                if (result.success()) {
                    ensureCharacterMapping(result.characterId(), playerGuid);

                    try (PreparedStatement upd = conn.prepareStatement(
                            "UPDATE registry_auth_queue SET status='ok', session_token=?, resolved_char_id=? WHERE id=?")) {
                        upd.setString(1, result.sessionToken());
                        upd.setString(2, result.characterId());
                        upd.setInt(3, id);
                        upd.executeUpdate();
                    }
                    log.info("Auth queue: ok - id=" + id + " charId=" + result.characterId());
                } else {
                    try (PreparedStatement upd = conn.prepareStatement(
                            "UPDATE registry_auth_queue SET status='reject', reject_reason=? WHERE id=?")) {
                        upd.setString(1, result.reason());
                        upd.setInt(2, id);
                        upd.executeUpdate();
                    }
                    log.warning("Auth queue: reject - id=" + id + " reason=" + result.reason());
                }
            }
        }
    }

    private void ensureCharacterMapping(String characterId, int playerGuid) throws SQLException {
        if (characterId == null || characterId.isBlank()) {
            return;
        }

        try (PreparedStatement existing = conn.prepareStatement(
                "SELECT char_guid FROM registry_character_map WHERE registry_uuid = ?")) {
            existing.setString(1, characterId);
            try (ResultSet rs = existing.executeQuery()) {
                if (rs.next()) {
                    return;
                }
            }
        }

        try (PreparedStatement map = conn.prepareStatement(
                "INSERT INTO registry_character_map (registry_uuid, char_guid) VALUES (?, ?)")) {
            map.setString(1, characterId);
            map.setInt(2, playerGuid);
            map.executeUpdate();
            log.info("Mapped registry_uuid=" + characterId + " -> char_guid=" + playerGuid);
        }
    }
    private void processEventQueue() throws SQLException {
        String select = "SELECT id, session_token, event_name"
                      + " FROM registry_event_queue WHERE processed = 0 ORDER BY id LIMIT 50";

        try (PreparedStatement ps = conn.prepareStatement(select);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                int    id           = rs.getInt("id");
                String sessionToken = rs.getString("session_token");
                String eventName    = rs.getString("event_name");

                try {
                    GameEvent event = GameEvent.valueOf(eventName.toUpperCase());
                    snapshotAgent.onEvent(sessionToken, event);
                    log.fine("Event queue: fired " + eventName + " token=" + sessionToken.substring(0, 8) + "...");
                } catch (IllegalArgumentException e) {
                    log.warning("Event queue: unknown event '" + eventName + "' - skipping");
                }

                try (PreparedStatement mark = conn.prepareStatement(
                        "UPDATE registry_event_queue SET processed=1 WHERE id=?")) {
                    mark.setInt(1, id);
                    mark.executeUpdate();
                }
            }
        }
    }

    private void reconnectIfNeeded() {
        try {
            if (conn == null || conn.isClosed()) {
                log.warning("DB connection lost - reconnecting...");
                conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
                log.info("DB reconnected");
            }
        } catch (SQLException e) {
            log.severe("DB reconnect failed: " + e.getMessage());
        }
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (conn != null && !conn.isClosed()) conn.close();
        } catch (SQLException ignored) {}
        log.info("DbQueueProcessor stopped");
    }
}
