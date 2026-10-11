package com.vision.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class CloudKeyStoreTest {
    static class TestCrypto implements CloudKeyStore.Crypto {
        final SecretKey key;
        TestCrypto()throws Exception{KeyGenerator g=KeyGenerator.getInstance("AES");g.init(256);key=g.generateKey();}
        public byte[] encrypt(byte[] p)throws Exception{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key);byte[] body=c.doFinal(p),r=new byte[12+body.length];System.arraycopy(c.getIV(),0,r,0,12);System.arraycopy(body,0,r,12,body.length);return r;}
        public byte[] decrypt(byte[] p)throws Exception{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,p,0,12));return c.doFinal(p,12,p.length-12);}
    }
    SharedPreferences prefs(){SharedPreferences p=RuntimeEnvironment.getApplication().getSharedPreferences("testkey",Context.MODE_PRIVATE);p.edit().clear().commit();return p;}
    @Test public void encryptedRoundTripAndForget()throws Exception{SharedPreferences p=prefs();CloudKeyStore s=new CloudKeyStore(p,new TestCrypto());assertTrue(s.save("synthetic_key"));assertFalse(p.getAll().toString().contains("synthetic_key"));assertEquals("synthetic_key",s.load());s.clear();assertNull(s.load());}
    @Test public void tamperedCiphertextFailsClosedAndIsRemoved()throws Exception{SharedPreferences p=prefs();CloudKeyStore s=new CloudKeyStore(p,new TestCrypto());s.save("synthetic_key");p.edit().putString("ciphertext","corrupt").commit();assertNull(s.load());assertFalse(p.contains("ciphertext"));}
    @Test public void unavailableKeystoreNeverWritesPlaintext()throws Exception{SharedPreferences p=prefs();CloudKeyStore s=new CloudKeyStore(p,new CloudKeyStore.Crypto(){public byte[] encrypt(byte[] p)throws Exception{throw new Exception();}public byte[] decrypt(byte[] p)throws Exception{throw new Exception();}});assertFalse(s.save("synthetic_key"));assertTrue(p.getAll().isEmpty());}
    @Test public void changingCryptoKeyCannotRestoreOldCiphertext()throws Exception{SharedPreferences p=prefs();new CloudKeyStore(p,new TestCrypto()).save("synthetic_key");assertNull(new CloudKeyStore(p,new TestCrypto()).load());assertTrue(p.getAll().isEmpty());}
}
