package org.registryagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.registryagent.adapter.ServerAdapter;
import org.registryagent.adapter.azerothcore.AzerothCoreAdapter;
import org.registryagent.adapter.trinitycore.TrinityCoreAdapter;
import org.registryagent.auth.AuthServer;
import org.registryagent.auth.ChallengeManager;
import org.registryagent.auth.KeyVerifier;
import org.registryagent.registry.NodeTransport;
import org.registryagent.registry.RegistryClient;
import org.registryagent.registry.SnapshotVerifier;
import org.registryagent.snapshot.ImportProfileLoader;
import org.registryagent.snapshot.ServerSigner;
import org.registryagent.snapshot.SnapshotAgent;
import org.registryagent.snapshot.SnapshotBuilder;
import org.registryagent.ui.AdminImportPanel;

import javax.swing.*;
import java.awt.*;

import java.io.File;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

public class Main 
{

	private static final Logger log = Logger.getLogger(Main.class.getName());

	public static AuthServer       authServer;
	public static AgentAuthServer  agentAuthServer;
	public static SnapshotAgent    snapshotAgent;
	public static DbQueueProcessor dbQueueProcessor;

	public static void main(String[] args) throws Exception {

		if (args.length == 0) {
			printUsage();
			System.exit(1);
		}

		switch (args[0]) {
		case "init"      -> runInit(args);
		case "start"     -> runStart(args);
		case "admin-gui" -> runAdminGui(args);
		default -> {
			printUsage();
			System.exit(1);
		}
		}
	}

	private static void runInit(String[] args) throws Exception {
		Path privateKeyPath = Path.of("./keys/server.pem");
		Path publicKeyPath = Path.of("./keys/server.pub");
		Path configPath = Path.of("./config.yaml");

		if (privateKeyPath.toFile().exists()) {
			log.severe("keys/server.pem already exists. " + "Delete it manually if you intend to regenerate keys.");
			System.exit(1);
		}

		privateKeyPath.getParent().toFile().mkdirs();

		ServerSigner.generateKeypair(privateKeyPath, publicKeyPath);
		String serverId = UUID.randomUUID().toString();

		log.info("Generated server keypair:");
		log.info("  Private key: " + privateKeyPath);
		log.info("  Public key:  " + publicKeyPath);
		log.info("  Server ID:   " + serverId);

		String skeleton = """
				serverId: "%s"

				privateKeyPath: "./keys/server.pem"
				publicKeyPath:  "./keys/server.pub"

				dbUrl:      "jdbc:mysql://localhost:3306/characters"
				dbUsername: "trinity"
				dbPassword: "trinity"
				dbAdapter:  "trinitycore_3.3.5a"

				registryNodes:
				  - "http://node1.example.com:8080"
				  - "http://node2.example.com:8080"
				""".formatted(serverId);

		java.nio.file.Files.writeString(configPath, skeleton);

		log.info("Skeleton config written to: " + configPath);
		log.info("");
		log.info("Next steps:");
		log.info("  1. Edit config.yaml — set your DB credentials and registry node URLs");
		log.info("  2. Register your public key with the registry network");
		log.info("  3. Run: registry-agent start");
	}

	private static void runStart(String[] args) throws Exception {
		String configPath = args.length > 1 ? args[1] : "./config.yaml";

		log.info("Loading config from: " + configPath);
		AgentConfig config = loadConfig(configPath);
		config.validate();

		log.info("Starting registry-agent — serverId=" + config.getServerId());

		KeyVerifier keyVerifier = new KeyVerifier();

		ServerSigner serverSigner = new ServerSigner(Path.of(config.getPrivateKeyPath()),
				Path.of(config.getPublicKeyPath()));

		SnapshotVerifier snapshotVerifier = new SnapshotVerifier(keyVerifier);

		log.info("Crypto layer ready — serverPubKey=" + serverSigner.getPublicKeyHex().substring(0, 8) + "...");

		NodeTransport nodeTransport = new NodeTransport(snapshotVerifier);

		RegistryClient registryClient = new RegistryClient(config.getRegistryNodes(), nodeTransport);

		log.info("Registry layer ready — " + config.getRegistryNodes().size() + " nodes configured");

		ChallengeManager challengeManager = new ChallengeManager();

		authServer = new AuthServer(challengeManager, keyVerifier, registryClient);

		log.info("Auth layer ready");

		ServerAdapter adapter = buildAdapter(config);

		log.info("Adapter ready — namespace=" + adapter.getNamespace());

		SnapshotBuilder snapshotBuilder = new SnapshotBuilder(config.getServerId(), serverSigner);

		snapshotAgent = new SnapshotAgent(authServer, adapter, snapshotBuilder, registryClient);

		log.info("Snapshot layer ready");

		agentAuthServer = new AgentAuthServer(authServer, snapshotAgent, config.getServerId());
		agentAuthServer.start();

		dbQueueProcessor = new DbQueueProcessor(agentAuthServer, snapshotAgent,
				config.getDbUrl(), config.getDbUsername(), config.getDbPassword());
		dbQueueProcessor.start();

		log.info("DB queue processor ready");

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			log.info("Shutdown signal received — flushing agent...");
			dbQueueProcessor.shutdown();
			snapshotAgent.shutdown();
			agentAuthServer.stop();
			challengeManager.shutdown();
			registryClient.shutdown();
			log.info("Shutdown complete.");
		}, "shutdown-hook"));

		log.info("");
		log.info("========================================");
		log.info("  registry-agent is running");
		log.info("  Server ID : " + config.getServerId());
		log.info("  Adapter   : " + adapter.getNamespace());
		log.info("  Nodes     : " + config.getRegistryNodes().size());
		log.info("========================================");
		log.info("");
		log.info("Waiting for game server events...");

		Thread.currentThread().join();
	}

	private static ServerAdapter buildAdapter(AgentConfig config) {
		TrinityCoreAdapter adapter = switch (config.getDbAdapter()) {
		case "trinitycore_3.3.5a" ->
			new TrinityCoreAdapter(config.getDbUrl(), config.getDbUsername(), config.getDbPassword());
		case "azerothcore_3.3.5a" ->
			new AzerothCoreAdapter(config.getDbUrl(), config.getDbUsername(), config.getDbPassword());
		default -> throw new IllegalStateException("Unknown adapter: '" + config.getDbAdapter() + "'. "
				+ "Supported: trinitycore_3.3.5a, azerothcore_3.3.5a");
		};
		adapter.setCustomItemThreshold(config.getCustomItemThreshold());
		return adapter;
	}

	private static AgentConfig loadConfig(String path) throws Exception {
		ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
		yaml.findAndRegisterModules();

		File configFile = new File(path);
		if (!configFile.exists()) {
			throw new IllegalStateException("Config file not found: " + path + "\n"
					+ "Run 'registry-agent init' to generate a skeleton config.");
		}

		return yaml.readValue(configFile, AgentConfig.class);
	}

	private static void runAdminGui(String[] args) throws Exception {
		if (GraphicsEnvironment.isHeadless()) {
			log.severe("admin-gui requires a display. "
					+ "Run 'registry-agent start' for headless operation.");
			System.exit(1);
		}

		try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
		catch (Exception ignored) {}

		String configPath = args.length > 1 ? args[1] : "./config.yaml";
		log.info("Loading config from: " + configPath + " (admin-gui mode)");

		AgentConfig config = loadConfig(configPath);
		config.validate();

		log.info("Starting registry-agent (admin-gui) — serverId=" + config.getServerId());

		KeyVerifier      keyVerifier      = new KeyVerifier();
		ServerSigner     serverSigner     = new ServerSigner(
				Path.of(config.getPrivateKeyPath()),
				Path.of(config.getPublicKeyPath()));
		SnapshotVerifier snapshotVerifier = new SnapshotVerifier(keyVerifier);
		NodeTransport    nodeTransport    = new NodeTransport(snapshotVerifier);
		RegistryClient   registryClient  = new RegistryClient(config.getRegistryNodes(), nodeTransport);
		ChallengeManager challengeManager = new ChallengeManager();
		authServer       = new AuthServer(challengeManager, keyVerifier, registryClient);

		ServerAdapter adapter = buildAdapter(config);
		SnapshotBuilder snapshotBuilder = new SnapshotBuilder(config.getServerId(), serverSigner);
		snapshotAgent   = new SnapshotAgent(authServer, adapter, snapshotBuilder, registryClient);

		ImportProfileLoader profileLoader = new ImportProfileLoader();
		snapshotAgent.setProfileLoader(profileLoader);

		agentAuthServer = new AgentAuthServer(authServer, snapshotAgent, config.getServerId());
		agentAuthServer.start();

		dbQueueProcessor = new DbQueueProcessor(agentAuthServer, snapshotAgent,
				config.getDbUrl(), config.getDbUsername(), config.getDbPassword());
		dbQueueProcessor.start();

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			log.info("Shutdown signal received — flushing agent...");
			dbQueueProcessor.shutdown();
			snapshotAgent.shutdown();
			agentAuthServer.stop();
			challengeManager.shutdown();
			registryClient.shutdown();
			log.info("Shutdown complete.");
		}, "shutdown-hook"));

		log.info("Agent ready — opening admin GUI");

		SwingUtilities.invokeLater(() -> new AdminImportPanel(
				snapshotAgent,
				profileLoader,
				config.getServerId(),
				adapter.getNamespace(),
				config.getRegistryNodes().size()));

		Thread.currentThread().join();
	}

	private static void printUsage() {
		System.out.println("""
				registry-agent — WoW private server character registry

				Usage:
				  registry-agent init              Generate keypair and skeleton config
				  registry-agent start             Start agent using ./config.yaml
				  registry-agent start <path>      Start agent using a custom config path
				  registry-agent admin-gui          Start agent + open admin import GUI
				  registry-agent admin-gui <path>  Start agent + GUI using a custom config path

				Run 'init' once before first use.
				""");
	}
}
