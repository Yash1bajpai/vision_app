package com.vision.app;

import android.content.Context;
import android.content.SharedPreferences;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class PreferenceMemoryStoreTest {
    static class TestCrypto implements PreferenceMemoryStore.Crypto {
        SecretKey key; int deletes;
        TestCrypto() throws Exception { KeyGenerator g=KeyGenerator.getInstance("AES");g.init(256);key=g.generateKey(); }
        public byte[] encrypt(byte[] p)throws Exception{if(key==null)throw new Exception();Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key);byte[] b=c.doFinal(p),r=new byte[12+b.length];System.arraycopy(c.getIV(),0,r,0,12);System.arraycopy(b,0,r,12,b.length);return r;}
        public byte[] decrypt(byte[] p)throws Exception{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,p,0,12));return c.doFinal(p,12,p.length-12);}
        public void deleteKey(){key=null;deletes++;}
    }
    SharedPreferences prefs(){SharedPreferences p=RuntimeEnvironment.getApplication().getSharedPreferences("test-preference",Context.MODE_PRIVATE);p.edit().clear().commit();return p;}
    @Test public void actualAesGcmRoundTripDifferentCiphertextAndClear()throws Exception{
        SharedPreferences p=prefs();TestCrypto c=new TestCrypto();PreferenceMemoryStore s=new PreferenceMemoryStore(p,c);
        s.write("{synthetic concise record}");String first=p.getString("ciphertext",null);
        assertFalse(p.getAll().toString().contains("concise"));assertEquals("{synthetic concise record}",s.read());
        s.write("{synthetic concise record}");assertNotEquals(first,p.getString("ciphertext",null));
        s.clear();assertNull(s.read());assertEquals(1,c.deletes);
    }
    @Test public void tamperKeyLossAndExtraPlaintextAreRejected()throws Exception{
        SharedPreferences p=prefs();TestCrypto c=new TestCrypto();PreferenceMemoryStore s=new PreferenceMemoryStore(p,c);s.write("record");
        p.edit().putString("response_style","concise").commit();try{s.read();fail();}catch(Exception expected){}p.edit().remove("response_style").commit();
        try{new PreferenceMemoryStore(p,new TestCrypto()).read();fail();}catch(Exception expected){}
        p.edit().putString("ciphertext","corrupt").commit();try{s.read();fail();}catch(Exception expected){}
    }
    @Test public void unavailableEncryptionWritesNoPlaintext()throws Exception{
        SharedPreferences p=prefs();PreferenceMemoryStore s=new PreferenceMemoryStore(p,new PreferenceMemoryStore.Crypto(){public byte[] encrypt(byte[] p)throws Exception{throw new Exception();}public byte[] decrypt(byte[] p)throws Exception{throw new Exception();}public void deleteKey(){}});
        try{s.write("concise");fail();}catch(Exception expected){}assertTrue(p.getAll().isEmpty());
    }
    @Test public void clearingPreferencesNeverClearsCloudData()throws Exception{
        SharedPreferences p=prefs(),cloud=RuntimeEnvironment.getApplication().getSharedPreferences("vision_cloud_key",Context.MODE_PRIVATE);
        cloud.edit().putString("ciphertext","synthetic-cloud-ciphertext").commit();
        PreferenceMemoryStore s=new PreferenceMemoryStore(p,new TestCrypto());s.write("record");s.clear();
        assertEquals("synthetic-cloud-ciphertext",cloud.getString("ciphertext",null));cloud.edit().clear().commit();
        assertNotEquals("vision.cloud.config.v2",PreferenceMemoryStore.ALIAS);
    }
    @Test public void fullControllerEncryptedReloadAndDisable()throws Exception{
        SharedPreferences p=prefs();TestCrypto crypto=new TestCrypto();PreferenceMemoryStore store=new PreferenceMemoryStore(p,crypto);
        SavedPreferenceMemory m=new SavedPreferenceMemory(store);long t=SavedPreferenceMemoryTest.NOW;
        m.handle("enable saved preference memory",t);m.handle("save preference response_style concise",t);
        assertFalse(p.getAll().toString().contains("concise"));assertFalse(p.getAll().toString().contains("response_style"));
        assertEquals("concise",new SavedPreferenceMemory(new PreferenceMemoryStore(p,crypto)).style(t));
        m.disable();assertTrue(p.getAll().isEmpty());assertNull(new SavedPreferenceMemory(store).style(t));
    }
}
