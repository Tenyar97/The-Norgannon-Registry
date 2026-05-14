package org.registryagent.auth;

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public class KeyVerifier {

	private static final HexFormat HEX = HexFormat.of();

	public boolean verify(String publicKeyHex, byte[] message, String signatureHex) {
		try {
			byte[] pubKeyBytes = HEX.parseHex(publicKeyHex);
			byte[] sigBytes = HEX.parseHex(signatureHex);

			Ed25519PublicKeyParameters pubKey = new Ed25519PublicKeyParameters(pubKeyBytes, 0);

			Ed25519Signer signer = new Ed25519Signer();
			signer.init(false, pubKey);
			signer.update(message, 0, message.length);

			return signer.verifySignature(sigBytes);

		} catch (Exception e) {
			return false;
		}
	}

	public boolean verify(String publicKeyHex, String message, String signatureHex) {
		return verify(publicKeyHex, message.getBytes(StandardCharsets.UTF_8), signatureHex);
	}

	public boolean isValidPublicKey(String publicKeyHex) {
		if (publicKeyHex == null)
			return false;
		if (publicKeyHex.length() != 64)
			return false;
		try {
			HEX.parseHex(publicKeyHex);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}
}
