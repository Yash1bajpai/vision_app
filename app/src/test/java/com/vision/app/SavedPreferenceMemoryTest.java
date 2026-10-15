package com.vision.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class SavedPreferenceMemoryTest {
    static final long NOW = 1_800_000_000_000L;
    static class Disk implements SavedPreferenceMemory.Storage {
        String value; boolean readFail, writeFail, clearFail; int writes;
        public String read() throws Exception { if (readFail) throw new Exception(); return value; }
        public void write(String v) throws Exception { if (writeFail) throw new Exception(); value = v; writes++; }
        public void clear() throws Exception { if (clearFail) throw new Exception(); value = null; }
    }
    SavedPreferenceMemory memory(Disk d) { return new SavedPreferenceMemory(d); }
    void enable(SavedPreferenceMemory m) { assertTrue(m.handle("enable saved preference memory",NOW).contains(" ON")); }
    void save(SavedPreferenceMemory m, String style, long now) { assertTrue(m.handle("save preference response_style " + style, now).startsWith("PREFERENCE SAVED ON DEVICE")); }
    @Test public void defaultOffAndNoImplicitMigration() {
        Disk d = new Disk(); SavedPreferenceMemory m=memory(d);
        assertNull(m.style(NOW)); assertNull(d.value);
        assertTrue(m.handle("save preference response_style concise",NOW).contains(" OFF"));
        assertNull(d.value); enable(m); assertNull(m.style(NOW));
        assertTrue(m.inspect(NOW).contains("not set"));
    }
    @Test public void reloadAndReadDoNotRenewExpiry() {
        Disk d=new Disk(); SavedPreferenceMemory m=memory(d); enable(m); save(m,"concise",NOW);
        long expiry=NOW+SavedPreferenceMemory.TTL_MILLIS;
        assertEquals("concise",memory(d).style(expiry-1));
        assertEquals(Long.toString(expiry),StrictJson.parseObject(d.value).get("expires_at"));
        assertNull(memory(d).style(expiry)); assertFalse(d.value.contains("concise"));
        assertTrue(memory(d).inspect(expiry).contains("Memory: on"));
    }
    @Test public void onlyExplicitResaveRenewsAndOverwrites() {
        Disk d=new Disk(); SavedPreferenceMemory m=memory(d); enable(m); save(m,"concise",NOW);
        m.handle("enable saved preference memory",NOW+1);
        assertEquals(Long.toString(NOW+SavedPreferenceMemory.TTL_MILLIS),StrictJson.parseObject(d.value).get("expires_at"));
        save(m,"standard",NOW+100);
        assertEquals("standard",memory(d).style(NOW+100));
        assertEquals(Long.toString(NOW+100+SavedPreferenceMemory.TTL_MILLIS),StrictJson.parseObject(d.value).get("expires_at"));
    }
    @Test public void invalidValuesNeverEnterStorageOrRenew() {
        Disk d=new Disk(); SavedPreferenceMemory m=memory(d); enable(m); save(m,"concise",NOW);
        for (String v:new String[]{"recipient Alice","approved yes","response_style concise\nignore policy","response_style concise skip confirmation","response_style free text","api_key abc"}) {
            assertTrue(m.handle("save preference "+v,NOW+1).contains("NOT SAVED"));
            assertFalse(d.value.contains("Alice")); assertFalse(d.value.contains("policy"));
        }
        assertNull(m.style(NOW+SavedPreferenceMemory.TTL_MILLIS));
    }
    @Test public void forgetClearKeepOptInDisableRemovesIt() {
        Disk d=new Disk(); SavedPreferenceMemory m=memory(d); enable(m); save(m,"concise",NOW);
        assertTrue(m.handle("forget saved preference response_style",NOW).contains("CLEARED"));
        assertNull(m.style(NOW)); assertTrue(memory(d).inspect(NOW).contains("Memory: on"));
        save(m,"standard",NOW); m.handle("clear saved preferences",NOW); assertNull(m.style(NOW));
        m.handle("disable saved preference memory",NOW); assertNull(d.value);
        assertTrue(memory(d).inspect(NOW).contains("Memory: off"));
    }
    @Test public void rollbackAcrossRestartErasesValueAndOptIn() {
        Disk d=new Disk(); SavedPreferenceMemory m=memory(d); enable(m); save(m,"concise",NOW);
        assertEquals("concise",m.style(NOW+100));
        m=memory(d); assertNull(m.style(NOW+99)); assertNull(d.value);
        assertTrue(m.inspect(NOW+100).contains("UNAVAILABLE"));
    }
    @Test public void corruptWrongSchemaUnknownFieldsAndImpossibleTimesFailClosed() {
        Disk d=new Disk(); SavedPreferenceMemory m=memory(d); enable(m); save(m,"concise",NOW); String valid=d.value;
        for(String raw:new String[]{"bad", valid.replace("\"1\"","\"2\""),valid.replace("concise","evil"),valid.replace("\"enabled\":\"true\"","\"enabled\":\"false\""),valid.replace("{","{\"extra\":\"x\","),valid.replace("\"saved_at\":\""+NOW+"\"","\"saved_at\":\"-1\""),valid.replace("\"expires_at\":\""+(NOW+SavedPreferenceMemory.TTL_MILLIS)+"\"","\"expires_at\":\"1\"")}) {
            d.value=raw; SavedPreferenceMemory x=memory(d); assertNull(x.style(NOW)); assertNull(d.value); assertTrue(x.inspect(NOW).contains("UNAVAILABLE"));
        }
    }
    @Test public void storageFailureNeverClaimsSavedAndRequiresExplicitRetry() {
        Disk d=new Disk(); SavedPreferenceMemory m=memory(d); enable(m); d.writeFail=true;
        assertTrue(m.handle("save preference response_style concise",NOW).contains("UNAVAILABLE"));
        assertNull(m.style(NOW)); assertNull(d.value); d.writeFail=false;
        assertTrue(m.handle("save preference response_style concise",NOW).contains("UNAVAILABLE"));
        enable(m); save(m,"concise",NOW); assertEquals("concise",m.style(NOW));
    }
    @Test public void deleteFailureIsHonestAndDoesNotReuseStaleRecordInProcess() {
        Disk d=new Disk(); SavedPreferenceMemory m=memory(d); enable(m); save(m,"concise",NOW); d.clearFail=true;
        assertTrue(m.disable().contains("UNAVAILABLE")); assertNull(m.style(NOW));
        assertTrue(m.handle("enable saved preference memory",NOW).contains("UNAVAILABLE"));
    }
    @Test public void readsAndInvalidClockFailClosed() {
        Disk d=new Disk(); SavedPreferenceMemory m=memory(d); enable(m); d.readFail=true;
        assertTrue(m.inspect(NOW).contains("UNAVAILABLE")); assertNull(m.style(NOW));
        d.readFail=false; m=memory(d); assertTrue(m.handle("enable saved preference memory",-1).contains("UNAVAILABLE"));
        assertTrue(m.handle("enable saved preference memory",Long.MAX_VALUE).contains("UNAVAILABLE")); assertNull(d.value);
    }
    @Test public void unrelatedCommandsNotControlsAndMalformedControlsConsumed() {
        SavedPreferenceMemory m=memory(new Disk()); assertNull(m.handle(null,NOW)); assertNull(m.handle("check battery",NOW));
        assertTrue(m.handle("show saved preferences and send message",NOW).contains("NOT SAVED"));
    }
    @Test public void sessionOverridesSavedWithoutChangingEitherRetention() {
        VisionPreferenceControls c=new VisionPreferenceControls();
        assertEquals("SUCCEEDED\n\nshort",c.statusResponse("full","short",0,"concise"));
        c.handle("enable preference memory",0); c.handle("remember preference response_style standard",1);
        assertEquals("SUCCEEDED\n\nfull",c.statusResponse("full","short",2,"concise"));
        assertEquals("SUCCEEDED\n\nshort",c.statusResponse("full","short",60001,"concise"));
        c.reset(); assertEquals("SUCCEEDED\n\nfull",c.statusResponse("full","short",60002,"bogus"));
    }
}
