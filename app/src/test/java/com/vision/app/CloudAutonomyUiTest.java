package com.vision.app;

import android.app.AlertDialog;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28) @LooperMode(LooperMode.Mode.PAUSED)
public class CloudAutonomyUiTest {
    static Object field(MainActivity a,String n)throws Exception{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);return f.get(a);}
    static void set(MainActivity a,String n,Object v)throws Exception{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);f.set(a,v);}
    static <T extends View>T find(View v,Class<T> k,String text){
        if(k.isInstance(v)&&(text==null||v instanceof TextView&&text.contentEquals(((TextView)v).getText())))return k.cast(v);
        if(v instanceof android.view.ViewGroup){android.view.ViewGroup g=(android.view.ViewGroup)v;for(int i=0;i<g.getChildCount();i++){T t=find(g.getChildAt(i),k,text);if(t!=null)return t;}}return null;
    }
    static void command(MainActivity a,String t)throws Exception{((EditText)field(a,"inputField")).setText(t);find(a.getWindow().getDecorView(),Button.class,"↑").performClick();}
    static ProviderSelector selector(MainActivity a)throws Exception{return (ProviderSelector)field(a,"providerSelector");}
    static String text(MainActivity a)throws Exception{return ((TextView)field(a,"activityText")).getText().toString();}
    static void settle()throws Exception{for(int i=0;i<80;i++){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);}}
    static void configure(MainActivity a,int[] calls)throws Exception{
        OpenAiCompatibleProvider p=new OpenAiCompatibleProvider("synthetic_session_key_123456789",(k,b)->{calls[0]++;return OpenAiCompatibleProviderTest.response("{\"type\":\"READ_TIME\",\"target\":\"time\",\"text\":\"\",\"channel\":\"\"}");});
        set(a,"cloudProvider",p);selector(a).register(ProviderMode.CLOUD,p);selector(a).select(ProviderMode.CLOUD);
    }
    @Test public void noKeyIsOfflineAndCloudUnavailable()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();
        assertEquals(ProviderMode.DETERMINISTIC_ONLY,selector(a).mode());command(a,"provider use cloud");assertTrue(text(a).startsWith("PROVIDER NOT CHANGED"));c.pause().stop().destroy();
    }
    @Test public void unknownRequestUsesCloudWithoutUploadOrReviewPrompts()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();int[] n={0};configure(a,n);
        command(a,"what hour might it be");assertNull(field(a,"activeDialog"));settle();assertEquals(1,n[0]);assertNull(field(a,"activeDialog"));
        assertFalse(text(a).startsWith("THINKING"));assertFalse(text(a).startsWith("MODEL"));c.pause().stop().destroy();
    }
    @Test public void knownCommandsNeverUploadEvenWithKey()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();int[] n={0};configure(a,n);
        command(a,"check battery");settle();assertEquals(0,n[0]);c.pause().stop().destroy();
    }
    @Test public void backgroundCancelsButDoesNotRevokeConfiguredCloud()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();configure(a,new int[]{0});
        OpenAiCompatibleProvider p=(OpenAiCompatibleProvider)field(a,"cloudProvider");c.pause().stop();assertTrue(p.configured());assertEquals(0L,field(a,"providerRequestId"));c.destroy();assertFalse(p.configured());
    }
    @Test public void deterministicPauseAndForgetStopCloud()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();int[] n={0};configure(a,n);
        command(a,"provider use deterministic");command(a,"what hour might it be");settle();assertEquals(0,n[0]);
        command(a,"cloud forget");assertNull(field(a,"cloudProvider"));assertFalse(selector(a).isAvailable(ProviderMode.CLOUD));c.pause().stop().destroy();
    }
    @Test public void setupEnablesCloudWithoutSavedStateOrPlaintextStorage()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();command(a,"cloud setup");
        AlertDialog d=(AlertDialog)field(a,"activeDialog");EditText key=findKey(d.getWindow().getDecorView());assertFalse(key.isSaveEnabled());
        find(d.getWindow().getDecorView(),android.widget.CheckBox.class,null).setChecked(false);
        key.setText("synthetic_session_key_123456789");d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(Looper.getMainLooper()).idle();
        assertEquals(ProviderMode.CLOUD,selector(a).mode());assertEquals("",key.getText().toString());
        android.os.Bundle b=new android.os.Bundle();c.saveInstanceState(b);assertFalse(b.toString().contains("synthetic_session_key"));c.pause().stop().destroy();
    }
    @Test public void existingRiskyMessageStillShowsConfirmation()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();int[] n={0};configure(a,n);
        command(a,"send sms to +15550101234: hello");assertEquals(0,n[0]);assertFalse(text(a).startsWith("COMPOSER OPENED"));
        assertTrue(VisionRiskPolicy.requiresConfirmation(VisionAction.Type.SEND_MESSAGE_DIRECT));c.pause().stop().destroy();
    }
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void renderOneTimeSetup()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();command(a,"cloud setup");
        View v=((AlertDialog)field(a,"activeDialog")).getWindow().getDecorView();
        v.measure(View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.AT_MOST));v.layout(0,0,480,v.getMeasuredHeight());disable(v);
        android.graphics.Bitmap b=android.graphics.Bitmap.createBitmap(480,v.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);v.draw(new android.graphics.Canvas(b));
        java.io.File dir=new java.io.File("build/visual-checks");dir.mkdirs();try(java.io.FileOutputStream f=new java.io.FileOutputStream(new java.io.File(dir,"cloud-autonomous-setup.png"))){b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,f);}c.pause().stop().destroy();
    }
    static EditText findKey(View v){
        if(v instanceof EditText && ((EditText)v).getHint()!=null && ((EditText)v).getHint().toString().contains("API key"))return (EditText)v;
        if(v instanceof android.view.ViewGroup){android.view.ViewGroup g=(android.view.ViewGroup)v;for(int i=0;i<g.getChildCount();i++){EditText t=findKey(g.getChildAt(i));if(t!=null)return t;}}return null;
    }
    @Test public void providerPickerListsAllAndCustomRequiresEndpointAndModel()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();command(a,"cloud setup");
        AlertDialog d=(AlertDialog)field(a,"activeDialog");android.widget.Spinner p=find(d.getWindow().getDecorView(),android.widget.Spinner.class,null);
        assertEquals(5,p.getCount());p.setSelection(4);shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Custom",p.getSelectedItem());EditText key=findKey(d.getWindow().getDecorView());key.setText("synthetic_session_key_123456789");
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(Looper.getMainLooper()).idle();assertEquals(ProviderMode.DETERMINISTIC_ONLY,selector(a).mode());
        assertTrue(text(a).startsWith("SETUP NOT CHANGED"));c.pause().stop().destroy();
    }
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void renderCustomProviderSetup()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();command(a,"cloud setup");
        AlertDialog d=(AlertDialog)field(a,"activeDialog");find(d.getWindow().getDecorView(),android.widget.Spinner.class,null).setSelection(4);shadowOf(Looper.getMainLooper()).idle();
        View v=d.getWindow().getDecorView();v.measure(View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.AT_MOST));v.layout(0,0,480,v.getMeasuredHeight());disable(v);
        android.graphics.Bitmap b=android.graphics.Bitmap.createBitmap(480,v.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);v.draw(new android.graphics.Canvas(b));
        java.io.File dir=new java.io.File("build/visual-checks");dir.mkdirs();try(java.io.FileOutputStream f=new java.io.FileOutputStream(new java.io.File(dir,"cloud-custom-setup.png"))){b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,f);}c.pause().stop().destroy();
    }
    static void disable(View v){v.setScrollIndicators(0);if(v instanceof android.view.ViewGroup){android.view.ViewGroup g=(android.view.ViewGroup)v;for(int i=0;i<g.getChildCount();i++)disable(g.getChildAt(i));}}
}
