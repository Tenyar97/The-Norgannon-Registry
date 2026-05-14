package org.registrynode.http.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.registrynode.gossip.GossipAgent;
import org.registrynode.model.SnapshotRecord;
import org.registrynode.storage.SnapshotStore;
import org.registrynode.storage.SnapshotStore.StoreResult;
import org.registrynode.verify.RecordVerifier;

import java.io.IOException;
import java.util.Map;
import java.util.logging.Logger;

public class SnapshotHandler extends BaseHandler implements HttpHandler {

	private static final Logger log = Logger.getLogger(SnapshotHandler.class.getName());

	private final SnapshotStore store;
	private final RecordVerifier verifier;
	private final GossipAgent gossip;

	public SnapshotHandler(SnapshotStore store, RecordVerifier verifier, GossipAgent gossip) {
		this.store = store;
		this.verifier = verifier;
		this.gossip = gossip;
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!isPost(exchange)) {
			sendMethodNotAllowed(exchange);
			return;
		}

		String senderUrl = senderUrl(exchange);
		if (senderUrl != null && !senderUrl.isBlank()) {
			gossip.addPeer(senderUrl);
		}

		String body = readBody(exchange);
		if (body == null || body.isBlank()) {
			sendBadRequest(exchange, "Empty request body");
			return;
		}

		SnapshotRecord record;
		try {
			record = MAPPER.readValue(body, SnapshotRecord.class);
		} catch (Exception e) {
			sendBadRequest(exchange, "Invalid JSON: " + e.getMessage());
			return;
		}

		RecordVerifier.VerificationResult verification = verifier.verify(record);
		if (!verification.isValid()) {
			log.warning("Rejected record — " + verification.getReason() + " characterId="
					+ (record.getCharacterId() != null ? record.getCharacterId() : "unknown"));
			sendBadRequest(exchange, "Verification failed: " + verification.getReason());
			return;
		}

		StoreResult result = store.put(record);

		switch (result) {
		case STORED -> {
			log.info("Stored record — characterId=" + record.getCharacterId() + " sequence=" + record.getSequence()
					+ (isGossip(exchange) ? " [gossip]" : " [direct]"));

			if (!isGossip(exchange)) {
				gossip.gossip(record);
			}

			sendOk(exchange, Map.of("result", "stored", "sequence", record.getSequence()));
		}

		case STALE -> {
			log.fine("Stale record ignored — characterId=" + record.getCharacterId() + " incoming="
					+ record.getSequence() + " current=" + store.currentSequence(record.getCharacterId()));

			sendOk(exchange, Map.of("result", "stale", "sequence", store.currentSequence(record.getCharacterId())));
		}

		case FAILED -> {
			log.severe("Disk write failed — characterId=" + record.getCharacterId());
			sendError(exchange, 500, "Storage error — disk write failed");
		}
		}
	}
}
