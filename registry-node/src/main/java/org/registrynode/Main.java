package org.registrynode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.registrynode.gossip.GossipAgent;
import org.registrynode.http.NodeServer;
import org.registrynode.storage.SnapshotStore;
import org.registrynode.verify.RecordVerifier;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;


public class Main {

    private static final Logger log = Logger.getLogger(Main.class.getName());

    private static NodeServer   server;
    private static GossipAgent  gossip;

    public static void main(String[] args) throws Exception {
        String configPath = args.length > 0 ? args[0] : "./config.yaml";

        log.info("Starting registry-node — config=" + configPath);


        NodeConfig config = loadConfig(configPath);
        config.validate();

        log.info("Port:    " + config.getPort());
        log.info("DataDir: " + config.getDataDir());
        log.info("Peers:   " + peerCount(config));


        Path dataDir = Path.of(config.getDataDir());
        SnapshotStore store = new SnapshotStore(dataDir);
        store.warmup();

        log.info("SnapshotStore ready — " + store.size() + " records on disk");


        RecordVerifier verifier = new RecordVerifier();


        List<String> peers = config.getPeers() != null
                ? config.getPeers()
                : List.of();

        gossip = new GossipAgent(store, verifier, peers);

        server = new NodeServer(
                config.getPort(),
                store,
                verifier,
                gossip
        );


        server.start();


        gossip.start();


        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received...");
            server.stop();
            gossip.shutdown();
            log.info("Node shutdown complete");
        }, "shutdown-hook"));


        log.info("");
        log.info("================================================");
        log.info("  registry-node is running");
        log.info("  Port    : " + config.getPort());
        log.info("  Records : " + store.size());
        log.info("  Peers   : " + peers.size());
        if (config.getPublicUrl() != null) {
            log.info("  URL     : " + config.getPublicUrl());
        }
        log.info("================================================");
        log.info("");
        log.info("Endpoints:");
        log.info("  POST http://localhost:" + config.getPort() + "/snapshot");
        log.info("  GET  http://localhost:" + config.getPort() + "/character/{id}");
        log.info("  GET  http://localhost:" + config.getPort() + "/characters");
        log.info("  GET  http://localhost:" + config.getPort() + "/ping");
        log.info("  GET  http://localhost:" + config.getPort() + "/status");


        Thread.currentThread().join();
    }


    private static NodeConfig loadConfig(String path) throws Exception {
        File configFile = new File(path);

        if (!configFile.exists()) {
            log.warning("Config file not found at " + path +
                        " — using defaults (port=8080, data=./data, no peers)");
            return new NodeConfig();
        }

        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        yaml.findAndRegisterModules();
        return yaml.readValue(configFile, NodeConfig.class);
    }

    private static String peerCount(NodeConfig config) {
        if (config.getPeers() == null || config.getPeers().isEmpty()) {
            return "0 (single-node mode)";
        }
        return String.valueOf(config.getPeers().size());
    }
}
