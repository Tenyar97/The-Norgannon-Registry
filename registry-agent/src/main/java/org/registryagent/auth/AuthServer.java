package org.registryagent.auth;

import org.registryagent.model.AuthChallenge;
import org.registryagent.model.LoginSession;
import org.registryagent.registry.RegistryClient;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class AuthServer {

	private static final Logger log = Logger.getLogger(AuthServer.class.getName());

	private final ChallengeManager challengeManager;
	private final KeyVerifier keyVerifier;
	private final RegistryClient registryClient;

	private final Map<String, LoginSession> activeSessions = new ConcurrentHashMap<>();

	private final Set<String> bannedKeys = ConcurrentHashMap.newKeySet();

	public AuthServer(ChallengeManager challengeManager, KeyVerifier keyVerifier, RegistryClient registryClient) {
		this.challengeManager = challengeManager;
		this.keyVerifier = keyVerifier;
		this.registryClient = registryClient;
	}

	public ChallengeResult requestChallenge(String playerPubKey, String characterId) {

		if (!keyVerifier.isValidPublicKey(playerPubKey)) {
			log.warning("Rejected login attempt - invalid public key format");
			return ChallengeResult.rejected("Invalid public key format");
		}

		// BANHAMMER
		if (bannedKeys.contains(playerPubKey)) {
			log.info("Rejected login attempt - banned key: " + playerPubKey.substring(0, 8) + "...");
			return ChallengeResult.rejected("Account is banned");
		}

		AuthChallenge challenge = challengeManager.issue(playerPubKey);
		log.fine("Challenge issued for key: " + playerPubKey.substring(0, 8) + "...");
		return ChallengeResult.issued(challenge.getNonce());
	}

	public LoginResult handleChallengeResponse(String playerPubKey, String characterId, String signatureHex) {

		AuthChallenge challenge = challengeManager.consume(playerPubKey);

		if (challenge == null) {
			log.warning("No valid pending challenge for key: " + playerPubKey.substring(0, 8) + "...");
			return LoginResult.rejected("No pending challenge - request a new one");
		}

		boolean valid = keyVerifier.verify(playerPubKey, challenge.getNonce(), signatureHex);

		if (!valid) {
			log.warning("Signature verification failed for key: " + playerPubKey.substring(0, 8) + "...");
			return LoginResult.rejected("Signature verification failed");
		}

		boolean characterExists = registryClient.characterExists(characterId, playerPubKey);

		if (!characterExists) {
			log.info("No registry record found for characterId=" + characterId
					+ " - will create new character on entry");
		}

		String sessionToken = UUID.randomUUID().toString();

		LoginSession session = new LoginSession(sessionToken, playerPubKey, characterId, signatureHex,
				System.currentTimeMillis());

		activeSessions.put(sessionToken, session);

		log.info("Login successful - characterId=" + characterId + " sessionToken=" + sessionToken.substring(0, 8)
				+ "...");

		return LoginResult.success(sessionToken, characterExists);
	}

	public void injectSession(LoginSession session) {
		activeSessions.put(session.getSessionToken(), session);
	}

	public LoginSession getSession(String sessionToken) {
		return activeSessions.get(sessionToken);
	}

	public void invalidateSession(String sessionToken) {
		LoginSession session = activeSessions.remove(sessionToken);
		if (session != null) {
			log.info("Session invalidated - characterId=" + session.getCharacterId());
		}
	}

	public boolean isSessionActive(String sessionToken) {
		return activeSessions.containsKey(sessionToken);
	}

	public Collection<LoginSession> getActiveSessions() {
		return Collections.unmodifiableCollection(activeSessions.values());
	}

	// Banned keys take effect immediately, but sessions are not automatically
	// terminated.
	// Call invalidateSession() separately
	public void ban(String playerPubKey) {
		bannedKeys.add(playerPubKey);
		log.info("Banned key: " + playerPubKey.substring(0, 8) + "...");
	}

	public void unban(String playerPubKey) {
		bannedKeys.remove(playerPubKey);
		log.info("Unbanned key: " + playerPubKey.substring(0, 8) + "...");
	}

	public boolean isBanned(String playerPubKey) {
		return bannedKeys.contains(playerPubKey);
	}

	public static class ChallengeResult {
		private final boolean issued;
		private final String nonce;
		private final String reason;

		private ChallengeResult(boolean issued, String nonce, String reason) {
			this.issued = issued;
			this.nonce = nonce;
			this.reason = reason;
		}

		public static ChallengeResult issued(String nonce) {
			return new ChallengeResult(true, nonce, null);
		}

		public static ChallengeResult rejected(String reason) {
			return new ChallengeResult(false, null, reason);
		}

		public boolean isIssued() {
			return issued;
		}

		public String getNonce() {
			return nonce;
		}

		public String getReason() {
			return reason;
		}
	}

	public static class LoginResult {
		private final boolean success;
		private final String sessionToken;
		private final boolean characterExists;
		private final String reason;

		private LoginResult(boolean success, String sessionToken, boolean characterExists, String reason) {
			this.success = success;
			this.sessionToken = sessionToken;
			this.characterExists = characterExists;
			this.reason = reason;
		}

		public static LoginResult success(String sessionToken, boolean characterExists) {
			return new LoginResult(true, sessionToken, characterExists, null);
		}

		public static LoginResult rejected(String reason) {
			return new LoginResult(false, null, false, reason);
		}

		public boolean isSuccess() {
			return success;
		}

		public String getSessionToken() {
			return sessionToken;
		}

		public boolean isCharacterExists() {
			return characterExists;
		}

		public String getReason() {
			return reason;
		}
	}
}
