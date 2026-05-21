package org.registrycompanion.http.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.registrycompanion.key.KeyManager;

import java.io.IOException;
import java.util.Map;

public class PubkeyHandler extends BaseHandler implements HttpHandler {

	private final KeyManager keyManager;

	public PubkeyHandler(KeyManager keyManager) {
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

		if (!keyManager.isReady()) {
			sendError(exchange, 503, "No key loaded - setup not complete");
			return;
		}

		sendOk(exchange, Map.of("pubkey", keyManager.getPublicKeyHex()));
	}
}
