package com.remotedesktop.client;

import android.util.Log;

import java.security.MessageDigest;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM transport decryption for server->client video/FEC packets.
 * The key is SHA-256(PSK) and MUST match server/src/crypto.cpp.
 */
public final class Crypto {
    private static final String TAG = "Crypto";

    private static final String PSK = "remote-desktop-psk-2026";
    private static final String TRANSFORM = "AES/GCM/NoPadding";

    private static volatile byte[] sKey;
    private static final Object KEY_LOCK = new Object();

    private static byte[] key() {
        byte[] k = sKey;
        if (k != null) return k;
        synchronized (KEY_LOCK) {
            if (sKey == null) {
                try {
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    sKey = md.digest(PSK.getBytes("UTF-8"));
                } catch (Exception e) {
                    UiLog.e(TAG, "key derivation failed", e);
                }
            }
            return sKey;
        }
    }

    /**
     * Decrypt a packet whose payload is [nonce|ciphertext|tag], returning a
     * fresh buffer with the same clear header but the plaintext payload.
     * Returns null on auth failure or malformed input (caller drops packet).
     */
    public static byte[] decryptPacket(byte[] data, int len, int headerSize) {
        int payLen = len - headerSize;
        if (payLen < Proto.NONCE_SIZE + Proto.TAG_SIZE) return null;
        try {
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"),
                    new GCMParameterSpec(Proto.TAG_SIZE * 8,
                            data, headerSize, Proto.NONCE_SIZE));
            byte[] plain = c.doFinal(data,
                    headerSize + Proto.NONCE_SIZE,
                    payLen - Proto.NONCE_SIZE - Proto.TAG_SIZE);
            byte[] out = new byte[headerSize + plain.length];
            System.arraycopy(data, 0, out, 0, headerSize);
            System.arraycopy(plain, 0, out, headerSize, plain.length);
            return out;
        } catch (Exception e) {
            UiLog.e(TAG, "decrypt failed", e);
            return null;
        }
    }

    private Crypto() { }
}