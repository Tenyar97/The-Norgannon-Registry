package org.registrynode.model;

public class SnapshotRecord {

	private String version;
	private String characterId;
	private String playerPubKey;
	private String serverId;
	private String serverPubKey;
	private long sequence;
	private String createdAt;
	private String updatedAt;
	private CharacterPayload payload;
	private String serverSignature;
	private String playerSignature;

	public SnapshotRecord() {
	}

	public String getVersion() {
		return version;
	}

	public void setVersion(String version) {
		this.version = version;
	}

	public String getCharacterId() {
		return characterId;
	}

	public void setCharacterId(String characterId) {
		this.characterId = characterId;
	}

	public String getPlayerPubKey() {
		return playerPubKey;
	}

	public void setPlayerPubKey(String playerPubKey) {
		this.playerPubKey = playerPubKey;
	}

	public String getServerId() {
		return serverId;
	}

	public void setServerId(String serverId) {
		this.serverId = serverId;
	}

	public String getServerPubKey() {
		return serverPubKey;
	}

	public void setServerPubKey(String serverPubKey) {
		this.serverPubKey = serverPubKey;
	}

	public long getSequence() {
		return sequence;
	}

	public void setSequence(long sequence) {
		this.sequence = sequence;
	}

	public String getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(String createdAt) {
		this.createdAt = createdAt;
	}

	public String getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(String updatedAt) {
		this.updatedAt = updatedAt;
	}

	public CharacterPayload getPayload() {
		return payload;
	}

	public void setPayload(CharacterPayload payload) {
		this.payload = payload;
	}

	public String getServerSignature() {
		return serverSignature;
	}

	public void setServerSignature(String serverSignature) {
		this.serverSignature = serverSignature;
	}

	public String getPlayerSignature() {
		return playerSignature;
	}

	public void setPlayerSignature(String playerSignature) {
		this.playerSignature = playerSignature;
	}

	@Override
	public String toString() {
		return String.format("SnapshotRecord{characterId='%s', sequence=%d, updatedAt='%s'}", characterId, sequence,
				updatedAt);
	}
}
