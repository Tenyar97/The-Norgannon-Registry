package org.registrycompanion.ui;

import org.registrycompanion.key.KeyManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.logging.Logger;

public class SetupDialog {

	private static final Logger log = Logger.getLogger(SetupDialog.class.getName());

	private static final Color BG_COLOR = new Color(30, 30, 35);
	private static final Color FG_COLOR = new Color(220, 220, 220);
	private static final Color ACCENT_COLOR = new Color(255, 200, 60); // WoW gold
	private static final Color PHRASE_BG = new Color(20, 20, 25);
	private static final Color BTN_COLOR = new Color(60, 60, 70);
	private static final Font TITLE_FONT = new Font("Segoe UI", Font.BOLD, 18);
	private static final Font BODY_FONT = new Font("Segoe UI", Font.PLAIN, 13);
	private static final Font PHRASE_FONT = new Font("Consolas", Font.PLAIN, 13);
	private static final Font SMALL_FONT = new Font("Segoe UI", Font.PLAIN, 11);

	private final KeyManager keyManager;

	private JDialog dialog;
	private JPanel cardPanel;
	private CardLayout cardLayout;

	private boolean setupComplete = false;
	private String recoveryPhrase;

	public SetupDialog(KeyManager keyManager) {
		this.keyManager = keyManager;
	}

	public boolean showAndWait() {
		try {
			SwingUtilities.invokeAndWait(this::buildAndShow);
		} catch (Exception e) {
			log.severe("Failed to show setup dialog: " + e.getMessage());
			return false;
		}
		return setupComplete;
	}

	private void buildAndShow() {
		dialog = new JDialog((Frame) null, "Registry Companion - Setup", true);
		dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		dialog.setSize(520, 460);
		dialog.setResizable(false);
		dialog.setLocationRelativeTo(null);

		JPanel root = new JPanel(new BorderLayout());
		root.setBackground(BG_COLOR);
		root.setBorder(new EmptyBorder(0, 0, 0, 0));

		root.add(buildHeader(), BorderLayout.NORTH);

		cardLayout = new CardLayout();
		cardPanel = new JPanel(cardLayout);
		cardPanel.setBackground(BG_COLOR);
		cardPanel.add(buildStep1(), "step1");
		cardPanel.add(buildStep2(), "step2");
		cardPanel.add(buildStep3(), "step3");
		root.add(cardPanel, BorderLayout.CENTER);

		dialog.setContentPane(root);
		dialog.setVisible(true);
	}

	private JPanel buildHeader() {
		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(new Color(20, 20, 24));
		header.setBorder(new EmptyBorder(16, 24, 16, 24));

		JLabel icon = new JLabel("🔑");
		icon.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 28));
		icon.setBorder(new EmptyBorder(0, 0, 0, 12));

		JLabel title = new JLabel("Registry Companion");
		title.setFont(TITLE_FONT);
		title.setForeground(ACCENT_COLOR);

		JLabel sub = new JLabel("Your character identity, preserved forever");
		sub.setFont(SMALL_FONT);
		sub.setForeground(new Color(150, 150, 160));

		JPanel text = new JPanel(new GridLayout(2, 1, 0, 2));
		text.setBackground(new Color(20, 20, 24));
		text.add(title);
		text.add(sub);

		header.add(icon, BorderLayout.WEST);
		header.add(text, BorderLayout.CENTER);

		return header;
	}

	private JPanel buildStep1() {
		JPanel panel = new JPanel(new BorderLayout(0, 16));
		panel.setBackground(BG_COLOR);
		panel.setBorder(new EmptyBorder(24, 32, 24, 32));

		JLabel heading = styledLabel("Welcome!", TITLE_FONT, FG_COLOR);

		JTextArea body = new JTextArea("This companion runs alongside WoW and keeps your character safe.\n\n"
				+ "If a private server shuts down, your character data is preserved "
				+ "in a secure registry - and you can import it into any other server "
				+ "that supports this protocol.\n\n" + "Setup takes about 30 seconds. You'll need a pen and paper.");
		body.setFont(BODY_FONT);
		body.setForeground(FG_COLOR);
		body.setBackground(BG_COLOR);
		body.setEditable(false);
		body.setWrapStyleWord(true);
		body.setLineWrap(true);
		body.setBorder(null);

		JButton next = accentButton("Get Started →");
		next.addActionListener(e -> {
			try {
				keyManager.generate();
				recoveryPhrase = keyManager.getRecoveryPhrase();
				refreshStep2Phrase();
				cardLayout.show(cardPanel, "step2");
			} catch (Exception ex) {
				JOptionPane.showMessageDialog(dialog, "Failed to generate keypair: " + ex.getMessage(), "Error",
						JOptionPane.ERROR_MESSAGE);
			}
		});

		JButton cancel = plainButton("Cancel");
		cancel.addActionListener(e -> dialog.dispose());

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		buttons.setBackground(BG_COLOR);
		buttons.add(cancel);
		buttons.add(next);

		panel.add(heading, BorderLayout.NORTH);
		panel.add(body, BorderLayout.CENTER);
		panel.add(buttons, BorderLayout.SOUTH);

		return panel;
	}

	private JLabel phraseLabel;

	private JPanel buildStep2() {
		JPanel panel = new JPanel(new BorderLayout(0, 12));
		panel.setBackground(BG_COLOR);
		panel.setBorder(new EmptyBorder(20, 32, 20, 32));

		JLabel heading = styledLabel("Write down your recovery phrase", TITLE_FONT, FG_COLOR);

		JLabel warning = styledLabel("If you lose this, and reinstall Windows, your key is gone forever. (A long time)",
				SMALL_FONT, new Color(255, 160, 60));

		phraseLabel = new JLabel("<generating...>");
		phraseLabel.setFont(PHRASE_FONT);
		phraseLabel.setForeground(ACCENT_COLOR);
		phraseLabel.setHorizontalAlignment(SwingConstants.CENTER);

		JPanel phraseBox = new JPanel(new BorderLayout());
		phraseBox.setBackground(PHRASE_BG);
		phraseBox.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(60, 60, 80), 1),
				new EmptyBorder(12, 16, 12, 16)));
		phraseBox.add(phraseLabel, BorderLayout.CENTER);

		JButton copy = plainButton("Copy to clipboard");
		copy.addActionListener(e -> {
			if (recoveryPhrase != null) {
				Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(recoveryPhrase), null);
				copy.setText("✓ Copied!");
				Timer t = new Timer(2000, ev -> copy.setText("Copy to clipboard"));
				t.setRepeats(false);
				t.start();
			}
		});

		JPanel copyRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 4));
		copyRow.setBackground(BG_COLOR);
		copyRow.add(copy);

		JCheckBox confirmed = new JCheckBox("I've written down my recovery phrase");
		confirmed.setFont(BODY_FONT);
		confirmed.setToolTipText(
				"IF you lose these words and reinstall Windows; Nobody can help you. It's gone forever");
		confirmed.setForeground(FG_COLOR);
		confirmed.setBackground(BG_COLOR);

		JButton next = accentButton("Continue →");
		next.setEnabled(false);
		confirmed.addActionListener(e -> next.setEnabled(confirmed.isSelected()));
		next.addActionListener(e -> cardLayout.show(cardPanel, "step3"));

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		buttons.setBackground(BG_COLOR);
		buttons.add(next);

		JPanel labels = new JPanel(new GridLayout(2, 1, 0, 6));
		labels.setBackground(BG_COLOR);
		labels.add(heading);
		labels.add(warning);

		JPanel top = new JPanel(new BorderLayout(0, 8));
		top.setBackground(BG_COLOR);
		top.add(labels, BorderLayout.NORTH);
		top.add(phraseBox, BorderLayout.CENTER);
		top.add(copyRow, BorderLayout.SOUTH);

		panel.add(top, BorderLayout.NORTH);
		panel.add(confirmed, BorderLayout.CENTER);
		panel.add(buttons, BorderLayout.SOUTH);

		return panel;
	}

	private void refreshStep2Phrase() {
		if (phraseLabel != null && recoveryPhrase != null) {
			String[] words = recoveryPhrase.split(" ");
			StringBuilder sb = new StringBuilder("<html><center>");
			for (int i = 0; i < words.length; i++) {
				sb.append(words[i]);
				if ((i + 1) % 6 == 0 && i < words.length - 1)
					sb.append("<br>");
				else if (i < words.length - 1)
					sb.append("  ");
			}
			sb.append("</center></html>");
			phraseLabel.setText(sb.toString());
		}
	}

	private JPanel buildStep3() {
		JPanel panel = new JPanel(new BorderLayout(0, 16));
		panel.setBackground(BG_COLOR);
		panel.setBorder(new EmptyBorder(24, 32, 24, 32));

		JLabel heading = styledLabel("You're all set!", TITLE_FONT, new Color(100, 220, 100));

		JTextArea body = new JTextArea("Your identity key has been created and saved securely.\n\n"
				+ "The companion will run silently in your system tray. "
				+ "When you log into a server that supports this protocol, "
				+ "it will handle authentication automatically.");
		body.setFont(BODY_FONT);
		body.setForeground(FG_COLOR);
		body.setBackground(BG_COLOR);
		body.setEditable(false);
		body.setWrapStyleWord(true);
		body.setLineWrap(true);
		body.setBorder(null);

		JButton finish = accentButton("Start Playing");
		finish.addActionListener(e -> {
			setupComplete = true;
			dialog.dispose();
		});

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		buttons.setBackground(BG_COLOR);
		buttons.add(finish);

		panel.add(heading, BorderLayout.NORTH);
		panel.add(body, BorderLayout.CENTER);
		panel.add(buttons, BorderLayout.SOUTH);

		return panel;
	}

	private JLabel styledLabel(String text, Font font, Color color) {
		JLabel label = new JLabel(text);
		label.setFont(font);
		label.setForeground(color);
		return label;
	}

	private JButton accentButton(String text) {
		JButton btn = new JButton(text);
		btn.setFont(new Font("Segoe UI", Font.BOLD, 13));
		btn.setBackground(ACCENT_COLOR);
		btn.setForeground(Color.BLACK);
		btn.setFocusPainted(false);
		btn.setBorderPainted(false);
		btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		btn.setBorder(new EmptyBorder(8, 20, 8, 20));
		return btn;
	}

	private JButton plainButton(String text) {
		JButton btn = new JButton(text);
		btn.setFont(BODY_FONT);
		btn.setBackground(BTN_COLOR);
		btn.setForeground(FG_COLOR);
		btn.setFocusPainted(false);
		btn.setBorderPainted(false);
		btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		btn.setBorder(new EmptyBorder(8, 16, 8, 16));
		return btn;
	}
}
