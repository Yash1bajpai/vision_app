package com.vision.app;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w360dp-h640dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class VisionPreferenceUiTest {
    private Object field(MainActivity a, String name) throws Exception {
        Field f=MainActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(a);
    }
    private Button send(View v) {
        if(v instanceof Button && "↑".contentEquals(((Button)v).getText())) return (Button)v;
        if(v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g=(android.view.ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++) { Button b=send(g.getChildAt(i)); if(b!=null)return b; }
        }
        return null;
    }
    private void command(MainActivity a,String text) throws Exception {
        ((EditText)field(a,"inputField")).setText(text); send(a.getWindow().getDecorView()).performClick();
    }
    @Test public void controlsAreLocalAndNeverAddedToRequestContext() throws Exception {
        org.robolectric.android.controller.ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a=c.get();
        command(a,"remember preference response_style concise");
        assertTrue(((TextView)field(a,"activityText")).getText().toString().contains("OFF"));
        command(a,"enable preference memory"); command(a,"remember preference response_style concise");
        command(a,"remember preference approved yes");
        assertTrue(((TextView)field(a,"activityText")).getText().toString().contains("NOT SAVED"));
        assertTrue(((VisionSessionContext)field(a,"sessionContext")).snapshot(android.os.SystemClock.elapsedRealtime()).isEmpty());
        command(a,"show preferences");
        assertTrue(((TextView)field(a,"activityText")).getText().toString().contains("response_style: concise"));
        command(a,"remember preference response_style concise");
        View root=a.getWindow().getDecorView(); root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY));root.layout(0,0,1080,1920);
        Bitmap bitmap=Bitmap.createBitmap(1080,1920,Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));
        try(FileOutputStream out=new FileOutputStream(new java.io.File(System.getProperty("java.io.tmpdir"), "vision-preferences-preview.png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}
        c.pause().stop().destroy();
    }
    @Test public void actualExpiryAndBackgroundReset() throws Exception {
        org.robolectric.android.controller.ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a=c.get(); command(a,"enable preference memory"); command(a,"remember preference response_style concise");
        shadowOf(Looper.getMainLooper()).idleFor(60,TimeUnit.SECONDS);
        assertNull(field(a,"preferenceExpiry"));
        assertTrue(((VisionPreferenceControls)field(a,"preferenceControls")).inspect(android.os.SystemClock.elapsedRealtime()).contains("not set"));
        command(a,"remember preference response_style standard"); c.pause().stop();
        assertNull(field(a,"preferenceExpiry"));
        assertTrue(((VisionPreferenceControls)field(a,"preferenceControls")).inspect(android.os.SystemClock.elapsedRealtime()).contains("Memory: off"));
        c.destroy();
    }
    @Test public void recreationNeverRestoresPreferenceOptInOrValues() throws Exception {
        org.robolectric.android.controller.ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a=c.get(); command(a,"enable preference memory"); command(a,"remember preference response_style concise");
        c.recreate(); command(c.get(),"show preferences");
        String text=((TextView)field(c.get(),"activityText")).getText().toString();
        assertTrue(text.contains("Memory: off")); assertTrue(text.contains("not set")); c.pause().stop().destroy();
    }
    @Test public void timeAndNetworkStyleChangesWithoutChangingFactsOrFailures() throws Exception {
        org.robolectric.android.controller.ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a=c.get(); command(a,"check time");
        String original=((TextView)field(a,"activityText")).getText().toString();
        assertTrue(original.contains("It is "));
        command(a,"enable preference memory"); command(a,"remember preference response_style concise"); command(a,"check time");
        String concise=((TextView)field(a,"activityText")).getText().toString();
        assertEquals(original.replace("It is ","Time: ").replace(" on ","; "),concise);
        command(a,"check network");
        assertTrue(((TextView)field(a,"activityText")).getText().toString().contains("Network: "));
        command(a,"show preferences");
        View root=a.getWindow().getDecorView(); root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY));root.layout(0,0,1080,1920);
        Bitmap bitmap=Bitmap.createBitmap(1080,1920,Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));
        try(FileOutputStream out=new FileOutputStream(new java.io.File(System.getProperty("java.io.tmpdir"), "vision-preferences-wired.png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}
        command(a,"disable preference memory"); command(a,"check time");
        assertTrue(((TextView)field(a,"activityText")).getText().toString().contains("It is "));
        command(a,"not a command");
        assertEquals("REQUEST NOT RECOGNIZED\n\nVision did not perform anything. Type help to see supported commands.",((TextView)field(a,"activityText")).getText().toString());
        c.pause().stop().destroy();
    }
    @Test public void batteryStylePreservesPercentageAndChargingState() throws Exception {
        org.robolectric.android.controller.ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a=c.get();
        android.os.BatteryManager bm=(android.os.BatteryManager)a.getSystemService(android.content.Context.BATTERY_SERVICE);
        shadowOf(bm).setIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY,42);
        android.content.Intent battery=new android.content.Intent(android.content.Intent.ACTION_BATTERY_CHANGED);
        battery.putExtra(android.os.BatteryManager.EXTRA_STATUS,android.os.BatteryManager.BATTERY_STATUS_CHARGING);
        a.sendStickyBroadcast(battery);
        command(a,"check battery"); assertEquals("SUCCEEDED\n\nBattery is at 42% and charging.",((TextView)field(a,"activityText")).getText().toString());
        command(a,"enable preference memory"); command(a,"remember preference response_style concise"); command(a,"check battery");
        assertEquals("SUCCEEDED\n\nBattery: 42% (charging).",((TextView)field(a,"activityText")).getText().toString());
        shadowOf(Looper.getMainLooper()).idleFor(60,TimeUnit.SECONDS); command(a,"check battery");
        assertEquals("SUCCEEDED\n\nBattery is at 42% and charging.",((TextView)field(a,"activityText")).getText().toString());
        c.pause().stop().destroy();
    }
}
