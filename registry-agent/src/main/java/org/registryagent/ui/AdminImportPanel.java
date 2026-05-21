package org.registryagent.ui;

import org.registryagent.snapshot.ImportProfile;
import org.registryagent.snapshot.ImportProfileLoader;
import org.registryagent.snapshot.SnapshotAgent;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

public class AdminImportPanel extends JFrame {

	private static final Logger log = Logger.getLogger(AdminImportPanel.class.getName());

	private static final Color BG = new Color(30, 30, 35);
	private static final Color BG_DARK = new Color(20, 20, 24);
	private static final Color BG_FIELD = new Color(22, 22, 28);
	private static final Color BORDER = new Color(60, 60, 80);
	private static final Color FG = new Color(220, 220, 220);
	private static final Color FG_DIM = new Color(140, 140, 155);
	private static final Color GOLD = new Color(255, 200, 60);
	private static final Color GREEN = new Color(80, 200, 90);
	private static final Color RED = new Color(220, 70, 70);
	private static final Color BTN_PLAIN = new Color(55, 55, 68);

	private static final Font FONT_TITLE = new Font("Segoe UI", Font.BOLD, 16);
	private static final Font FONT_LABEL = new Font("Segoe UI", Font.PLAIN, 12);
	private static final Font FONT_BOLD = new Font("Segoe UI", Font.BOLD, 12);
	private static final Font FONT_MONO = new Font("Consolas", Font.PLAIN, 12);
	private static final Font FONT_SMALL = new Font("Segoe UI", Font.PLAIN, 11);

	private final SnapshotAgent snapshotAgent;
	private final ImportProfileLoader profileLoader;
	private final String serverId;
	private final String adapterNamespace;
	private final int nodeCount;

	private JTextField characterIdField;
	private JTextField pubKeyField;
	private JTextField accountIdField;
	private JComboBox<String> profileCombo;
	private JButton importBtn;

	private JLabel levelCapLabel, goldCapLabel;
	private JLabel equipLabel, invLabel, bankLabel, petsLabel;
	private JLabel skillsLabel, repLabel, questsLabel, talentsLabel;
	private JLabel customTemplatesLabel;

	private JTextArea resultArea;

	private ImportProfile currentProfile;

	public AdminImportPanel(SnapshotAgent snapshotAgent, ImportProfileLoader profileLoader, String serverId,
			String adapterNamespace, int nodeCount) {
		super("Admin Import - registry-agent");

		this.snapshotAgent = snapshotAgent;
		this.profileLoader = profileLoader;
		this.serverId = serverId;
		this.adapterNamespace = adapterNamespace;
		this.nodeCount = nodeCount;

		setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				int choice = JOptionPane.showConfirmDialog(AdminImportPanel.this,
						"Close the admin panel?\n\nThe agent will keep running in the background.", "Close",
						JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
				if (choice == JOptionPane.YES_OPTION)
					dispose();
			}
		});

		setSize(620, 660);
		setMinimumSize(new Dimension(540, 580));
		setLocationRelativeTo(null);
		setResizable(true);

		JPanel root = new JPanel(new BorderLayout());
		root.setBackground(BG);
		root.add(buildHeader(), BorderLayout.NORTH);
		root.add(buildContent(), BorderLayout.CENTER);
		setContentPane(root);

		refreshProfileCombo();
		setVisible(true);
	}

	private JPanel buildHeader() {
		JPanel header = new JPanel(new BorderLayout(12, 0));
		header.setBackground(BG_DARK);
		header.setBorder(new EmptyBorder(14, 20, 14, 20));

		JLabel icon = new JLabel("⚙");
		icon.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 26));
		icon.setForeground(GOLD);
		icon.setBorder(new EmptyBorder(0, 0, 0, 10));

		JLabel title = new JLabel("Admin Import");
		title.setFont(FONT_TITLE);
		title.setForeground(GOLD);

		String info = "Server: " + abbrev(serverId, 12) + "  ·  Adapter: " + adapterNamespace + "  ·  Nodes: "
				+ nodeCount;
		JLabel infoLabel = new JLabel(info);
		infoLabel.setFont(FONT_SMALL);
		infoLabel.setForeground(FG_DIM);

		JPanel text = new JPanel(new GridLayout(2, 1, 0, 3));
		text.setBackground(BG_DARK);
		text.add(title);
		text.add(infoLabel);

		header.add(icon, BorderLayout.WEST);
		header.add(text, BorderLayout.CENTER);
		return header;
	}

	private JPanel buildContent() {
		JPanel content = new JPanel(new GridBagLayout());
		content.setBackground(BG);
		content.setBorder(new EmptyBorder(18, 22, 18, 22));

		GridBagConstraints c = new GridBagConstraints();
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1.0;
		c.gridx = 0;
		c.insets = new Insets(0, 0, 12, 0);

		c.gridy = 0;
		content.add(buildCharacterSection(), c);

		c.gridy = 1;
		content.add(buildSummarySection(), c);

		c.gridy = 2;
		c.insets = new Insets(0, 0, 12, 0);
		content.add(buildActionRow(), c);

		c.gridy = 3;
		c.weighty = 1.0;
		c.fill = GridBagConstraints.BOTH;
		c.insets = new Insets(0, 0, 0, 0);
		content.add(buildResultSection(), c);

		return content;
	}

	private JPanel buildCharacterSection() {
		JPanel panel = titledPanel("Character");
		panel.setLayout(new GridBagLayout());

		GridBagConstraints lc = labelConstraints();
		GridBagConstraints fc = fieldConstraints();

		lc.gridy = fc.gridy = 0;
		panel.add(fieldLabel("Character ID"), lc);
		characterIdField = inputField();
		characterIdField.setToolTipText("Registry UUID for this character - visible in the player's companion app.");
		panel.add(characterIdField, fc);

		GridBagConstraints sc = new GridBagConstraints();
		sc.gridy = 1;
		sc.gridx = 0;
		sc.gridwidth = 2;
		sc.ipady = 5;
		panel.add(new JPanel() {
			{
				setOpaque(false);
			}
		}, sc);

		lc.gridy = fc.gridy = 2;
		panel.add(fieldLabel("Player Pub Key"), lc);
		pubKeyField = inputField();
		pubKeyField.setToolTipText(
				"Player's Ed25519 public key (64-char hex) - player copies this from their companion tray.");
		panel.add(pubKeyField, fc);

		sc.gridy = 3;
		panel.add(new JPanel() {
			{
				setOpaque(false);
			}
		}, sc);

		lc.gridy = fc.gridy = 4;
		panel.add(fieldLabel("Account ID"), lc);
		accountIdField = inputField();
		accountIdField.setToolTipText("Local game account ID (integer from the account table).\n"
				+ "Required only when the character does not already exist on this server.\n"
				+ "Leave blank to update an existing character without creating a new one.");
		JLabel accountHint = new JLabel("  for auto-create if not found");
		accountHint.setFont(FONT_SMALL);
		accountHint.setForeground(FG_DIM);
		JPanel accountRow = new JPanel(new BorderLayout(4, 0));
		accountRow.setBackground(BG);
		accountRow.add(accountIdField, BorderLayout.CENTER);
		accountRow.add(accountHint, BorderLayout.EAST);
		panel.add(accountRow, fc);

		sc.gridy = 5;
		panel.add(new JPanel() {
			{
				setOpaque(false);
			}
		}, sc);

		lc.gridy = fc.gridy = 6;
		panel.add(fieldLabel("Profile"), lc);

		profileCombo = new JComboBox<>();
		styleCombo(profileCombo);
		profileCombo.addActionListener(e -> onProfileSelected());

		JButton refreshBtn = plainButton("↺");
		refreshBtn.setToolTipText("Reload profiles from the profiles/ directory");
		refreshBtn.setFont(new Font("Segoe UI", Font.BOLD, 14));
		refreshBtn.setPreferredSize(new Dimension(34, 28));
		refreshBtn.addActionListener(e -> {
			profileLoader.invalidateAll();
			refreshProfileCombo();
			setResult("Profiles reloaded from disk.", FG_DIM);
		});

		JPanel comboRow = new JPanel(new BorderLayout(4, 0));
		comboRow.setBackground(BG);
		comboRow.add(profileCombo, BorderLayout.CENTER);
		comboRow.add(refreshBtn, BorderLayout.EAST);

		panel.add(comboRow, fc);

		return panel;
	}

	private JPanel buildSummarySection() {
		JPanel panel = titledPanel("Profile Summary");
		panel.setLayout(new GridBagLayout());

		GridBagConstraints c = new GridBagConstraints();
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1.0;
		c.gridx = 0;
		c.anchor = GridBagConstraints.WEST;
		c.insets = new Insets(0, 0, 6, 0);

		c.gridy = 0;
		JPanel capsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 18, 0));
		capsRow.setBackground(BG);
		levelCapLabel = summaryLabel("Level cap: -");
		goldCapLabel = summaryLabel("Gold cap: -");
		capsRow.add(levelCapLabel);
		capsRow.add(goldCapLabel);
		panel.add(capsRow, c);

		c.gridy = 1;
		JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 0));
		row1.setBackground(BG);
		equipLabel = summaryLabel("Equipment");
		invLabel = summaryLabel("Inventory");
		bankLabel = summaryLabel("Bank");
		petsLabel = summaryLabel("Pets");
		row1.add(equipLabel);
		row1.add(invLabel);
		row1.add(bankLabel);
		row1.add(petsLabel);
		panel.add(row1, c);

		c.gridy = 2;
		c.insets = new Insets(0, 0, 0, 0);
		JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 0));
		row2.setBackground(BG);
		skillsLabel = summaryLabel("Skills");
		repLabel = summaryLabel("Reputation");
		questsLabel = summaryLabel("Quests");
		talentsLabel = summaryLabel("Talents");
		customTemplatesLabel = summaryLabel("Custom item templates");
		row2.add(skillsLabel);
		row2.add(repLabel);
		row2.add(questsLabel);
		row2.add(talentsLabel);
		row2.add(customTemplatesLabel);
		panel.add(row2, c);

		return panel;
	}

	private JPanel buildActionRow() {
		JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		row.setBackground(BG);

		importBtn = new JButton("Import Character");
		importBtn.setFont(FONT_BOLD);
		importBtn.setBackground(GOLD);
		importBtn.setForeground(Color.BLACK);
		importBtn.setFocusPainted(false);
		importBtn.setBorderPainted(false);
		importBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		importBtn.setBorder(new EmptyBorder(10, 28, 10, 28));
		importBtn.addActionListener(e -> runImport());

		row.add(importBtn);
		return row;
	}

	private JPanel buildResultSection() {
		JPanel panel = titledPanel("Result");
		panel.setLayout(new BorderLayout());

		resultArea = new JTextArea("No import run yet.");
		resultArea.setFont(FONT_MONO);
		resultArea.setForeground(FG_DIM);
		resultArea.setBackground(BG_FIELD);
		resultArea.setEditable(false);
		resultArea.setWrapStyleWord(true);
		resultArea.setLineWrap(true);
		resultArea.setBorder(new EmptyBorder(8, 10, 8, 10));

		JScrollPane scroll = new JScrollPane(resultArea);
		scroll.setBorder(new LineBorder(BORDER, 1));
		scroll.getViewport().setBackground(BG_FIELD);

		panel.add(scroll, BorderLayout.CENTER);
		return panel;
	}

	private void refreshProfileCombo() {
		String previous = (String) profileCombo.getSelectedItem();
		profileCombo.removeAllItems();
		profileCombo.addItem("(Permissive - no restrictions)");

		List<String> names = profileLoader.listAvailable();
		for (String name : names)
			profileCombo.addItem(name);

		if (previous != null) {
			for (int i = 0; i < profileCombo.getItemCount(); i++) {
				if (previous.equals(profileCombo.getItemAt(i))) {
					profileCombo.setSelectedIndex(i);
					break;
				}
			}
		}
		onProfileSelected();
	}

	private void onProfileSelected() {
		String selected = (String) profileCombo.getSelectedItem();
		if (selected == null || selected.startsWith("(")) {
			currentProfile = null;
			updateSummary(ImportProfile.permissive());
		} else {
			currentProfile = profileLoader.load(selected);
			updateSummary(currentProfile != null ? currentProfile : ImportProfile.permissive());
		}
	}

	private void updateSummary(ImportProfile p) {
		levelCapLabel.setText(p.getMaxLevel() > 0 ? "Level cap: " + p.getMaxLevel() : "Level cap: none");
		goldCapLabel.setText(
				p.getMaxGoldCopper() > 0 ? "Gold cap: " + (p.getMaxGoldCopper() / 10_000) + "g" : "Gold cap: none");

		setToggle(equipLabel, "Equipment", p.isImportEquipment());
		setToggle(invLabel, "Inventory", p.isImportInventory());
		setToggle(bankLabel, "Bank", p.isImportBank());
		setToggle(petsLabel, "Pets", p.isImportPets());
		setToggle(skillsLabel, "Skills", p.isImportSkills());
		setToggle(repLabel, "Reputation", p.isImportReputation());
		setToggle(questsLabel, "Quests", p.isImportQuestsCompleted());
		setToggle(talentsLabel, "Talents", p.isImportTalents());
		setToggle(customTemplatesLabel, "Custom item templates", p.isImportCustomItemTemplates());
	}

	private void runImport() {
		String characterId = characterIdField.getText().trim();
		String playerPubKey = pubKeyField.getText().trim();

		if (characterId.isEmpty()) {
			shake(characterIdField);
			setResult("Character ID is required.", RED);
			return;
		}
		if (playerPubKey.isEmpty()) {
			shake(pubKeyField);
			setResult("Player public key is required.", RED);
			return;
		}

		int accountId = 0;
		String accountIdText = accountIdField.getText().trim();
		if (!accountIdText.isEmpty()) {
			try {
				accountId = Integer.parseInt(accountIdText);
				if (accountId <= 0)
					throw new NumberFormatException("non-positive");
			} catch (NumberFormatException e) {
				shake(accountIdField);
				setResult("Account ID must be a positive integer (e.g. 1, 2, 3).", RED);
				return;
			}
		}

		importBtn.setEnabled(false);
		importBtn.setText("Importing…");
		setResult("Fetching snapshot from registry…", FG_DIM);

		String profileName = currentProfile != null ? currentProfile.getName() : "permissive";
		final int finalAccountId = accountId;
		log.info("Admin import - characterId=" + characterId + " accountId=" + (accountId > 0 ? accountId : "none")
				+ " profile=" + profileName);

		new SwingWorker<Boolean, Void>() {
			@Override
			protected Boolean doInBackground() {
				return snapshotAgent.importFromRegistry(characterId, playerPubKey, currentProfile, finalAccountId);
			}

			@Override
			protected void done() {
				importBtn.setEnabled(true);
				importBtn.setText("Import Character");
				try {
					if (get()) {
						String created = finalAccountId > 0 ? "\nAccount: " + finalAccountId + " (created if not found)"
								: "";
						setResult("Import successful.\n" + "Character: " + characterId + created + "\n"
								+ "Profile applied: " + profileName, GREEN);
					} else {

						setResult("No snapshot found in registry.\n\n"
								+ "The player must authenticate at least once with their companion app "
								+ "before a snapshot is available to import.\n\n" + "Character ID: " + characterId,
								RED);
					}
				} catch (ExecutionException ee) {
					Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
					setResult("Import failed.\n\n" + cause.getMessage(), RED);
				} catch (Exception e) {
					setResult("Import error: " + e.getMessage(), RED);
				}
			}
		}.execute();
	}

	private void setResult(String text, Color color) {
		resultArea.setForeground(color);
		resultArea.setText(text);
	}

	private void setToggle(JLabel label, String name, boolean enabled) {
		label.setText((enabled ? "✔ " : "✘ ") + name);
		label.setForeground(enabled ? GREEN : FG_DIM);
	}

	private void shake(JComponent c) {
		Point origin = c.getLocation();
		int[] offsets = { -6, 6, -4, 4, -2, 2, 0 };
		int[] step = { 0 };
		Timer t = new Timer(30, null);
		t.addActionListener(e -> {
			if (step[0] >= offsets.length) {
				c.setLocation(origin);
				t.stop();
			} else
				c.setLocation(origin.x + offsets[step[0]++], origin.y);
		});
		t.start();
	}

	private GridBagConstraints labelConstraints() {
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.anchor = GridBagConstraints.LINE_END;
		c.insets = new Insets(0, 0, 0, 10);
		c.ipadx = 0;
		return c;
	}

	private GridBagConstraints fieldConstraints() {
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1.0;
		c.anchor = GridBagConstraints.LINE_START;
		return c;
	}

	private JPanel titledPanel(String title) {
		JPanel panel = new JPanel();
		panel.setBackground(BG);
		TitledBorder border = BorderFactory.createTitledBorder(new LineBorder(BORDER, 1), " " + title + " ",
				TitledBorder.LEFT, TitledBorder.TOP, FONT_BOLD, GOLD);
		panel.setBorder(new CompoundBorder(border, new EmptyBorder(8, 10, 10, 10)));
		return panel;
	}

	private JLabel fieldLabel(String text) {
		JLabel lbl = new JLabel(text + "  ");
		lbl.setFont(FONT_LABEL);
		lbl.setForeground(FG_DIM);
		return lbl;
	}

	private JTextField inputField() {
		JTextField field = new JTextField();
		field.setFont(FONT_MONO);
		field.setForeground(FG);
		field.setBackground(BG_FIELD);
		field.setCaretColor(GOLD);
		field.setBorder(new CompoundBorder(new LineBorder(BORDER, 1), new EmptyBorder(4, 8, 4, 8)));
		field.setPreferredSize(new Dimension(0, 28));

		field.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2 && field.getText().isEmpty()) {
					try {
						String clip = (String) Toolkit.getDefaultToolkit().getSystemClipboard()
								.getData(DataFlavor.stringFlavor);
						if (clip != null && !clip.isBlank())
							field.setText(clip.trim());
					} catch (Exception ignored) {
					}
				}
			}
		});
		return field;
	}

	private JLabel summaryLabel(String text) {
		JLabel lbl = new JLabel(text);
		lbl.setFont(FONT_SMALL);
		lbl.setForeground(FG_DIM);
		return lbl;
	}

	private JButton plainButton(String text) {
		JButton btn = new JButton(text);
		btn.setFont(FONT_LABEL);
		btn.setBackground(BTN_PLAIN);
		btn.setForeground(FG);
		btn.setFocusPainted(false);
		btn.setBorderPainted(false);
		btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		btn.setBorder(new EmptyBorder(4, 10, 4, 10));
		return btn;
	}

	private void styleCombo(JComboBox<String> combo) {
		combo.setFont(FONT_LABEL);
		combo.setBackground(BG_FIELD);
		combo.setForeground(FG);
		combo.setBorder(new LineBorder(BORDER, 1));
		combo.setRenderer((list, value, index, isSelected, cellHasFocus) -> {
			JLabel lbl = new JLabel(value != null ? value : "");
			lbl.setFont(FONT_LABEL);
			lbl.setBorder(new EmptyBorder(3, 8, 3, 8));
			lbl.setOpaque(true);
			lbl.setBackground(isSelected ? new Color(50, 50, 65) : BG_FIELD);
			lbl.setForeground(isSelected ? GOLD : FG);
			return lbl;
		});
	}

	private static String abbrev(String s, int len) {
		if (s == null)
			return "";
		return s.length() <= len ? s : s.substring(0, len) + "…";
	}
}
