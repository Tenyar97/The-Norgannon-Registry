package org.registrynode.gossip;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Logger;

public class PeerNode {

	private static final Logger log = Logger.getLogger(PeerNode.class.getName());
	private static final int MAX_FAILURES = 3;
	private static final long COOLDOWN_MS = 60_000L;
	private static final Duration TIMEOUT = Duration.ofSeconds(8);

	private final String baseUrl;
	private final HttpClient http;

	private int failureCount = 0;
	private long lastFailureTime = 0;
	private boolean healthy = true;

	public PeerNode(String baseUrl) {
		this.baseUrl = baseUrl.stripTrailing().replaceAll("/$", "");
		this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
	}

	public boolean push(String json) {
		HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/snapshot")).timeout(TIMEOUT)
				.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build();

		try {
			HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());

			if (response.statusCode() == 200) {
				recordSuccess();
				return true;
			}

			log.fine("Peer " + baseUrl + " returned " + response.statusCode());
			recordFailure();
			return false;

		} catch (Exception e) {
			log.fine("Push to " + baseUrl + " failed: " + e.getMessage());
			recordFailure();
			return false;
		}
	}

	public boolean ping() {
		HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/ping")).timeout(Duration.ofSeconds(3))
				.GET().build();

		try {
			HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());

			boolean alive = response.statusCode() == 200;
			if (alive)
				recordSuccess();
			else
				recordFailure();
			return alive;

		} catch (Exception e) {
			recordFailure();
			return false;
		}
	}

	public String fetchCharacterList() {
		HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/characters")).timeout(TIMEOUT).GET()
				.build();

		try {
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() == 200) {
				recordSuccess();
				return response.body();
			}

			recordFailure();
			return null;

		} catch (Exception e) {
			log.fine("fetchCharacterList from " + baseUrl + " failed: " + e.getMessage());
			recordFailure();
			return null;
		}
	}

	public String fetchRecord(String characterId) {
		HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/character/" + characterId))
				.timeout(TIMEOUT).GET().build();

		try {
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() == 200) {
				recordSuccess();
				return response.body();
			}

			recordFailure();
			return null;

		} catch (Exception e) {
			recordFailure();
			return null;
		}
	}

	public synchronized boolean isAvailable() {
		if (healthy)
			return true;
		return System.currentTimeMillis() - lastFailureTime >= COOLDOWN_MS;
	}

	public synchronized void recordSuccess() {
		failureCount = 0;
		healthy = true;
	}

	public synchronized void recordFailure() {
		failureCount++;
		lastFailureTime = System.currentTimeMillis();
		if (failureCount >= MAX_FAILURES)
			healthy = false;
	}

	public String getBaseUrl() {
		return baseUrl;
	}

	public boolean isHealthy() {
		return healthy;
	}

	public int getFailures() {
		return failureCount;
	}

	@Override
	public String toString() {
		return "PeerNode{url='" + baseUrl + "', healthy=" + healthy + "}";
	}
}
