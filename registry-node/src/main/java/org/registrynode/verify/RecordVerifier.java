package org.registrynode.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.registrynode.model.SnapshotRecord;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.logging.Logger;

public class RecordVerifier {

	private static final Logger log = Logger.getLogger(RecordVerifier.class.getName());

	private final KeyVerifier keyVerifier;
	private final ObjectMapper mapper;

	public RecordVerifier() {
		this.keyVerifier = new KeyVerifier();
		this.mapper = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
				.configure(SerializationFeature.INDENT_OUTPUT, false);
	}

	public VerificationResult verify(SnapshotRecord record) {

		if (record == null)
			return fail("Record is null");
		if (blank(record.getCharacterId()))
			return fail("Missing characterId");
		if (blank(record.getPlayerPubKey()))
			return fail("Missing playerPubKey");
		if (blank(record.getServerPubKey()))
			return fail("Missing serverPubKey");
		if (blank(record.getServerId()))
			return fail("Missing serverId");
		if (blank(record.getServerSignature()))
			return fail("Missing serverSignature");
		if (blank(record.getPlayerSignature()))
			return fail("Missing playerSignature");
		if (record.getPayload() == null)
			return fail("Missing payload");
		if (record.getSequence() <= 0)
			return fail("Invalid sequence: " + record.getSequence());

		if (record.getUpdatedAt() != null) {
			try {
				Instant updated = Instant.parse(record.getUpdatedAt());
				Instant now = Instant.now();
				if (updated.isAfter(now.plusSeconds(300))) {
					return fail("Record timestamp is too far in the future");
				}
			} catch (Exception e) {
				return fail("Invalid updatedAt timestamp: " + record.getUpdatedAt());
			}
		}

		String canonicalJson = toCanonicalJson(record);
		if (canonicalJson == null)
			return fail("Failed to serialize payload for verification");

		boolean serverSigValid = keyVerifier.verify(record.getServerPubKey(), canonicalJson,
				record.getServerSignature());

		if (!serverSigValid) {
			log.warning("Server signature invalid — characterId=" + record.getCharacterId() + " serverPubKey="
					+ abbrev(record.getServerPubKey()));
			return fail("Server signature verification failed");
		}

		String authToken = record.getServerId() + ":" + record.getPlayerPubKey();

		boolean playerSigValid = keyVerifier.verify(record.getPlayerPubKey(), authToken, record.getPlayerSignature());

		if (!playerSigValid) {
			log.warning("Player signature invalid — characterId=" + record.getCharacterId() + " playerPubKey="
					+ abbrev(record.getPlayerPubKey()));
			return fail("Player signature verification failed");
		}

		log.fine("Record verified — characterId=" + record.getCharacterId() + " sequence=" + record.getSequence());

		return ok();
	}

	private String toCanonicalJson(SnapshotRecord record) {
		try {
			return mapper.writeValueAsString(record.getPayload());
		} catch (Exception e) {
			log.severe("Payload serialization failed: " + e.getMessage());
			return null;
		}
	}

	private boolean blank(String s) {
		return s == null || s.isBlank();
	}

	private String abbrev(String s) {
		if (s == null || s.length() < 8)
			return s;
		return s.substring(0, 8) + "...";
	}

	private VerificationResult ok() {
		return new VerificationResult(true, null);
	}

	private VerificationResult fail(String reason) {
		return new VerificationResult(false, reason);
	}

	public static class VerificationResult {
		private final boolean valid;
		private final String reason;

		VerificationResult(boolean valid, String reason) {
			this.valid = valid;
			this.reason = reason;
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
