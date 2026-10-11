package com.vision.app;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.json.JSONObject;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class CloudProviderConfigTest {
    @Test public void allPresetDestinationsAndModelAreBound()throws Exception {
        String[] hosts={"api.groq.com","openrouter.ai","api.openai.com","generativelanguage.googleapis.com"};
        for(int i=0;i<4;i++){
            CloudProviderConfig c=new CloudProviderConfig(CloudProviderConfig.NAMES[i],null,"test/model");
            assertEquals(hosts[i],new java.net.URI(c.endpoint).getHost());
            JSONObject saved=new JSONObject(c.serialize("synthetic_key"));
            CloudProviderConfig restored=CloudProviderConfig.fromJson(saved);assertEquals(c.endpoint,restored.endpoint);assertEquals(c.model,restored.model);
            assertEquals("synthetic_key",saved.getString("key"));
        }
    }
    @Test public void customAcceptsOnlyHttpsCompletionUrl() {
        assertEquals("https://example.com/v1/chat/completions",new CloudProviderConfig("Custom","https://example.com/v1/chat/completions","test").endpoint);
        String[] bad={"http://example.com/v1/chat/completions","https://localhost/v1/chat/completions","https://127.0.0.1/v1/chat/completions","https://example.com:6333/v1/chat/completions","https://key@example.com/v1/chat/completions","https://example.com/v1/chat/completions?key=secret","https://example.com/v1/chat/completions#fragment","https://example.com/v1","file:///chat/completions"};
        for(String e:bad){try{new CloudProviderConfig("Custom",e,"test");fail(e);}catch(IllegalArgumentException expected){}}
    }
    @Test public void modelRequiredAndHeaderInjectionRejected() {
        for(String m:new String[]{"","test\r\nheader","bad model"}){try{new CloudProviderConfig("OpenAI",null,m);fail();}catch(IllegalArgumentException expected){}}
    }
    @Test public void providerSpecificOptionsNotLeakedToOthers()throws Exception {
        for(String name:new String[]{"Groq","OpenRouter","OpenAI","Gemini","Custom"}){
            CloudProviderConfig c=new CloudProviderConfig(name,"https://example.com/v1/chat/completions",name.equals("Groq")?"openai/gpt-oss-20b":"test-model");
            JSONObject p=new JSONObject(OpenAiCompatibleProvider.payload("synthetic request",c));
            assertEquals(c.model,p.getString("model"));assertEquals(2,p.getJSONArray("messages").length());
            assertEquals(name.equals("Groq"),p.has("include_reasoning"));
            assertEquals(name.equals("Groq")||name.equals("OpenAI"),p.has("max_completion_tokens"));
            assertEquals(!(name.equals("Groq")||name.equals("OpenAI")),p.has("max_tokens"));
        }
    }
    @Test public void genericAdapterAcceptsEveryPresetResponseWithNoHistory() {
        for(String name:new String[]{"Groq","OpenRouter","OpenAI","Gemini","Custom"}){
            CloudProviderConfig c=new CloudProviderConfig(name,"https://example.com/v1/chat/completions","test");
            OpenAiCompatibleProvider p=new OpenAiCompatibleProvider(c,"synthetic_session_key_123456789",(k,b)->{
                assertFalse(b.contains("secret hint"));
                return OpenAiCompatibleProviderTest.response("{\"type\":\"READ_TIME\",\"target\":\"time\",\"text\":\"\",\"channel\":\"\"}");
            });assertNotNull(p.propose("test",java.util.Arrays.asList("secret hint")));
        }
    }
}
