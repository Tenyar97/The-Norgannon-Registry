package org.registryagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.registryagent.auth.AuthServer;
import org.registryagent.model.LoginSession;
import org.registryagent.snapshot.GameEvent;
import org.registryagent.snapshot.ImportProfile;
import org.registryagent.snapshot.ImportProfileLoader;
import org.registryagent.snapshot.SnapshotAgent;
import org.registryagent.snapshot.TrustedServer;
import org.registryagent.snapshot.TrustStore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

public class AgentAuthServer {

	private static final Logger log = Logger.getLogger(AgentAuthServer.class.getName());

	public static final int PORT = 8100;
	private static final String HOST = "127.0.0.1";

	private static final String COMPANION_URL = "http://127.0.0.1:7742";

	private final HttpServer server;
	private final AuthServer authServer;
	private final SnapshotAgent snapshotAgent;
	private final String serverId;
	private final ObjectMapper mapper = new ObjectMapper();
	private final HttpClient http = HttpClient.newHttpClient();
	private final ImportProfileLoader profileLoader = new ImportProfileLoader();
	private final TrustStore trustStore = TrustStore.load();

	public AgentAuthServer(AuthServer authServer, SnapshotAgent snapshotAgent, String serverId) throws IOException {
		this.authServer = authServer;
		this.snapshotAgent = snapshotAgent;
		this.serverId = serverId;

		snapshotAgent.setTrustStore(trustStore);
		snapshotAgent.setProfileLoader(profileLoader);

		InetSocketAddress address = new InetSocketAddress(HOST, PORT);
		server = HttpServer.create(address, 0);

		server.createContext("/auth/challenge", exchange -> {
			if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
				sendError(exchange, 405, "Method not allowed");
				return;
			}
			handleChallenge(exchange);
		});

		server.createContext("/auth/verify", exchange -> {
			if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
				sendError(exchange, 405, "Method not allowed");
				return;
			}
			handleVerify(exchange);
		});

		server.createContext("/health", exchange -> {
			sendJson(exchange, 200, Map.of("status", "ok"));
		});

		server.createContext("/auth/login", exchange -> {
			if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
				sendError(exchange, 405, "Method not allowed");
				return;
			}
			handleLogin(exchange);
		});

		server.createContext("/snapshot/event", exchange -> {
			if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
				sendError(exchange, 405, "Method not allowed");
				return;
			}
			handleSnapshotEvent(exchange);
		});

		server.createContext("/admin/import", exchange -> {
			if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
				sendError(exchange, 405, "Method not allowed");
				return;
			}
			handleAdminImport(exchange);
		});

		server.createContext("/admin/trust", exchange -> {
			switch (exchange.getRequestMethod().toUpperCase()) {
				case "GET"    -> handleTrustList(exchange);
				case "POST"   -> handleTrustAdd(exchange);
				case "DELETE" -> handleTrustRemove(exchange);
				default       -> sendError(exchange, 405, "Method not allowed");
			}
		});

		server.createContext("/debug/session", exchange -> {
			if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
				sendError(exchange, 405, "Method not allowed");
				return;
			}
			handleDebugSession(exchange);
		});

		server.createContext("/debug/event", exchange -> {
			if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
				sendError(exchange, 405, "Method not allowed");
				return;
			}
			handleDebugEvent(exchange);
		});

		server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
	}


	private void handleChallenge(HttpExchange exchange) throws IOException {
		Map<?, ?> body = readJson(exchange);
		if (body == null) {
			sendError(exchange, 400, "Invalid JSON");
			return;
		}

		String playerPubKey = (String) body.get("player_pub_key");
		String characterId = (String) body.get("character_id");

		if (playerPubKey == null || playerPubKey.isBlank()) {
			sendError(exchange, 400, "Missing player_pub_key");
			return;
		}

		if ("new".equals(characterId) || "".equals(characterId)) {
			characterId = null;
		}

		AuthServer.ChallengeResult result = authServer.requestChallenge(playerPubKey, characterId);

		if (!result.isIssued()) {
			sendJson(exchange, 400, Map.of("error", result.getReason()));
			return;
		}

		log.info("Challenge issued for pubkey=" + playerPubKey.substring(0, 8) + "...");
		sendJson(exchange, 200, Map.of("nonce", result.getNonce()));
	}


	private void handleVerify(HttpExchange exchange) throws IOException {
		Map<?, ?> body = readJson(exchange);
		if (body == null) {
			sendError(exchange, 400, "Invalid JSON");
			return;
		}

		String playerPubKey = (String) body.get("player_pub_key");
		String characterId = (String) body.get("character_id");
		String signature = (String) body.get("signature");

		if (playerPubKey == null || signature == null) {
			sendError(exchange, 400, "Missing required fields");
			return;
		}

		if ("new".equals(characterId) || "".equals(characterId)) {
			characterId = null;
		}

		// First login
		if (characterId == null) {
			characterId = UUID.randomUUID().toString();
		}

		AuthServer.LoginResult result = authServer.handleChallengeResponse(playerPubKey, characterId, signature);

		if (!result.isSuccess()) {
			log.warning("Auth rejected - pubkey=" + playerPubKey.substring(0, 8) + "... reason=" + result.getReason());
			sendJson(exchange, 200, Map.of("success", false, "reason", result.getReason()));
			return;
		}

		log.info("Auth success - pubkey=" + playerPubKey.substring(0, 8) + "... characterId=" + characterId);

		sendJson(exchange, 200,
				Map.of("success", true, "character_id", characterId, "session_token", result.getSessionToken()));
	}

	private void handleLogin(HttpExchange exchange) throws IOException {
		Map<?, ?> body = readJson(exchange);
		if (body == null) {
			sendError(exchange, 400, "Invalid JSON");
			return;
		}

		String pubkey = (String) body.get("player_pub_key");
		if (pubkey == null || pubkey.isBlank()) {
			sendError(exchange, 400, "Missing player_pub_key");
			return;
		}

		String characterId = (String) body.get("character_id");
		if (characterId == null || characterId.isBlank() || "new".equals(characterId)) {
			characterId = UUID.randomUUID().toString();
		}

		String authToken = serverId + ":" + pubkey;
		String playerSignature = signViaCompanion(authToken);
		if (playerSignature == null) {
			log.warning(
					"Login failed - could not reach companion for signing. pubkey=" + pubkey.substring(0, 8) + "...");
			sendJson(exchange, 503, Map.of("success", false, "reason", "Companion unreachable - is it running?"));
			return;
		}

		String sessionToken = UUID.randomUUID().toString();
		LoginSession session = new LoginSession(sessionToken, pubkey, characterId, playerSignature,
				System.currentTimeMillis());
		authServer.injectSession(session);

		log.info("Login - pubkey=" + pubkey.substring(0, 8) + "... characterId=" + characterId + " token="
				+ sessionToken.substring(0, 8) + "...");
		sendJson(exchange, 200, Map.of("success", true, "session_token", sessionToken, "character_id", characterId));
	}

	private void handleSnapshotEvent(HttpExchange exchange) throws IOException {
		Map<?, ?> body = readJson(exchange);
		if (body == null) {
			sendError(exchange, 400, "Invalid JSON");
			return;
		}

		String sessionToken = (String) body.get("session_token");
		String eventName = (String) body.get("event");

		if (sessionToken == null || eventName == null) {
			sendError(exchange, 400, "Missing session_token or event");
			return;
		}

		GameEvent event;
		try {
			event = GameEvent.valueOf(eventName.toUpperCase());
		} catch (IllegalArgumentException e) {
			sendError(exchange, 400, "Unknown event: " + eventName);
			return;
		}

		snapshotAgent.onEvent(sessionToken, event);
		log.fine("Event fired - token=" + sessionToken.substring(0, 8) + "... event=" + event);
		sendJson(exchange, 200, Map.of("ok", true));
	}

	private void handleDebugSession(HttpExchange exchange) throws IOException {
		Map<?, ?> body = readJson(exchange);
		if (body == null) {
			sendError(exchange, 400, "Invalid JSON");
			return;
		}

		String pubkey = (String) body.get("pubkey");
		if (pubkey == null || pubkey.isBlank()) {
			sendError(exchange, 400, "Missing pubkey");
			return;
		}

		String characterId = (String) body.get("character_id");
		if (characterId == null || characterId.isBlank()) {
			characterId = UUID.randomUUID().toString();
		}

		String authToken = serverId + ":" + pubkey;
		String playerSignature = signViaCompanion(authToken);
		if (playerSignature == null) {
			sendError(exchange, 503, "Could not reach companion at " + COMPANION_URL + " - is it running?");
			return;
		}

		String sessionToken = UUID.randomUUID().toString();
		LoginSession session = new LoginSession(sessionToken, pubkey, characterId, playerSignature,
				System.currentTimeMillis());
		authServer.injectSession(session);

		log.info("[DEBUG] Session injected - characterId=" + characterId + " token=" + sessionToken.substring(0, 8)
				+ "...");
		sendJson(exchange, 200, Map.of("session_token", sessionToken, "character_id", characterId));
	}

	private String signViaCompanion(String message) {
		try {
			String reqBody = mapper.writeValueAsString(Map.of("message", message));
			HttpRequest req = HttpRequest.newBuilder().uri(URI.create(COMPANION_URL + "/sign"))
					.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(reqBody))
					.build();
			HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() != 200) {
				log.warning("[DEBUG] Companion /sign returned " + resp.statusCode());
				return null;
			}
			Map<?, ?> data = mapper.readValue(resp.body(), Map.class);
			return (String) data.get("signature");
		} catch (Exception e) {
			log.warning("[DEBUG] Failed to sign via companion: " + e.getMessage());
			return null;
		}
	}

	// LOGIN|LOGOUT|HEARTBEAT|LEVEL_UP|ITEM_ACQUIRED|QUEST_COMPLETED|DEATH
	
	private void handleDebugEvent(HttpExchange exchange) throws IOException {
		Map<?, ?> body = readJson(exchange);
		if (body == null) {
			sendError(exchange, 400, "Invalid JSON");
			return;
		}

		String sessionToken = (String) body.get("session_token");
		String eventName = (String) body.get("event");

		if (sessionToken == null || eventName == null) {
			sendError(exchange, 400, "Missing session_token or event");
			return;
		}

		GameEvent event;
		try {
			event = GameEvent.valueOf(eventName.toUpperCase());
		} catch (IllegalArgumentException e) {
			sendError(exchange, 400, "Unknown event: " + eventName);
			return;
		}

		snapshotAgent.onEvent(sessionToken, event);
		log.info("[DEBUG] Event fired - token=" + sessionToken.substring(0, 8) + "... event=" + event);
		sendJson(exchange, 200, Map.of("ok", true));
	}

	private void handleAdminImport(HttpExchange exchange) throws IOException {
		Map<?, ?> body = readJson(exchange);
		if (body == null) {
			sendError(exchange, 400, "Invalid JSON");
			return;
		}

		String characterId  = (String) body.get("character_id");
		String playerPubKey = (String) body.get("player_pub_key");

		if (characterId == null || characterId.isBlank()) {
			sendError(exchange, 400, "Missing character_id");
			return;
		}
		if (playerPubKey == null || playerPubKey.isBlank()) {
			sendError(exchange, 400, "Missing player_pub_key");
			return;
		}

		ImportProfile profile = resolveProfile(body.get("profile"));
		String profileName = profile != null ? profile.getName() : "permissive";

		log.info("Admin import requested - characterId=" + characterId
				+ " pubkey=" + playerPubKey.substring(0, Math.min(8, playerPubKey.length())) + "..."
				+ " profile=" + profileName);

		boolean ok = snapshotAgent.importFromRegistry(characterId, playerPubKey, profile);

		if (ok) {
			sendJson(exchange, 200, Map.of(
					"ok",      true,
					"profile", profileName,
					"message", "Import applied for characterId=" + characterId));
		} else {
			sendJson(exchange, 200, Map.of(
					"ok",     false,
					"reason", "No snapshot found in registry for characterId=" + characterId
							  + " - check that the player has authenticated at least once."));
		}
	}

	private ImportProfile resolveProfile(Object raw) {
		if (raw == null) return null;

		if (raw instanceof String name) {
			ImportProfile profile = profileLoader.load(name);
			if (profile == null) {
				log.warning("Import profile '" + name + "' not found in profiles/ - using permissive defaults");
			}
			return profile;
		}

		if (raw instanceof Map<?, ?>) {
			try {
				return mapper.convertValue(raw, ImportProfile.class);
			} catch (Exception e) {
				log.warning("Failed to deserialize inline import profile: " + e.getMessage());
				return null;
			}
		}

		log.warning("Unrecognised 'profile' field type: " + raw.getClass().getSimpleName());
		return null;
	}

	private void handleTrustList(HttpExchange exchange) throws IOException {
		var servers = trustStore.list().stream()
				.map(s -> Map.of(
						"pub_key",         s.getPubKey(),
						"label",           s.getLabel() != null ? s.getLabel() : "",
						"added_at",        s.getAddedAt() != null ? s.getAddedAt() : "",
						"default_profile", s.getDefaultProfile() != null ? s.getDefaultProfile() : ""))
				.toList();

		sendJson(exchange, 200, Map.of(
				"trust_all", trustStore.isTrustAll(),
				"count",     servers.size(),
				"servers",   servers));
	}

	private void handleTrustAdd(HttpExchange exchange) throws IOException {
		Map<?, ?> body = readJson(exchange);
		if (body == null) { sendError(exchange, 400, "Invalid JSON"); return; }

		String pubKey  = (String) body.get("pub_key");
		String label   = body.get("label") != null ? (String) body.get("label") : "";
		String profile = (String) body.get("default_profile");

		if (pubKey == null || pubKey.isBlank()) {
			sendError(exchange, 400, "Missing pub_key");
			return;
		}

		TrustedServer entry = new TrustedServer(pubKey.toLowerCase(), label, null, profile);
		trustStore.add(entry);

		log.info("Trust list updated - added server label='" + label + "' pubKey=" + pubKey.substring(0, Math.min(16, pubKey.length())) + "...");

		sendJson(exchange, 200, Map.of(
				"ok",      true,
				"message", "Server '" + label + "' added to trust list. "
						   + "This server is now in strict mode - only listed servers can be imported from."));
	}
	
	private void handleTrustRemove(HttpExchange exchange) throws IOException {
		Map<?, ?> body = readJson(exchange);
		if (body == null) { sendError(exchange, 400, "Invalid JSON"); return; }

		String pubKey = (String) body.get("pub_key");
		if (pubKey == null || pubKey.isBlank()) {
			sendError(exchange, 400, "Missing pub_key");
			return;
		}

		boolean removed = trustStore.remove(pubKey);
		if (removed) {
			sendJson(exchange, 200, Map.of(
					"ok",      true,
					"message", "Server removed from trust list."));
		} else {
			sendJson(exchange, 200, Map.of(
					"ok",      false,
					"message", "No entry found for pub_key=" + pubKey));
		}
	}

	public record LoginResult(boolean success, String sessionToken, String characterId, String reason) {
	}

	public LoginResult processLogin(String pubkey, String characterId) {
		if (pubkey == null || pubkey.isBlank())
			return new LoginResult(false, null, null, "Missing pubkey");

		if (characterId == null || characterId.isBlank() || "new".equals(characterId))
			characterId = UUID.randomUUID().toString();

		String playerSignature = signViaCompanion(serverId + ":" + pubkey);
		if (playerSignature == null) {
			log.warning("processLogin - companion unreachable for pubkey=" + pubkey.substring(0, 8) + "...");
			return new LoginResult(false, null, null, "Companion unreachable - is it running?");
		}

		String sessionToken = UUID.randomUUID().toString();
		LoginSession session = new LoginSession(sessionToken, pubkey, characterId, playerSignature,
				System.currentTimeMillis());
		authServer.injectSession(session);

		log.info("Login (DB queue) - pubkey=" + pubkey.substring(0, 8) + "... characterId=" + characterId);
		return new LoginResult(true, sessionToken, characterId, null);
	}

	public void start() {
		server.start();
		log.info("AgentAuthServer listening on " + HOST + ":" + PORT);
	}

	public void stop() {
		server.stop(2);
		log.info("AgentAuthServer stopped");
	}

	private Map<?, ?> readJson(HttpExchange exchange) throws IOException {
		try (InputStream is = exchange.getRequestBody()) {
			String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
			if (body.isBlank())
				return Map.of();
			return mapper.readValue(body, Map.class);
		} catch (Exception e) {
			return null;
		}
	}

	private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
		byte[] bytes = mapper.writeValueAsBytes(body);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(bytes);
		}
	}

	private void sendError(HttpExchange exchange, int status, String message)
			throws IOException {
		sendJson(exchange, status, Map.of("error", message));
	}
}
