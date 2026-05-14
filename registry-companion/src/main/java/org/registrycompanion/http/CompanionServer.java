package org.registrycompanion.http;

import com.sun.net.httpserver.HttpServer;
import org.registrycompanion.http.handlers.*;
import org.registrycompanion.key.KeyManager;
import org.registrycompanion.ui.RecoveryDialog;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

public class CompanionServer {

	private static final Logger log = Logger.getLogger(CompanionServer.class.getName());
	public static final int PORT = 7742;
	public static final String HOST = "127.0.0.1";

	private final HttpServer server;

	public CompanionServer(KeyManager keyManager, RecoveryDialog recoveryDialog) throws Exception {

		InetSocketAddress address = new InetSocketAddress(HOST, PORT);
		server = HttpServer.create(address, 0);

		server.createContext("/status", new StatusHandler(keyManager));
		server.createContext("/pubkey", new PubkeyHandler(keyManager));
		server.createContext("/sign", new SignHandler(keyManager));
		server.createContext("/recovery", new RecoveryHandler(keyManager, recoveryDialog));

		server.createContext("/ping", exchange -> {
			exchange.sendResponseHeaders(200, 0);
			exchange.getResponseBody().close();
		});

		server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
	}

	public void start() {
		server.start();
		log.info("Companion HTTP server listening on " + HOST + ":" + PORT);
		log.info("Addon endpoint: http://" + HOST + ":" + PORT + "/");
	}

	public void stop() {
		server.stop(2);
		log.info("Companion HTTP server stopped");
	}

	public int getPort() {
		return PORT;
	}
}
