package org.registryagent.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.registryagent.model.CharacterPayload;
import org.registryagent.model.LoginSession;
import org.registryagent.model.SnapshotRecord;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class SnapshotBuilder {

	private static final Logger log = Logger.getLogger(SnapshotBuilder.class.getName());
	private static final String SCHEMA_VERSION = "1.0";

	private final String serverId;
	private final ServerSigner signer;

	private final Map<String, Long> sequences = new ConcurrentHashMap<>();

	private final ObjectMapper mapper = new ObjectMapper()
			.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
			.configure(SerializationFeature.INDENT_OUTPUT, false);

	public SnapshotBuilder(String serverId, ServerSigner signer) {
		this.serverId = serverId;
		this.signer = signer;
	}

	public SnapshotRecord build(LoginSession session, CharacterPayload payload, String existingCreatedAt) {
		String now = Instant.now().toString();

		SnapshotRecord record = new SnapshotRecord();
		record.setVersion(SCHEMA_VERSION);
		record.setCharacterId(session.getCharacterId());
		record.setPlayerPubKey(session.getPlayerPubKey());
		record.setServerId(serverId);
		record.setServerPubKey(signer.getPublicKeyHex());
		record.setSequence(nextSequence(session.getCharacterId()));
		record.setCreatedAt(existingCreatedAt != null ? existingCreatedAt : now);
		record.setUpdatedAt(now);
		record.setPayload(payload);

		record.setPlayerSignature(session.getPlayerSignature());

		String canonicalJson = toCanonicalJson(payload);
		if (canonicalJson == null) {
			throw new IllegalStateException(
					"Failed to serialize payload for signing - characterId=" + session.getCharacterId());
		}

		record.setServerSignature(signer.sign(canonicalJson));

		log.fine("Built snapshot - characterId=" + session.getCharacterId() + " sequence=" + record.getSequence());

		return record;
	}

	public void seedSequence(String characterId, long lastKnownSequence) {
		sequences.put(characterId, lastKnownSequence);
		log.fine("Seeded sequence for characterId=" + characterId + " at " + lastKnownSequence);
	}

	public String toCanonicalJson(CharacterPayload payload) {
		try {
			return mapper.writeValueAsString(payload);
		} catch (Exception e) {
			log.severe("Failed to serialize CharacterPayload: " + e.getMessage());
			return null;
		}
	}

	private long nextSequence(String characterId) {
		return sequences.merge(characterId, 1L, Long::sum);
	}

	public long currentSequence(String characterId) {
		return sequences.getOrDefault(characterId, 0L);
	}
}
