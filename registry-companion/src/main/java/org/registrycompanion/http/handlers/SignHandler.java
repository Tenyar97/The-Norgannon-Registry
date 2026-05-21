package org.registrycompanion.http.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.registrycompanion.key.KeyManager;

import java.io.IOException;
import java.util.Map;
import java.util.logging.Logger;

public class SignHandler extends BaseHandler implements HttpHandler {

	private static final Logger log = Logger.getLogger(SignHandler.class.getName());

	private static final String SERVER_AUTH_MAGIC = "SERVER_AUTH";
	private static final int MAX_MESSAGE_LENGTH = 512;

	private final KeyManager keyManager;

	public SignHandler(KeyManager keyManager) {
		this.keyManager = keyManager;
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (handlePreflight(exchange))
			return;

		if (!isPost(exchange)) {
			sendMethodNotAllowed(exchange);
			return;
		}

		if (!keyManager.isReady()) {
			sendError(exchange, 503, "No key loaded - setup not complete");
			return;
		}

		Map<?, ?> body;
		try {
			body = readJsonBody(exchange);
		} catch (Exception e) {
			sendBadRequest(exchange, "Invalid JSON body");
			return;
		}

		String message = (String) body.get("message");
		String serverId = (String) body.get("server_id");

		if (message == null || message.isBlank()) {
			sendBadRequest(exchange, "Missing required field: message");
			return;
		}

		if (message.length() > MAX_MESSAGE_LENGTH) {
			sendBadRequest(exchange, "Message too long (max " + MAX_MESSAGE_LENGTH + " chars)");
			return;
		}

		try {
			if (SERVER_AUTH_MAGIC.equals(message) && serverId != null && !serverId.isBlank()) {
				String signature = keyManager.signAuthToken(serverId);
				String authToken = serverId + ":" + keyManager.getPublicKeyHex();

				log.fine("Auth token signed for serverId=" + serverId.substring(0, 8) + "...");

				sendOk(exchange, Map.of("signature", signature, "auth_token", authToken, "pubkey",
						keyManager.getPublicKeyHex()));

			} else {
				String signature = keyManager.sign(message);

				log.fine("Challenge signed - message length=" + message.length());

				sendOk(exchange, Map.of("signature", signature, "pubkey", keyManager.getPublicKeyHex()));
			}

		} catch (Exception e) {
			log.severe("Signing failed: " + e.getMessage());
			sendServerError(exchange, "Signing failed");
		}
	}
}
