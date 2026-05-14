package org.registrycompanion.tray;

import org.registrycompanion.http.CompanionServer;
import org.registrycompanion.key.KeyManager;
import org.registrycompanion.startup.StartupRegistrar;
import org.registrycompanion.ui.RecoveryDialog;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.logging.Logger;

public class TrayManager {

	private static final Logger log = Logger.getLogger(TrayManager.class.getName());

	private final KeyManager keyManager;
	private final CompanionServer server;
	private final RecoveryDialog recoveryDialog;
	private final Runnable onExit;
	private final StartupRegistrar startupRegistrar;

	private SystemTray systemTray;
	private TrayIcon trayIcon;
	private MenuItem statusItem;
	private MenuItem pubkeyItem;
	private MenuItem startupItem;

	public TrayManager(KeyManager keyManager, CompanionServer server, RecoveryDialog recoveryDialog,
			StartupRegistrar startupRegistrar, Runnable onExit) {
		this.keyManager = keyManager;
		this.server = server;
		this.recoveryDialog = recoveryDialog;
		this.startupRegistrar = startupRegistrar;
		this.onExit = onExit;
	}

	public void install() {
		if (!SystemTray.isSupported()) {
			throw new UnsupportedOperationException("System tray is not supported on this platform");
		}

		systemTray = SystemTray.getSystemTray();

		Image icon = buildIcon(keyManager.isReady() ? IconState.READY : IconState.SETUP_NEEDED);
		PopupMenu menu = buildMenu();

		trayIcon = new TrayIcon(icon, "Registry Companion", menu);
		trayIcon.setImageAutoSize(true);

		trayIcon.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getButton() == MouseEvent.BUTTON1) {
					showBalloon();
				}
			}
		});

		try {
			systemTray.add(trayIcon);
			log.info("Tray icon installed");
		} catch (AWTException e) {
			throw new RuntimeException("Failed to install tray icon: " + e.getMessage(), e);
		}
	}

	private PopupMenu buildMenu() {
		PopupMenu menu = new PopupMenu();

		MenuItem title = new MenuItem("Registry Companion");
		title.setEnabled(false);
		menu.add(title);
		menu.addSeparator();

		statusItem = new MenuItem(statusText());
		statusItem.setEnabled(false);
		menu.add(statusItem);

		pubkeyItem = new MenuItem(pubkeyText());
		pubkeyItem.addActionListener(e -> copyPubkey());
		menu.add(pubkeyItem);

		menu.addSeparator();

		MenuItem showPhrase = new MenuItem("Show Recovery Phrase");
		showPhrase.addActionListener(e -> SwingUtilities.invokeLater(() -> {
			if (!keyManager.isReady()) {
				showError("No key loaded. Complete setup first.");
				return;
			}
			recoveryDialog.showPhrase(keyManager.getRecoveryPhrase());
		}));
		menu.add(showPhrase);

		MenuItem restorePhrase = new MenuItem("Restore from Recovery Phrase");
		restorePhrase.addActionListener(e -> SwingUtilities.invokeLater(() -> recoveryDialog.showRestore(keyManager)));
		menu.add(restorePhrase);

		menu.addSeparator();

		startupItem = new MenuItem(startupItemText());
		startupItem.addActionListener(e -> {
			boolean nowRegistered = startupRegistrar.toggle();
			startupItem.setLabel(startupItemText());
			balloon("Registry Companion",
					nowRegistered ? "Will now start automatically with Windows." : "Removed from Windows startup.",
					TrayIcon.MessageType.INFO);
			log.info("Startup registration toggled — now registered: " + nowRegistered);
		});
		menu.add(startupItem);

		menu.addSeparator();

		MenuItem exit = new MenuItem("Exit");
		exit.addActionListener(e -> {
			int confirm = JOptionPane.showConfirmDialog(null,
					"Exit Registry Companion?\n\nServers won't be able to save your character data while it's closed.",
					"Exit", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (confirm == JOptionPane.YES_OPTION) {
				onExit.run();
			}
		});
		menu.add(exit);

		return menu;
	}

	public void refresh() {
		SwingUtilities.invokeLater(() -> {
			if (trayIcon == null)
				return;

			IconState state = keyManager.isReady() ? IconState.READY : IconState.SETUP_NEEDED;
			trayIcon.setImage(buildIcon(state));
			trayIcon.setToolTip(tooltipText());

			if (statusItem != null)
				statusItem.setLabel(statusText());
			if (pubkeyItem != null)
				pubkeyItem.setLabel(pubkeyText());
		});
	}

	public void balloon(String caption, String message, TrayIcon.MessageType type) {
		if (trayIcon != null) {
			trayIcon.displayMessage(caption, message, type);
		}
	}

	private Image buildIcon(IconState state) {
		int size = 16;
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		Color shieldColor = switch (state) {
		case READY -> new Color(60, 200, 80); // green
		case SETUP_NEEDED -> new Color(220, 180, 40); // yellow
		case ERROR -> new Color(220, 60, 60); // red
		};

		int[] xPoints = { 2, 14, 14, 8, 2 };
		int[] yPoints = { 2, 2, 10, 15, 10 };
		g.setColor(shieldColor);
		g.fillPolygon(xPoints, yPoints, 5);

		g.setColor(shieldColor.darker());
		g.setStroke(new BasicStroke(1f));
		g.drawPolygon(xPoints, yPoints, 5);

		g.setColor(Color.WHITE);
		g.setFont(new Font("Segoe UI", Font.BOLD, 8));
		FontMetrics fm = g.getFontMetrics();
		String mark = (state == IconState.READY) ? "✔" : "!";
		int mx = (size - fm.stringWidth(mark)) / 2;
		g.drawString(mark, mx, 9);

		g.dispose();
		return img;
	}

	private void showBalloon() {
		if (keyManager.isReady()) {
			balloon("Registry Companion", "Ready - your characters are protected.\nKey: "
					+ keyManager.getPublicKeyHex().substring(0, 12) + "...", TrayIcon.MessageType.INFO);
		} else {
			balloon("Registry Companion", "Setup not complete. Right-click for options.", TrayIcon.MessageType.WARNING);
		}
	}

	private void copyPubkey() {
		if (!keyManager.isReady()) {
			showError("No key loaded.");
			return;
		}
		Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
		clipboard.setContents(new StringSelection(keyManager.getPublicKeyHex()), null);
		balloon("Copied", "Public key copied to clipboard.", TrayIcon.MessageType.INFO);
	}

	private void showError(String message) {
		JOptionPane.showMessageDialog(null, message, "Registry Companion", JOptionPane.ERROR_MESSAGE);
	}

	private String startupItemText() {
		return startupRegistrar.isRegistered() ? "Start with Windows" : "Start with Windows";
	}

	private String statusText() {
		return keyManager.isReady() ? "Ready - key loaded" : "Setup needed";
	}

	private String pubkeyText() {
		if (!keyManager.isReady())
			return "No key loaded";
		String pk = keyManager.getPublicKeyHex();
		return "Key: " + pk.substring(0, 8) + "..." + pk.substring(pk.length() - 4);
	}

	private String tooltipText() {
		return keyManager.isReady() ? "Registry Companion — Ready" : "Registry Companion — Setup needed";
	}

	public void uninstall() {
		if (systemTray != null && trayIcon != null) {
			systemTray.remove(trayIcon);
			log.info("Tray icon removed");
		}
	}

	public enum IconState {
		READY, SETUP_NEEDED, ERROR
	}
}
