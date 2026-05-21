package org.registryagent.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.registryagent.auth.KeyVerifier;
import org.registryagent.model.SnapshotRecord;

import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

public class SnapshotVerifier {

	private static final Logger log = Logger.getLogger(SnapshotVerifier.class.getName());

	private final KeyVerifier keyVerifier;

	private final ObjectMapper mapper = new ObjectMapper()
			.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
			.configure(SerializationFeature.INDENT_OUTPUT, false);

	public SnapshotVerifier(KeyVerifier keyVerifier) {
		this.keyVerifier = keyVerifier;
	}

	public VerificationResult verify(SnapshotRecord record) {

		if (record == null)
			return VerificationResult.fail("Record is null");
		if (record.getCharacterId() == null)
			return VerificationResult.fail("Missing characterId");
		if (record.getPlayerPubKey() == null)
			return VerificationResult.fail("Missing playerPubKey");
		if (record.getServerPubKey() == null)
			return VerificationResult.fail("Missing serverPubKey");
		if (record.getServerSignature() == null)
			return VerificationResult.fail("Missing serverSignature");
		if (record.getPlayerSignature() == null)
			return VerificationResult.fail("Missing playerSignature");
		if (record.getPayload() == null)
			return VerificationResult.fail("Missing payload");
		if (record.getSequence() <= 0)
			return VerificationResult.fail("Invalid sequence");

		String canonicalJson = toCanonicalJson(record);
		if (canonicalJson == null) {
			return VerificationResult.fail("Failed to serialize payload for verification");
		}

		boolean serverSigValid = keyVerifier.verify(record.getServerPubKey(), canonicalJson,
				record.getServerSignature());

		if (!serverSigValid) {
			log.warning("Server signature invalid for characterId=" + record.getCharacterId() + " serverPubKey="
					+ record.getServerPubKey().substring(0, 8) + "...");
			return VerificationResult.fail("Server signature verification failed");
		}

		String authToken = record.getServerId() + ":" + record.getPlayerPubKey();

		boolean playerSigValid = keyVerifier.verify(record.getPlayerPubKey(), authToken, record.getPlayerSignature());

		if (!playerSigValid) {
			log.warning("Player signature invalid for characterId=" + record.getCharacterId() + " playerPubKey="
					+ record.getPlayerPubKey().substring(0, 8) + "...");
			return VerificationResult.fail("Player signature verification failed");
		}

		log.fine("Snapshot verified - characterId=" + record.getCharacterId() + " sequence=" + record.getSequence());

		return VerificationResult.ok();
	}

	public boolean isNewer(SnapshotRecord incoming, SnapshotRecord existing) {
		if (existing == null)
			return true;
		return incoming.getSequence() > existing.getSequence();
	}

	private String toCanonicalJson(SnapshotRecord record) {
		try {
			return mapper.writeValueAsString(record.getPayload());
		} catch (Exception e) {
			log.severe("Failed to serialize payload: " + e.getMessage());
			return null;
		}
	}

	public static class VerificationResult {
		private final boolean valid;
		private final String reason;

		private VerificationResult(boolean valid, String reason) {
			this.valid = valid;
			this.reason = reason;
		}

		public static VerificationResult ok() {
			return new VerificationResult(true, null);
		}

		public static VerificationResult fail(String reason) {
			return new VerificationResult(false, reason);
		}

		public boolean isValid() {
			return valid;
		}

		public String getReason() {
			return reason;
		}

		@Override
		public String toString() {
			return valid ? "VALID" : "INVALID: " + reason;
		}
	}
}
