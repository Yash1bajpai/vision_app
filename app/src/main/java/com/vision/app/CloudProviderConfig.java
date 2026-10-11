package com.vision.app;

import java.net.URI;
import org.json.JSONObject;
import org.json.JSONException;

/** Selected destination and model are bound to the credential inside encrypted storage. */
public final class CloudProviderConfig {
    public static final String[] NAMES={"Groq","OpenRouter","OpenAI","Gemini","Custom"};
    public final String name,endpoint,model;
    public CloudProviderConfig(String name,String customEndpoint,String model) {
        this.name=name;
        switch(name) {
            case "Groq": endpoint="https://api.groq.com/openai/v1/chat/completions";break;
            case "OpenRouter":endpoint="https://openrouter.ai/api/v1/chat/completions";break;
            case "OpenAI":endpoint="https://api.openai.com/v1/chat/completions";break;
            case "Gemini":endpoint="https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";break;
            case "Custom":endpoint=validateEndpoint(customEndpoint);break;
            default:throw new IllegalArgumentException("Unknown provider");
        }
        if(model==null||!model.trim().matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}"))throw new IllegalArgumentException("Model name required");
        this.model=model.trim();
    }
    private static String validateEndpoint(String value) {
        try {
            URI u=new URI(value==null?"":value.trim());String host=u.getHost();
            if(!"https".equals(u.getScheme())||host==null||u.getRawUserInfo()!=null||u.getRawQuery()!=null||u.getRawFragment()!=null
                    ||(u.getPort()!=-1&&u.getPort()!=443)||!u.getPath().endsWith("/chat/completions")
                    ||!host.matches("(?i)[a-z0-9.-]+")||host.equalsIgnoreCase("localhost")||!host.contains(".")
                    ||host.matches("[0-9.]+")||host.endsWith(".local"))throw new IllegalArgumentException("Use a HTTPS chat/completions URL without query or credentials");
            return u.toASCIIString();
        }catch(java.net.URISyntaxException e){throw new IllegalArgumentException("Invalid endpoint");}
    }
    String serialize(String key) {
        try{return new JSONObject().put("provider",name).put("endpoint",endpoint).put("model",model).put("key",key).toString();}
        catch(JSONException e){throw new IllegalArgumentException("Invalid config");}
    }
    static CloudProviderConfig fromJson(JSONObject j)throws JSONException {
        return new CloudProviderConfig(j.getString("provider"),j.getString("endpoint"),j.getString("model"));
    }
}
