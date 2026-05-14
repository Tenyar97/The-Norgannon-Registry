package org.registrycompanion.http.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.registrycompanion.key.KeyManager;
import org.registrycompanion.ui.RecoveryDialog;

import java.io.IOException;
import java.util.Map;
import java.util.logging.Logger;

import javax.swing.SwingUtilities;

public class RecoveryHandler extends BaseHandler implements HttpHandler {

	private static final Logger log = Logger.getLogger(RecoveryHandler.class.getName());

	private final KeyManager keyManager;
	private final RecoveryDialog recoveryDialog;

	public RecoveryHandler(KeyManager keyManager, RecoveryDialog recoveryDialog) {
		this.keyManager = keyManager;
		this.recoveryDialog = recoveryDialog;
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (handlePreflight(exchange))
			return;

		if (!isPost(exchange)) {
			sendMethodNotAllowed(exchange);
			return;
		}

		String path = exchange.getRequestURI().getPath();

		if (path.endsWith("/show")) {
			handleShow(exchange);
		} else if (path.endsWith("/restore")) {
			handleRestore(exchange);
		} else {
			sendError(exchange, 404, "Unknown recovery endpoint");
		}
	}

	private void handleShow(HttpExchange exchange) throws IOException {
		if (!keyManager.isReady()) {
			sendError(exchange, 503, "No key loaded");
			return;
		}

		SwingUtilities.invokeLater(() -> {
			String phrase = keyManager.getRecoveryPhrase();
			recoveryDialog.showPhrase(phrase);
		});

		log.info("Recovery phrase dialog opened");
		sendOk(exchange, Map.of("shown", true));
	}

	private void handleRestore(HttpExchange exchange) throws IOException {
		Map<?, ?> body;
		try {
			body = readJsonBody(exchange);
		} catch (Exception e) {
			sendBadRequest(exchange, "Invalid JSON body");
			return;
		}

		String phrase = (String) body.get("phrase");

		if (phrase == null || phrase.isBlank()) {
			sendBadRequest(exchange, "Missing required field: phrase");
			return;
		}

		try {
			keyManager.restoreFromPhrase(phrase.trim());
			log.info("Keypair restored from recovery phrase");

			sendOk(exchange, Map.of("success", true, "pubkey", keyManager.getPublicKeyHex()));

		} catch (IllegalArgumentException e) {
			sendBadRequest(exchange, "Invalid recovery phrase: " + e.getMessage());

		} catch (Exception e) {
			log.severe("Recovery restore failed: " + e.getMessage());
			sendServerError(exchange, "Restore failed");
		}
	}
}
