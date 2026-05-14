package org.registrynode.http.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.registrynode.model.SnapshotRecord;
import org.registrynode.storage.SnapshotStore;

import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

public class CharacterHandler extends BaseHandler implements HttpHandler {

	private static final Logger log = Logger.getLogger(CharacterHandler.class.getName());

	private final SnapshotStore store;

	public CharacterHandler(SnapshotStore store) {
		this.store = store;
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!isGet(exchange)) {
			sendMethodNotAllowed(exchange);
			return;
		}

		String path = exchange.getRequestURI().getPath();

		if (path.equals("/characters") || path.equals("/characters/")) {
			handleList(exchange);
			return;
		}

		handleGet(exchange);
	}

	private void handleGet(HttpExchange exchange) throws IOException {
		String characterId = pathSegment(exchange, 1);

		if (characterId == null || characterId.isBlank()) {
			sendBadRequest(exchange, "Missing character ID in path");
			return;
		}

		SnapshotRecord record = store.get(characterId);

		if (record == null) {
			log.fine("Record not found — characterId=" + characterId);
			sendNotFound(exchange);
			return;
		}

		log.fine("Serving record — characterId=" + characterId + " sequence=" + record.getSequence());

		sendOk(exchange, record);
	}

	private void handleList(HttpExchange exchange) throws IOException {
		List<String> ids = store.listCharacterIds();
		log.fine("Serving character list — " + ids.size() + " entries");
		sendOk(exchange, ids);
	}
}
