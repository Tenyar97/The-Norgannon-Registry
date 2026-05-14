package org.registrycompanion.ui;

import org.registrycompanion.key.KeyManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.logging.Logger;

public class RecoveryDialog {

	private static final Logger log = Logger.getLogger(RecoveryDialog.class.getName());

	private static final Color BG_COLOR = new Color(30, 30, 35);
	private static final Color FG_COLOR = new Color(220, 220, 220);
	private static final Color ACCENT_COLOR = new Color(255, 200, 60);
	private static final Color PHRASE_BG = new Color(20, 20, 25);
	private static final Color BTN_COLOR = new Color(60, 60, 70);
	private static final Color WARN_COLOR = new Color(255, 160, 60);
	private static final Font TITLE_FONT = new Font("Segoe UI", Font.BOLD, 16);
	private static final Font BODY_FONT = new Font("Segoe UI", Font.PLAIN, 13);
	private static final Font PHRASE_FONT = new Font("Consolas", Font.PLAIN, 13);
	private static final Font SMALL_FONT = new Font("Segoe UI", Font.PLAIN, 11);

	public void showPhrase(String phrase) {
		JDialog dialog = new JDialog((Frame) null, "Recovery Phrase", true);
		dialog.setSize(520, 320);
		dialog.setResizable(false);
		dialog.setLocationRelativeTo(null);
		dialog.setAlwaysOnTop(true);

		JPanel root = new JPanel(new BorderLayout(0, 16));
		root.setBackground(BG_COLOR);
		root.setBorder(new EmptyBorder(24, 32, 24, 32));

		JLabel title = styledLabel("Your Recovery Phrase", TITLE_FONT, FG_COLOR);
		JLabel warn = styledLabel("Keep this private! Anyone with these words controls your characters.", SMALL_FONT,
				WARN_COLOR);

		JPanel top = new JPanel(new GridLayout(2, 1, 0, 6));
		top.setBackground(BG_COLOR);
		top.add(title);
		top.add(warn);

		JLabel phraseLabel = new JLabel(formatPhrase(phrase));
		phraseLabel.setFont(PHRASE_FONT);
		phraseLabel.setForeground(ACCENT_COLOR);
		phraseLabel.setHorizontalAlignment(SwingConstants.CENTER);

		JPanel phraseBox = new JPanel(new BorderLayout());
		phraseBox.setBackground(PHRASE_BG);
		phraseBox.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(60, 60, 80), 1),
				new EmptyBorder(14, 16, 14, 16)));
		phraseBox.add(phraseLabel, BorderLayout.CENTER);

		JButton copy = plainButton("Copy");
		copy.addActionListener(e -> {
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(phrase), null);
			copy.setText("Copied!");
			Timer t = new Timer(2000, ev -> copy.setText("Copy"));
			t.setRepeats(false);
			t.start();
		});

		JButton close = accentButton("Done");
		close.addActionListener(e -> dialog.dispose());

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		buttons.setBackground(BG_COLOR);
		buttons.add(copy);
		buttons.add(close);

		root.add(top, BorderLayout.NORTH);
		root.add(phraseBox, BorderLayout.CENTER);
		root.add(buttons, BorderLayout.SOUTH);

		dialog.setContentPane(root);
		dialog.setVisible(true);
	}

	public void showRestore(KeyManager keyManager) {
		JDialog dialog = new JDialog((Frame) null, "Restore from Recovery Phrase", true);
		dialog.setSize(520, 340);
		dialog.setResizable(false);
		dialog.setLocationRelativeTo(null);
		dialog.setAlwaysOnTop(true);

		JPanel root = new JPanel(new BorderLayout(0, 16));
		root.setBackground(BG_COLOR);
		root.setBorder(new EmptyBorder(24, 32, 24, 32));

		JLabel title = styledLabel("Restore from Recovery Phrase", TITLE_FONT, FG_COLOR);
		JLabel inst = styledLabel("Enter your 24 words separated by spaces. This will replace your current key.",
				SMALL_FONT, new Color(160, 160, 170));

		JPanel top = new JPanel(new GridLayout(2, 1, 0, 6));
		top.setBackground(BG_COLOR);
		top.add(title);
		top.add(inst);

		JTextArea input = new JTextArea(3, 40);
		input.setFont(PHRASE_FONT);
		input.setForeground(ACCENT_COLOR);
		input.setBackground(PHRASE_BG);
		input.setCaretColor(FG_COLOR);
		input.setLineWrap(true);
		input.setWrapStyleWord(true);
		input.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(60, 60, 80), 1),
				new EmptyBorder(10, 12, 10, 12)));

		JLabel status = styledLabel("", SMALL_FONT, new Color(100, 200, 100));

		JButton restore = accentButton("Restore Key");
		restore.addActionListener(e -> {
			String phrase = input.getText().trim();
			if (phrase.isBlank()) {
				status.setForeground(WARN_COLOR);
				status.setText("Please enter your recovery phrase.");
				return;
			}

			try {
				keyManager.restoreFromPhrase(phrase);
				status.setForeground(new Color(100, 220, 100));
				status.setText(
						"Key restored successfully! Pubkey: " + keyManager.getPublicKeyHex().substring(0, 12) + "...");
				restore.setEnabled(false);

				Timer t = new Timer(2000, ev -> dialog.dispose());
				t.setRepeats(false);
				t.start();

			} catch (IllegalArgumentException ex) {
				status.setForeground(WARN_COLOR);
				status.setText("Invalid phrase: " + ex.getMessage());
			} catch (Exception ex) {
				status.setForeground(WARN_COLOR);
				status.setText("Restore failed: " + ex.getMessage());
				log.severe("Recovery restore failed: " + ex.getMessage());
			}
		});

		JButton cancel = plainButton("Cancel");
		cancel.addActionListener(e -> dialog.dispose());

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		buttons.setBackground(BG_COLOR);
		buttons.add(cancel);
		buttons.add(restore);

		JPanel center = new JPanel(new BorderLayout(0, 8));
		center.setBackground(BG_COLOR);
		center.add(input, BorderLayout.CENTER);
		center.add(status, BorderLayout.SOUTH);

		root.add(top, BorderLayout.NORTH);
		root.add(center, BorderLayout.CENTER);
		root.add(buttons, BorderLayout.SOUTH);

		dialog.setContentPane(root);
		dialog.setVisible(true);
	}

	private String formatPhrase(String phrase) {
		String[] words = phrase.split(" ");
		StringBuilder sb = new StringBuilder("<html><center>");
		for (int i = 0; i < words.length; i++) {
			sb.append(words[i]);
			if ((i + 1) % 6 == 0 && i < words.length - 1)
				sb.append("<br>");
			else if (i < words.length - 1)
				sb.append("  ");
		}
		sb.append("</center></html>");
		return sb.toString();
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
