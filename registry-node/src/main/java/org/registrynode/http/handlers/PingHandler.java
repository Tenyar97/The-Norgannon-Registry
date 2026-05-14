package org.registrynode.http.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.registrynode.storage.SnapshotStore;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class PingHandler extends BaseHandler implements HttpHandler {

	private static final String VERSION = "1.0.0";

	private final SnapshotStore store;

	public PingHandler(SnapshotStore store) {
		this.store = store;
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!isGet(exchange)) {
			sendMethodNotAllowed(exchange);
			return;
		}

		Map<String, Object> response = new LinkedHashMap<>();
		response.put("status", "ok");
		response.put("records", store.size());
		response.put("version", VERSION);

		sendOk(exchange, response);
	}
}
