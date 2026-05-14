package org.registrycompanion.key;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;


public class KeyEncryption {

    private static final String ALGORITHM    = "AES/GCM/NoPadding";
    private static final int    IV_LENGTH    = 12;  
    private static final int    TAG_LENGTH   = 128;

    private final SecretKey encryptionKey;
    private final SecureRandom random = new SecureRandom();

    public KeyEncryption(SecretKey encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    public static KeyEncryption fromMachineId() throws Exception {
        String machineGuid = readMachineGuid();
        byte[] keyBytes    = sha256(machineGuid.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        SecretKey key      = new SecretKeySpec(keyBytes, "AES");
        return new KeyEncryption(key);
    }

    public String encrypt(byte[] plaintext) throws Exception {
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey,
                new GCMParameterSpec(TAG_LENGTH, iv));

        byte[] ciphertext = cipher.doFinal(plaintext);

        byte[] combined = new byte[IV_LENGTH + ciphertext.length];
        System.arraycopy(iv,         0, combined, 0,         IV_LENGTH);
        System.arraycopy(ciphertext, 0, combined, IV_LENGTH, ciphertext.length);

        return Base64.getEncoder().encodeToString(combined);
    }

    public byte[] decrypt(String base64Blob) throws Exception {
        byte[] combined = Base64.getDecoder().decode(base64Blob);

        byte[] iv         = Arrays.copyOfRange(combined, 0, IV_LENGTH);
        byte[] ciphertext = Arrays.copyOfRange(combined, IV_LENGTH, combined.length);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey,
                new GCMParameterSpec(TAG_LENGTH, iv));

        return cipher.doFinal(ciphertext);
    }

    private static String readMachineGuid() {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{
                "reg", "query",
                "HKLM\\SOFTWARE\\Microsoft\\Cryptography",
                "/v", "MachineGuid"
            });

            String output = new String(process.getInputStream().readAllBytes());
            process.waitFor();

            for (String line : output.split("\n")) {
                if (line.contains("MachineGuid")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 3) {
                        return parts[parts.length - 1].trim();
                    }
                }
            }
        } catch (Exception e) {
        }
        //If we reached here..we've really fckd up
        return "dev-fallback-machine-id-not-secure";
    }


    private static byte[] sha256(byte[] input) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(input);
    }
}
