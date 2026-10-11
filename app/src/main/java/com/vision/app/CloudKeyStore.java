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

/** Persists only AES-GCM ciphertext. The non-exportable key belongs to Android Keystore. */
public final class CloudKeyStore {
    private static final String ALIAS = "vision.groq.key.v1";
    interface Crypto { byte[] encrypt(byte[] plain) throws Exception; byte[] decrypt(byte[] packed) throws Exception; }
    private final SharedPreferences prefs;
    private final Crypto crypto;
    public CloudKeyStore(Context context) {
        this(context.getSharedPreferences("vision_cloud_key", Context.MODE_PRIVATE), new AndroidCrypto());
    }
    CloudKeyStore(SharedPreferences prefs, Crypto crypto) { this.prefs=prefs;this.crypto=crypto; }
    public boolean save(String key) {
        byte[] plain=key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try { return prefs.edit().putString("ciphertext", Base64.encodeToString(crypto.encrypt(plain),Base64.NO_WRAP)).commit(); }
        catch(Exception e){clear();return false;}
        finally { java.util.Arrays.fill(plain,(byte)0); }
    }
    public String load() {
        String stored=prefs.getString("ciphertext",null);if(stored==null)return null;
        byte[] plain=null;
        try {
            plain=crypto.decrypt(Base64.decode(stored,Base64.NO_WRAP));
            return new String(plain,java.nio.charset.StandardCharsets.UTF_8);
        }catch(Exception e){clear();return null;}
        finally{if(plain!=null)java.util.Arrays.fill(plain,(byte)0);}
    }
    public void clear(){prefs.edit().remove("ciphertext").commit();}
    private static final class AndroidCrypto implements Crypto {
        private SecretKey key(boolean create) throws Exception {
            KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
            if(ks.containsAlias(ALIAS))return (SecretKey)ks.getKey(ALIAS,null);
            if(!create)throw new java.security.GeneralSecurityException("Missing device key");
            KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).setKeySize(256).build());return g.generateKey();
        }
        @Override public byte[] encrypt(byte[] plain) throws Exception {
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key(true));
            byte[] iv=c.getIV(),body=c.doFinal(plain);if(iv.length!=12)throw new java.security.GeneralSecurityException("IV");
            byte[] result=new byte[12+body.length];System.arraycopy(iv,0,result,0,12);System.arraycopy(body,0,result,12,body.length);return result;
        }
        @Override public byte[] decrypt(byte[] packed) throws Exception {
            if(packed.length<28||packed.length>1024)throw new java.security.GeneralSecurityException("Size");
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(false),new GCMParameterSpec(128,packed,0,12));
            return c.doFinal(packed,12,packed.length-12);
        }
    }
}
