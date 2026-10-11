package com.vision.app;

import android.app.AlertDialog;
import android.widget.EditText;
import android.view.View;
import android.view.ViewGroup;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Synthetic key and transport only. No external request or Android action executed. */
@RunWith(AndroidJUnit4.class)
public class CloudOptInInstrumentedTest {
    private static Object field(MainActivity a, String n) {
        try { Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);return f.get(a); }
        catch(Exception e){throw new AssertionError(e);}
    }
    private static EditText edit(View v) {
        if(v instanceof EditText)return (EditText)v;
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){EditText e=edit(g.getChildAt(i));if(e!=null)return e;}}
        return null;
    }
    @Test public void setupThenForgetKeepsOfflineDefault() {
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)) {
            s.onActivity(a->{
                VisionUi.typeAndSend(a,"cloud setup");AlertDialog d=VisionUi.activeDialog(a);
                EditText key=edit(d.getWindow().getDecorView());assertFalse(key.isSaveEnabled());
                key.setText("synthetic_session_key_123456789");d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });VisionUi.settle();
            s.onActivity(a->{
                ProviderSelector p=(ProviderSelector)field(a,"providerSelector");
                assertEquals(ProviderMode.DETERMINISTIC_ONLY,p.mode());assertTrue(p.isAvailable(ProviderMode.CLOUD));
                VisionUi.typeAndSend(a,"cloud forget");assertFalse(p.isAvailable(ProviderMode.CLOUD));
                assertNull(field(a,"cloudProvider"));
            });
        }
    }
    @Test public void denyUploadDoesNotCallCloud() {
        int[] calls={0};
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)) {
            s.onActivity(a->{
                try {
                    GroqReasoningProvider p=new GroqReasoningProvider("synthetic_session_key_123456789",(k,b)->{calls[0]++;return null;});
                    Field f=MainActivity.class.getDeclaredField("cloudProvider");f.setAccessible(true);f.set(a,p);
                    ((ProviderSelector)field(a,"providerSelector")).register(ProviderMode.CLOUD,p);
                    VisionUi.typeAndSend(a,"provider use cloud");
                    VisionUi.typeAndSend(a,"what hour might it be");
                    VisionUi.activeDialog(a).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
                }catch(Exception e){throw new AssertionError(e);}
            });VisionUi.settle();
            s.onActivity(a->{assertEquals(0,calls[0]);assertTrue(VisionUi.activityText(a).getText().toString().startsWith("CLOUD DENIED"));});
        }
    }
    @Test public void recreationClearsCloudKey() {
        final GroqReasoningProvider[] old={null};
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)) {
            s.onActivity(a->{
                VisionUi.typeAndSend(a,"cloud setup");AlertDialog d=VisionUi.activeDialog(a);
                edit(d.getWindow().getDecorView()).setText("synthetic_session_key_123456789");
                d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });VisionUi.settle();s.onActivity(a->old[0]=(GroqReasoningProvider)field(a,"cloudProvider"));
            s.recreate();s.onActivity(a->{
                assertFalse(old[0].configured());assertNull(field(a,"cloudProvider"));
                assertEquals(ProviderMode.DETERMINISTIC_ONLY,((ProviderSelector)field(a,"providerSelector")).mode());
            });
        }
    }
}
