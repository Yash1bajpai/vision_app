package com.vision.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Separate preference key and file. No plaintext fallback, logging, or cloud-key access. */
public final class PreferenceMemoryStore implements SavedPreferenceMemory.Storage {
    static final String FILE = "vision_saved_preferences_v1";
    static final String ALIAS = "vision.preference.memory.v1";
    interface Crypto {
        byte[] encrypt(byte[] plain) throws Exception;
        byte[] decrypt(byte[] packed) throws Exception;
        void deleteKey() throws Exception;
    }
    private final SharedPreferences prefs;
    private final Crypto crypto;
    public PreferenceMemoryStore(Context context) {
        this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE), new AndroidCrypto());
    }
    PreferenceMemoryStore(SharedPreferences prefs, Crypto crypto) { this.prefs = prefs; this.crypto = crypto; }
    @Override public String read() throws Exception {
        String stored = prefs.getString("ciphertext", null);
        if (stored == null) {
            if (!prefs.getAll().isEmpty()) throw new java.security.GeneralSecurityException("Invalid preference store");
            return null;
        }
        if (prefs.getAll().size() != 1 || stored.length() > 2048) throw new java.security.GeneralSecurityException("Invalid preference store");
        byte[] plain = null;
        try {
            plain = crypto.decrypt(Base64.decode(stored, Base64.NO_WRAP));
            if (plain.length > 512) throw new java.security.GeneralSecurityException("Record size");
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(plain)).toString();
        } finally { if (plain != null) java.util.Arrays.fill(plain, (byte) 0); }
    }
    @Override public void write(String record) throws Exception {
        byte[] plain = record.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            if (plain.length > 512) throw new java.security.GeneralSecurityException("Record size");
            String packed = Base64.encodeToString(crypto.encrypt(plain), Base64.NO_WRAP);
            if (!prefs.edit().clear().putString("ciphertext", packed).commit()) throw new java.io.IOException("Save failed");
        } finally { java.util.Arrays.fill(plain, (byte) 0); }
    }
    @Override public void clear() throws Exception {
        // Both are attempted: key invalidation prevents a stale record from decrypting if deletion fails.
        Exception failure = null;
        try { crypto.deleteKey(); } catch (Exception e) { failure = e; }
        try { if (!prefs.edit().clear().commit()) throw new java.io.IOException("Delete failed"); }
        catch (Exception e) { failure = e; }
        if (failure != null) throw failure;
    }
    private static final class AndroidCrypto implements Crypto {
        private KeyStore store() throws Exception {
            KeyStore s = KeyStore.getInstance("AndroidKeyStore"); s.load(null); return s;
        }
        private SecretKey key(boolean create) throws Exception {
            KeyStore s = store();
            if (s.containsAlias(ALIAS)) return (SecretKey) s.getKey(ALIAS, null);
            if (!create) throw new java.security.GeneralSecurityException("Missing preference key");
            KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).setKeySize(256).build());
            return g.generateKey();
        }
        @Override public byte[] encrypt(byte[] plain) throws Exception {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key(true));
            c.updateAAD(ALIAS.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] iv = c.getIV(), body = c.doFinal(plain);
            if (iv.length != 12) throw new java.security.GeneralSecurityException("Invalid IV");
            byte[] packed = new byte[12 + body.length];
            System.arraycopy(iv, 0, packed, 0, 12); System.arraycopy(body, 0, packed, 12, body.length); return packed;
        }
        @Override public byte[] decrypt(byte[] packed) throws Exception {
            if (packed.length < 28 || packed.length > 1024) throw new java.security.GeneralSecurityException("Invalid size");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, packed, 0, 12));
            c.updateAAD(ALIAS.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return c.doFinal(packed, 12, packed.length - 12);
        }
        @Override public void deleteKey() throws Exception { store().deleteEntry(ALIAS); }
    }
}
