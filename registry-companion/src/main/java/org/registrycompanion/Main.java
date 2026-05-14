package org.registrycompanion;

import org.registrycompanion.http.CompanionServer;
import org.registrycompanion.key.KeyEncryption;
import org.registrycompanion.key.KeyManager;
import org.registrycompanion.startup.StartupRegistrar;
import org.registrycompanion.tray.TrayManager;
import org.registrycompanion.ui.RecoveryDialog;
import org.registrycompanion.ui.SetupDialog;

import javax.swing.*;
import java.nio.file.Path;
import java.util.logging.Logger;

public class Main {

	private static final Logger log = Logger.getLogger(Main.class.getName());

	private static CompanionServer server;
	private static TrayManager trayManager;

	public static void main(String[] args) {
		setLookAndFeel();

		try {
			run();
		} catch (Exception e) {
			log.severe("Fatal error during startup: " + e.getMessage());
			JOptionPane.showMessageDialog(null,
					"Registry Companion failed to start:\n\n" + e.getMessage()
							+ "\n\nCheck that no other instance is already running.",
					"Registry Companion — Error", JOptionPane.ERROR_MESSAGE);
			System.exit(1);
		}
	}

	private static void run() throws Exception {

		String appData = System.getenv("APPDATA");
		Path keystoreDir = Path.of(appData != null ? appData : System.getProperty("user.home"), "RegistryCompanion");
		keystoreDir.toFile().mkdirs();

		log.info("Keystore directory: " + keystoreDir);

		KeyEncryption encryption = KeyEncryption.fromMachineId();

		KeyManager keyManager = new KeyManager(keystoreDir, encryption);
		boolean keyLoaded = keyManager.init();

		log.info("Key loaded: " + keyLoaded);

		if (!keyLoaded) {
			log.info("First run — showing setup dialog");

			boolean setupComplete = showSetupDialog(keyManager);

			if (!setupComplete) {
				log.info("Setup cancelled — exiting");
				System.exit(0);
			}

			log.info("Setup complete — pubkey=" + keyManager.getPublicKeyHex().substring(0, 8) + "...");
		}

		RecoveryDialog recoveryDialog = new RecoveryDialog();
		StartupRegistrar startupRegistrar = new StartupRegistrar();

		if (!keyLoaded && !startupRegistrar.isRegistered()) {
			boolean registered = startupRegistrar.register();
			if (registered) {
				log.info("Auto-registered for Windows startup");
			} else {
				log.warning(
						"Could not auto-register for Windows startup " + "— player can enable manually from tray menu");
			}
		}

		try {
			server = new CompanionServer(keyManager, recoveryDialog);
			server.start();
		} catch (java.net.BindException e) {
			log.warning("Port " + CompanionServer.PORT + " already in use — " + "another instance may be running");
			JOptionPane.showMessageDialog(null, "Registry Companion is already running.\n" + "Check your system tray.",
					"Already Running", JOptionPane.INFORMATION_MESSAGE);
			System.exit(0);
		}

		trayManager = new TrayManager(keyManager, server, recoveryDialog, startupRegistrar, Main::shutdown // exit
																											// callback
		);

		SwingUtilities.invokeAndWait(trayManager::install);

		log.info("Companion ready — pubkey=" + keyManager.getPublicKeyHex().substring(0, 8) + "...");

		if (!keyLoaded) {
			trayManager.balloon("Registry Companion", "Running in the background. Your characters are now protected.",
					java.awt.TrayIcon.MessageType.INFO);
		}

		Runtime.getRuntime().addShutdownHook(new Thread(Main::shutdownSilent, "shutdown-hook"));

		Thread.currentThread().join();
	}

	private static boolean showSetupDialog(KeyManager keyManager) throws Exception {
		SetupDialog setup = new SetupDialog(keyManager);
		return setup.showAndWait();
	}

	private static void shutdown() {
		log.info("Shutting down companion...");
		shutdownSilent();
		System.exit(0);
	}

	private static void shutdownSilent() {
		try {
			if (server != null) {
				server.stop();
			}
		} catch (Exception e) {
			log.warning("Error stopping server: " + e.getMessage());
		}

		try {
			if (trayManager != null) {
				trayManager.uninstall();
			}
		} catch (Exception e) {
			log.warning("Error removing tray icon: " + e.getMessage());
		}

		log.info("Companion shutdown complete");
	}

	private static void setLookAndFeel() {
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		} catch (Exception e) {
			log.warning("Could not set system look and feel: " + e.getMessage());
		}
	}
}
