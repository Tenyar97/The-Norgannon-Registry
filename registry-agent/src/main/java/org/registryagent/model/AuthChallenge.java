package org.registryagent.model;

public class AuthChallenge {

	private String nonce;
	private String playerPubKey;
	private long issuedAt;
	private long expiresAt;

	public static final long DEFAULT_TTL_MS = 10_000L;

	public AuthChallenge() {
	}

	public AuthChallenge(String nonce, String playerPubKey) {
		this.nonce = nonce;
		this.playerPubKey = playerPubKey;
		this.issuedAt = System.currentTimeMillis();
		this.expiresAt = this.issuedAt + DEFAULT_TTL_MS;
	}

	public boolean isExpired() {
		return System.currentTimeMillis() > expiresAt;
	}

	public String getNonce() {
		return nonce;
	}

	public void setNonce(String nonce) {
		this.nonce = nonce;
	}

	public String getPlayerPubKey() {
		return playerPubKey;
	}

	public void setPlayerPubKey(String playerPubKey) {
		this.playerPubKey = playerPubKey;
	}

	public long getIssuedAt() {
		return issuedAt;
	}

	public void setIssuedAt(long issuedAt) {
		this.issuedAt = issuedAt;
	}

	public long getExpiresAt() {
		return expiresAt;
	}

	public void setExpiresAt(long expiresAt) {
		this.expiresAt = expiresAt;
	}
}
