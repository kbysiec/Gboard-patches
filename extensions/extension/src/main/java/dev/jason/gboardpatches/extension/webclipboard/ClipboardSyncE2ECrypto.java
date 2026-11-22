package dev.jason.gboardpatches.extension.webclipboard;

import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * End-to-End AES-256-GCM encryption for clipboard payloads between Android and macOS.
 */
public final class ClipboardSyncE2ECrypto {
    private static final String SALT = "gboard-sync-e2e-v1:";
    private static final String PREFIX = "e2e:";
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private ClipboardSyncE2ECrypto() {}

    private static SecretKeySpec deriveKey(String pairingCode) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] input = (SALT + (pairingCode != null ? pairingCode : "")).getBytes(StandardCharsets.UTF_8);
            byte[] keyBytes = md.digest(input);
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Throwable t) {
            throw new RuntimeException("SHA-256 derivation failed", t);
        }
    }

    /**
     * Encrypts plaintext using AES-256-GCM authenticated encryption.
     * Returns "e2e:<base64(iv + ciphertext + tag)>" or original text if encryption is bypassed/fails.
     */
    public static String encrypt(String text, String pairingCode) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        if (pairingCode == null || pairingCode.isEmpty() || "0000".equals(pairingCode)) {
            return text;
        }
        try {
            SecretKeySpec keySpec = deriveKey(pairingCode);
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec);

            byte[] plaintextBytes = text.getBytes(StandardCharsets.UTF_8);
            byte[] ciphertextWithTag = cipher.doFinal(plaintextBytes);

            byte[] combined = new byte[iv.length + ciphertextWithTag.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertextWithTag, 0, combined, iv.length, ciphertextWithTag.length);

            return PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP);
        } catch (Throwable t) {
            return text;
        }
    }

    /**
     * Decrypts text if it starts with "e2e:". Otherwise returns text unchanged.
     */
    public static String decrypt(String text, String pairingCode) {
        if (text == null || !text.startsWith(PREFIX)) {
            return text;
        }
        if (pairingCode == null || pairingCode.isEmpty()) {
            return text;
        }
        try {
            String base64Payload = text.substring(PREFIX.length());
            byte[] combined = Base64.decode(base64Payload, Base64.NO_WRAP);
            if (combined.length < GCM_IV_LENGTH_BYTES + 16) {
                return text;
            }

            SecretKeySpec keySpec = deriveKey(pairingCode);
            byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH_BYTES);
            byte[] ciphertextWithTag = Arrays.copyOfRange(combined, GCM_IV_LENGTH_BYTES, combined.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, spec);

            byte[] decrypted = cipher.doFinal(ciphertextWithTag);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return text;
        }
    }
}
