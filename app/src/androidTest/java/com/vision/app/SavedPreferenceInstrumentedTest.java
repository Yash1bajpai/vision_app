package com.vision.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.security.KeyStore;
import javax.crypto.KeyGenerator;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Actual device Keystore, not the synthetic crypto used in JVM tests. */
@RunWith(AndroidJUnit4.class)
public class SavedPreferenceInstrumentedTest {
    @Test public void actualKeystoreRoundTripExpiryAndCloudKeyIsolation()throws Exception{
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();PreferenceMemoryStore store=new PreferenceMemoryStore(context);store.clear();
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        String sentinel="vision.preference.test.cloud-sentinel";
        KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(sentinel,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());g.generateKey();
        long now=System.currentTimeMillis();
        try{
            SavedPreferenceMemory m=new SavedPreferenceMemory(store);
            assertTrue(m.handle("enable saved preference memory",now).contains(" ON"));assertTrue(m.handle("save preference response_style concise",now).startsWith("PREFERENCE SAVED ON DEVICE"));
            assertFalse(context.getSharedPreferences(PreferenceMemoryStore.FILE,0).getAll().toString().contains("concise"));
            assertEquals("concise",new SavedPreferenceMemory(new PreferenceMemoryStore(context)).style(now));
            assertNull(m.style(now+SavedPreferenceMemory.TTL_MILLIS));m.disable();
            assertTrue(context.getSharedPreferences(PreferenceMemoryStore.FILE,0).getAll().isEmpty());
            ks.load(null);assertFalse(ks.containsAlias(PreferenceMemoryStore.ALIAS));assertTrue(ks.containsAlias(sentinel));
        }finally{store.clear();ks.deleteEntry(sentinel);}
    }
    @Test public void inputSaveRecreateInspectDisable()throws Exception{
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();PreferenceMemoryStore store=new PreferenceMemoryStore(context);store.clear();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{VisionUi.typeAndSend(a,"enable saved preference memory");VisionUi.activeDialog(a).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();});
            VisionUi.settle();
            scenario.onActivity(a->{VisionUi.typeAndSend(a,"save preference response_style concise");assertTrue(VisionUi.activityText(a).getText().toString().startsWith("PREFERENCE SAVED ON DEVICE"));});
            scenario.recreate();
            scenario.onActivity(a->{VisionUi.typeAndSend(a,"show preferences");String text=VisionUi.activityText(a).getText().toString();assertTrue(text.contains("SAVED DEVICE PREFERENCES"));assertTrue(text.contains("response_style: concise"));VisionUi.typeAndSend(a,"disable saved preference memory");VisionUi.typeAndSend(a,"show saved preferences");assertTrue(VisionUi.activityText(a).getText().toString().contains("Memory: off"));});
        }finally{store.clear();}
    }
}
