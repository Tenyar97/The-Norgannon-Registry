package org.registrycompanion.key;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.logging.Logger;

public class KeyManager {

	private static final Logger log = Logger.getLogger(KeyManager.class.getName());

	private static final String KEYSTORE_FILENAME = "keystore.enc";
	private static final String PUBKEY_FILENAME = "pubkey.hex";

	private static final HexFormat HEX = HexFormat.of();

	private final Path keystoreDir;
	private final KeyEncryption encryption;

	private Ed25519PrivateKeyParameters privateKey;
	private Ed25519PublicKeyParameters publicKey;
	private String publicKeyHex;

	public KeyManager(Path keystoreDir, KeyEncryption encryption) {
		this.keystoreDir = keystoreDir;
		this.encryption = encryption;
	}

	public boolean init() throws Exception {
		Path keystorePath = keystoreDir.resolve(KEYSTORE_FILENAME);

		if (Files.exists(keystorePath)) {
			loadFromDisk(keystorePath);
			log.info("Keypair loaded - pubkey=" + publicKeyHex.substring(0, 8) + "...");
			return true;
		}

		log.info("No keystore found - first run, setup required");
		return false;
	}

	public void generate() throws Exception {
		keystoreDir.toFile().mkdirs();

		SecureRandom random = new SecureRandom();
		byte[] privKeyBytes = new byte[32];
		random.nextBytes(privKeyBytes);

		setKeypair(privKeyBytes);
		saveToDisk(privKeyBytes);

		log.info("New keypair generated - pubkey=" + publicKeyHex.substring(0, 8) + "...");
	}

	public String sign(String nonce) {
		byte[] message = nonce.getBytes(StandardCharsets.UTF_8);

		Ed25519Signer signer = new Ed25519Signer();
		signer.init(true, privateKey);
		signer.update(message, 0, message.length);
		byte[] signature = signer.generateSignature();

		return HEX.formatHex(signature);
	}

	public String signAuthToken(String serverId) {
		String token = serverId + ":" + publicKeyHex;
		return sign(token);
	}

	// The user should write this down. It is the **ONLY** portable backup that survives Windows reinstallation.
	public String getRecoveryPhrase() {
		byte[] privKeyBytes = privateKey.getEncoded();
		return Bip39.encode(privKeyBytes);
	}

	public void restoreFromPhrase(String phrase) throws Exception {
		byte[] privKeyBytes = Bip39.decode(phrase);
		setKeypair(privKeyBytes);
		saveToDisk(privKeyBytes);
		log.info("Keypair restored from recovery phrase - pubkey=" + publicKeyHex.substring(0, 8) + "...");
	}

	public String getPublicKeyHex() {
		return publicKeyHex;
	}

	public boolean isReady() {
		return privateKey != null;
	}

	private void loadFromDisk(Path keystorePath) throws Exception {
		String encryptedBlob = Files.readString(keystorePath).strip();
		byte[] privKeyBytes = encryption.decrypt(encryptedBlob);
		setKeypair(privKeyBytes);
	}

	private void saveToDisk(byte[] privKeyBytes) throws Exception {
		Path keystorePath = keystoreDir.resolve(KEYSTORE_FILENAME);
		Path pubkeyPath = keystoreDir.resolve(PUBKEY_FILENAME);

		String encryptedBlob = encryption.encrypt(privKeyBytes);
		Files.writeString(keystorePath, encryptedBlob);

		Files.writeString(pubkeyPath, publicKeyHex);

		log.info("Keypair saved to: " + keystorePath);
	}

	private void setKeypair(byte[] privKeyBytes) {
		this.privateKey = new Ed25519PrivateKeyParameters(privKeyBytes, 0);
		this.publicKey = privateKey.generatePublicKey();
		this.publicKeyHex = HEX.formatHex(publicKey.getEncoded());
	}
}
