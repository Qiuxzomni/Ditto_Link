package com.vibedevdrop.dittolink;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts stored PC passwords with an AES-256-GCM key held by the Android Keystore.
 *
 * Format on disk: {@code v1:<base64 iv>:<base64 ciphertext+tag>}. Only a structurally valid sealed
 * value is treated as ciphertext; this lets legacy passwords that merely start with {@code v1:}
 * migrate normally.
 *
 * The key never leaves the Keystore (hardware-backed where the device provides it) and is not
 * exportable, so a copied prefs file or a cloud backup of it is not enough to recover a password.
 * If the key is lost (factory reset, restored backup, Keystore reset) {@link #open} returns an empty
 * string: the profile stays visible and the user re-enters the password once.
 */
final class PasswordVault {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "dittolink.pcpassword.v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    /** Returns null when encryption is unavailable — callers must not overwrite stored secrets then. */
    static synchronized String seal(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            // The Keystore generates the IV (the key forbids caller-supplied nonces) and hands it back.
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != IV_BYTES) return null;
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder encoder = Base64.getEncoder();
            return PREFIX + encoder.encodeToString(iv) + ":" + encoder.encodeToString(sealed);
        } catch (Exception unavailable) {
            return null;
        }
    }

    /** True when the stored value is already sealed by this vault. */
    static boolean isSealed(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) return false;
        int split = stored.indexOf(':', PREFIX.length());
        if (split < 0 || stored.indexOf(':', split + 1) >= 0) return false;
        try {
            Base64.Decoder decoder = Base64.getDecoder();
            byte[] iv = decoder.decode(stored.substring(PREFIX.length(), split));
            byte[] ciphertext = decoder.decode(stored.substring(split + 1));
            return iv.length == IV_BYTES && ciphertext.length >= TAG_BITS / 8;
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    static synchronized String open(String stored) {
        if (stored == null || stored.isEmpty()) return "";
        if (!isSealed(stored)) return stored; // legacy plaintext value
        int split = stored.indexOf(':', PREFIX.length());
        try {
            Base64.Decoder decoder = Base64.getDecoder();
            byte[] iv = decoder.decode(stored.substring(PREFIX.length(), split));
            byte[] sealed = decoder.decode(stored.substring(split + 1));
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
        } catch (Exception unreadable) {
            return "";
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            java.security.Key key = store.getKey(KEY_ALIAS, null);
            if (key instanceof SecretKey) return (SecretKey) key;
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private PasswordVault() { }
}
