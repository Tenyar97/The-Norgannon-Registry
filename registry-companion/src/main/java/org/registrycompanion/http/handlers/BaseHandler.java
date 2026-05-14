package org.registrycompanion.http.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public abstract class BaseHandler {

	protected static final ObjectMapper MAPPER = new ObjectMapper();

	protected void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
		byte[] bytes = MAPPER.writeValueAsBytes(body);
		sendRaw(exchange, status, "application/json", bytes);
	}

	protected void sendOk(HttpExchange exchange, Object body) throws IOException {
		sendJson(exchange, 200, body);
	}

	protected void sendError(HttpExchange exchange, int status, String message) throws IOException {
		sendJson(exchange, status, Map.of("error", message));
	}

	protected void sendBadRequest(HttpExchange exchange, String message) throws IOException {
		sendError(exchange, 400, message);
	}

	protected void sendServerError(HttpExchange exchange, String message) throws IOException {
		sendError(exchange, 500, message);
	}

	protected void sendMethodNotAllowed(HttpExchange exchange) throws IOException {
		sendError(exchange, 405, "Method not allowed");
	}

	private void sendRaw(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
		exchange.getResponseHeaders().set("Content-Type", contentType);
		exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
		exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
		exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
		exchange.sendResponseHeaders(status, body.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(body);
		}
	}

	protected String readBody(HttpExchange exchange) throws IOException {
		try (InputStream is = exchange.getRequestBody()) {
			return new String(is.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	protected Map<?, ?> readJsonBody(HttpExchange exchange) throws IOException {
		String body = readBody(exchange);
		if (body == null || body.isBlank())
			return Map.of();
		return MAPPER.readValue(body, Map.class);
	}

	protected boolean isOptions(HttpExchange exchange) {
		return "OPTIONS".equalsIgnoreCase(exchange.getRequestMethod());
	}

	protected boolean isGet(HttpExchange exchange) {
		return "GET".equalsIgnoreCase(exchange.getRequestMethod());
	}

	protected boolean isPost(HttpExchange exchange) {
		return "POST".equalsIgnoreCase(exchange.getRequestMethod());
	}

	protected boolean handlePreflight(HttpExchange exchange) throws IOException {
		if (isOptions(exchange)) {
			exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
			exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
			exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
			exchange.sendResponseHeaders(204, -1);
			return true;
		}
		return false;
	}
}
