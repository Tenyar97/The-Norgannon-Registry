package org.registrycompanion.http.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.registrycompanion.key.KeyManager;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class StatusHandler extends BaseHandler implements HttpHandler {

	private final KeyManager keyManager;

	public StatusHandler(KeyManager keyManager) {
		this.keyManager = keyManager;
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (handlePreflight(exchange))
			return;

		if (!isGet(exchange)) {
			sendMethodNotAllowed(exchange);
			return;
		}

		Map<String, Object> response = new LinkedHashMap<>();
		response.put("ready", keyManager.isReady());
		response.put("pubkey", keyManager.isReady() ? keyManager.getPublicKeyHex() : null);
		response.put("version", "1.0.0");

		sendOk(exchange, response);
	}
}
