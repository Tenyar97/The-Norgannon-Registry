package org.registrynode.http.handlers;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public abstract class BaseHandler {

	protected static final ObjectMapper MAPPER = new ObjectMapper().configure(SerializationFeature.INDENT_OUTPUT, false)
			.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

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

	protected void sendNotFound(HttpExchange exchange) throws IOException {
		sendError(exchange, 404, "Not found");
	}

	protected void sendBadRequest(HttpExchange exchange, String message) throws IOException {
		sendError(exchange, 400, message);
	}

	protected void sendMethodNotAllowed(HttpExchange exchange) throws IOException {
		sendError(exchange, 405, "Method not allowed");
	}

	private void sendRaw(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
		exchange.getResponseHeaders().set("Content-Type", contentType);
		exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
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

	protected boolean isGet(HttpExchange exchange) {
		return "GET".equalsIgnoreCase(exchange.getRequestMethod());
	}

	protected boolean isPost(HttpExchange exchange) {
		return "POST".equalsIgnoreCase(exchange.getRequestMethod());
	}

	protected String pathSegment(HttpExchange exchange, int index) {
		String[] parts = exchange.getRequestURI().getPath().split("/");
		// parts[0] is always ""
		int actualIndex = index + 1;
		if (actualIndex >= parts.length)
			return null;
		return parts[actualIndex].isBlank() ? null : parts[actualIndex];
	}

	protected boolean isGossip(HttpExchange exchange) {
		String val = exchange.getRequestHeaders().getFirst("X-Gossip");
		return "true".equalsIgnoreCase(val);
	}

	protected String senderUrl(HttpExchange exchange) {
		return exchange.getRequestHeaders().getFirst("X-Node-Url");
	}
}
