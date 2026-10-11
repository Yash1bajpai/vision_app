package com.vision.app;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.Arrays;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 28)
public class OpenAiCompatibleProviderTest {
    private static final String KEY = "synthetic_session_key_123456789";
    private static final String TIME = "{\"type\":\"READ_TIME\",\"target\":\"time\",\"text\":\"\",\"channel\":\"\"}";
    static String response(String raw) {
        try { return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":" + JSONObject.quote(raw) + "}}]}"; }
        catch (Exception e) { throw new AssertionError(e); }
    }
    @Test public void exactRequestOnlyNoHistoryOrKeyInPayload() throws Exception {
        OpenAiCompatibleProvider p = new OpenAiCompatibleProvider(KEY, (key, payload) -> {
            assertEquals(KEY, key);
            assertFalse(payload.contains(KEY)); assertFalse(payload.contains("private history"));
            try {
                JSONObject j = new JSONObject(payload);
                assertEquals(OpenAiCompatibleProvider.MODEL, j.getString("model"));
                assertEquals(2, j.getJSONArray("messages").length());
                assertEquals("what hour is it", j.getJSONArray("messages").getJSONObject(1).getString("content"));
            } catch (Exception e) { throw new AssertionError(e); }
            return response(TIME);
        });
        assertEquals(TIME, p.propose("what hour is it", Arrays.asList("private history")));
    }
    @Test public void clearAndOversizedRequestNeverCallTransport() {
        int[] calls = {0};
        OpenAiCompatibleProvider p = new OpenAiCompatibleProvider(KEY, (k,b) -> { calls[0]++; return response(TIME); });
        assertNull(p.propose(new String(new char[501]).replace('\0', 'x')));
        assertNull(p.propose(" ")); p.clear(); assertFalse(p.configured());
        assertNull(p.propose("what hour is it")); assertEquals(0, calls[0]);
    }
    @Test public void malformedTruncatedOrDangerousOutputsFailClosed() {
        String[] bad = {"not json", "{}", "{\"choices\":[]}",
            response(TIME).replace("stop", "length"), response(TIME).replace("assistant", "user"),
            response("[" + TIME + "]"), response(TIME.replace("\"channel\":\"\"", "\"channel\":\"\",\"approved\":\"true\"")),
            response(TIME.replace("READ_TIME", "READ_CALENDAR").replace("time", "calendar")),
            response(TIME.replace("READ_TIME", "READ_NOTIFICATION")),
            response(TIME.replace("READ_TIME", "OPEN_DIALER").replace("time", "+12345678901")),
            response(TIME).replace("\"role\":", "\"tool_calls\":[],\"role\":")};
        for (String b : bad) assertNull(new OpenAiCompatibleProvider(KEY, (k,p) -> b).propose("test"));
    }
    @Test public void absentResponseFailsWithoutRetry() {
        int[] n = {0};
        assertNull(new OpenAiCompatibleProvider(KEY, (k,p) -> { n[0]++; return null; }).propose("test"));
        assertEquals(1, n[0]);
    }
    @Test public void badKeyRejected() {
        try { new OpenAiCompatibleProvider("bad\r\nheader"); fail(); } catch (IllegalArgumentException expected) { }
    }
}
