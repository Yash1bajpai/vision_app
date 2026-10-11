package com.vision.app;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class CloudKeyStoreInstrumentedTest {
    @Test public void actualAndroidKeystoreRoundTripAndClear() {
        CloudKeyStore s=new CloudKeyStore(InstrumentationRegistry.getInstrumentation().getTargetContext());s.clear();
        try{assertTrue(s.save("synthetic_session_key_123456789"));assertEquals("synthetic_session_key_123456789",s.load());}
        finally{s.clear();}assertNull(s.load());
    }
}
