package org.registryagent.model;

public class LoginSession {

	private String sessionToken;
	private String playerPubKey;
	private String characterId;
	private String playerSignature;
	private long loginTime;

	public LoginSession() {
	}

	public LoginSession(String sessionToken, String playerPubKey, String characterId, String playerSignature,
			long loginTime) {
		this.sessionToken = sessionToken;
		this.playerPubKey = playerPubKey;
		this.characterId = characterId;
		this.playerSignature = playerSignature;
		this.loginTime = loginTime;
	}

	public String getSessionToken() {
		return sessionToken;
	}

	public void setSessionToken(String sessionToken) {
		this.sessionToken = sessionToken;
	}

	public String getPlayerPubKey() {
		return playerPubKey;
	}

	public void setPlayerPubKey(String playerPubKey) {
		this.playerPubKey = playerPubKey;
	}

	public String getCharacterId() {
		return characterId;
	}

	public void setCharacterId(String characterId) {
		this.characterId = characterId;
	}

	public String getPlayerSignature() {
		return playerSignature;
	}

	public void setPlayerSignature(String playerSignature) {
		this.playerSignature = playerSignature;
	}

	public long getLoginTime() {
		return loginTime;
	}

	public void setLoginTime(long loginTime) {
		this.loginTime = loginTime;
	}
}
