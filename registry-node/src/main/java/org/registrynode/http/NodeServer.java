package org.registrynode.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpServer;
import org.registrynode.gossip.GossipAgent;
import org.registrynode.http.handlers.CharacterHandler;
import org.registrynode.http.handlers.HealthHandler;
import org.registrynode.http.handlers.PingHandler;
import org.registrynode.http.handlers.SnapshotHandler;
import org.registrynode.storage.SnapshotStore;
import org.registrynode.verify.RecordVerifier;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

public class NodeServer {

	private static final Logger log = Logger.getLogger(NodeServer.class.getName());

	private final HttpServer server;
	private final int port;
	private final SnapshotStore store;
	private final GossipAgent gossip;

	public NodeServer(int port, SnapshotStore store, RecordVerifier verifier, GossipAgent gossip) throws Exception {

		this.port = port;
		this.store = store;
		this.gossip = gossip;

		InetSocketAddress address = new InetSocketAddress(port);
		server = HttpServer.create(address, 0);

		server.createContext("/snapshot", new SnapshotHandler(store, verifier, gossip));

		server.createContext("/character", new CharacterHandler(store));
		server.createContext("/characters", new CharacterHandler(store));

		server.createContext("/ping", new PingHandler(store));
		server.createContext("/health", new HealthHandler(store, gossip));

		server.createContext("/status", exchange -> {
			Map<String, Object> status = new LinkedHashMap<>();
			status.put("status", "ok");
			status.put("records", store.size());
			status.put("peers", gossip.peerCount());
			status.put("peerStatus", gossip.peerStatus());
			status.put("version", "1.0.0");

			byte[] body = new ObjectMapper().configure(SerializationFeature.INDENT_OUTPUT, true)
					.writeValueAsBytes(status);

			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (var os = exchange.getResponseBody()) {
				os.write(body);
			}
		});

		server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
	}

	public void start() {
		server.start();
		log.info("Node HTTP server listening on port " + port);
	}

	public void stop() {
		server.stop(3);
		log.info("Node HTTP server stopped");
	}

	public int getPort() {
		return port;
	}
}
