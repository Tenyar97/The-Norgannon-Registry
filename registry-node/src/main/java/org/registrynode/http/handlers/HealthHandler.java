package org.registrynode.http.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.registrynode.gossip.GossipAgent;
import org.registrynode.storage.SnapshotStore;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class HealthHandler extends BaseHandler implements HttpHandler {

	private final SnapshotStore store;
	private final GossipAgent gossip;
	private final long startedAt = System.currentTimeMillis();

	public HealthHandler(SnapshotStore store, GossipAgent gossip) {
		this.store = store;
		this.gossip = gossip;
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!isGet(exchange)) {
			sendMethodNotAllowed(exchange);
			return;
		}

		long uptimeSeconds = (System.currentTimeMillis() - startedAt) / 1000;

		Map<String, Object> response = new LinkedHashMap<>();
		response.put("status", "ok");
		response.put("uptime_seconds", uptimeSeconds);
		response.put("records", store.size());
		response.put("peers", gossip.peerCount());

		sendOk(exchange, response);
	}
}
