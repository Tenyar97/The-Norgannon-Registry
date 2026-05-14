package org.registryagent.snapshot;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

public class ServerSigner {

    private static final HexFormat HEX = HexFormat.of();

    private final Ed25519PrivateKeyParameters privateKey;
    private final String publicKeyHex;

    public ServerSigner(Path privateKeyPath, Path publicKeyPath) throws IOException {
        String privateKeyHex = Files.readString(privateKeyPath).strip();
        byte[] privateKeyBytes = HEX.parseHex(privateKeyHex);
        this.privateKey = new Ed25519PrivateKeyParameters(privateKeyBytes, 0);

        this.publicKeyHex = Files.readString(publicKeyPath).strip();
    }

    public String sign(String canonicalJson) {
        byte[] message = canonicalJson.getBytes(StandardCharsets.UTF_8);

        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, privateKey);
        signer.update(message, 0, message.length);
        byte[] signature = signer.generateSignature();

        return HEX.formatHex(signature);
    }

    public String getPublicKeyHex() {
        return publicKeyHex;
    }

    public static void generateKeypair(Path privateKeyPath, Path publicKeyPath) throws IOException {
        java.security.SecureRandom random = new java.security.SecureRandom();

        byte[] privateKeyBytes = new byte[32];
        random.nextBytes(privateKeyBytes);

        Ed25519PrivateKeyParameters privateKeyParams =
                new Ed25519PrivateKeyParameters(privateKeyBytes, 0);

        byte[] publicKeyBytes =
                privateKeyParams.generatePublicKey().getEncoded();

        Files.writeString(privateKeyPath, HEX.formatHex(privateKeyBytes));
        Files.writeString(publicKeyPath,  HEX.formatHex(publicKeyBytes));
    }
}
