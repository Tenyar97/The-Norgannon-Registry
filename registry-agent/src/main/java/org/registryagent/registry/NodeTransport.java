package org.registryagent.registry;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.registryagent.model.SnapshotRecord;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Logger;

public class NodeTransport {

	private static final Logger log = Logger.getLogger(NodeTransport.class.getName());

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

	private final HttpClient httpClient;
	private final ObjectMapper mapper;
	private final SnapshotVerifier verifier;

	public NodeTransport(SnapshotVerifier verifier) {
		this.verifier = verifier;
		this.mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
	}

	public SnapshotRecord fetch(RegistryNode node, String characterId) {
		HttpRequest request = HttpRequest.newBuilder().uri(URI.create(node.fetchUrl(characterId)))
				.timeout(REQUEST_TIMEOUT).GET().build();

		try {
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() == 404) {
				node.recordSuccess();
				return null;
			}

			if (response.statusCode() != 200) {
				log.warning("Node returned " + response.statusCode() + " for character " + characterId + " at "
						+ node.getBaseUrl());
				node.recordFailure();
				return null;
			}

			SnapshotRecord record = mapper.readValue(response.body(), SnapshotRecord.class);

			SnapshotVerifier.VerificationResult result = verifier.verify(record);
			if (!result.isValid()) {
				log.warning("Verification failed for record from " + node.getBaseUrl() + " - " + result.getReason());
				node.recordFailure();
				return null;
			}

			node.recordSuccess();
			return record;

		} catch (Exception e) {
			log.warning("Failed to fetch from node " + node.getBaseUrl() + ": " + e.getMessage());
			node.recordFailure();
			return null;
		}
	}

	public boolean push(RegistryNode node, SnapshotRecord record) {
		try {
			String body = mapper.writeValueAsString(record);

			HttpRequest request = HttpRequest.newBuilder().uri(URI.create(node.pushUrl())).timeout(REQUEST_TIMEOUT)
					.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();

			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() == 200) {
				node.recordSuccess();
				return true;
			}

			String rejection = response.body();
			log.warning("Node " + node.getBaseUrl() + " rejected snapshot: " + response.statusCode() + " " + rejection);
			if (rejection != null && rejection.contains("signature")) {
				log.warning("Signature rejection - ensure this server's public key is registered with the node."
						+ " serverId=" + record.getServerId()
						+ " serverPubKey=" + (record.getServerPubKey() != null
								? record.getServerPubKey().substring(0, Math.min(16, record.getServerPubKey().length())) + "..."
								: "null"));
			}
			node.recordFailure();
			return false;

		} catch (Exception e) {
			log.warning("Failed to push to node " + node.getBaseUrl() + ": " + e.getMessage());
			node.recordFailure();
			return false;
		}
	}

	public boolean ping(RegistryNode node) {
		HttpRequest request = HttpRequest.newBuilder().uri(URI.create(node.pingUrl())).timeout(Duration.ofSeconds(3))
				.GET().build();

		try {
			HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());

			boolean alive = response.statusCode() == 200;
			if (alive) {
				node.recordSuccess();
			} else {
				node.recordFailure();
			}
			return alive;

		} catch (Exception e) {
			node.recordFailure();
			return false;
		}
	}
}
