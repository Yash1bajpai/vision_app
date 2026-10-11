package com.vision.app;

import android.app.AlertDialog;
import android.os.Looper;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.android.controller.ActivityController;
import java.lang.reflect.Field;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 28) @LooperMode(LooperMode.Mode.PAUSED)
public class CloudOptInUiTest {
    static Object field(MainActivity a, String n) throws Exception {
        Field f=MainActivity.class.getDeclaredField(n); f.setAccessible(true); return f.get(a);
    }
    static void set(MainActivity a, String n, Object v) throws Exception {
        Field f=MainActivity.class.getDeclaredField(n); f.setAccessible(true); f.set(a,v);
    }
    static <T extends View> T find(View v, Class<T> kind, String text) {
        if (kind.isInstance(v) && (text == null || v instanceof TextView && text.contentEquals(((TextView)v).getText()))) return kind.cast(v);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g=(android.view.ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++){ T t=find(g.getChildAt(i),kind,text);if(t!=null)return t; }
        }
        return null;
    }
    static void command(MainActivity a, String s) throws Exception {
        ((EditText)field(a,"inputField")).setText(s);
        find(a.getWindow().getDecorView(),Button.class,"↑").performClick();
    }
    static AlertDialog dialog(MainActivity a) throws Exception { return (AlertDialog)field(a,"activeDialog"); }
    static String text(MainActivity a) throws Exception { return ((TextView)field(a,"activityText")).getText().toString(); }
    static ProviderSelector selector(MainActivity a) throws Exception { return (ProviderSelector)field(a,"providerSelector"); }
    static void configure(MainActivity a, int[] calls) throws Exception {
        GroqReasoningProvider p=new GroqReasoningProvider("synthetic_session_key_123456789",(k,b)->{
            calls[0]++;return GroqReasoningProviderTest.response("{\"type\":\"READ_TIME\",\"target\":\"time\",\"text\":\"\",\"channel\":\"\"}");
        });
        set(a,"cloudProvider",p);selector(a).register(ProviderMode.CLOUD,p);
        command(a,"provider use cloud");
    }
    static void settle() throws Exception {
        for(int i=0;i<80;i++){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);}
    }
    @Test public void setupDoesNotSelectCloudAndKeyNotSavedInBundle() throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();
        command(a,"cloud setup");AlertDialog d=dialog(a);
        EditText key=find(d.getWindow().getDecorView(),EditText.class,null);
        assertFalse(key.isSaveEnabled());key.setText("synthetic_session_key_123456789");
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(Looper.getMainLooper()).idle();
        assertTrue(selector(a).isAvailable(ProviderMode.CLOUD));
        assertEquals(ProviderMode.DETERMINISTIC_ONLY,selector(a).mode());
        assertEquals("",key.getText().toString());
        Bundle b=new Bundle();c.saveInstanceState(b);
        assertFalse(b.toString().contains("synthetic_session_key"));
        c.pause().stop();assertFalse(selector(a).isAvailable(ProviderMode.CLOUD));
        assertNull(field(a,"cloudProvider"));c.destroy();
    }
    @Test public void deniedUploadSendsNothingAndFixedCommandsRemainOffline() throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();int[] calls={0};configure(a,calls);
        command(a,"check battery");settle();assertEquals(0,calls[0]);
        command(a,"what hour might it be");assertEquals(0,calls[0]);
        dialog(a).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();settle();
        assertEquals(0,calls[0]);assertTrue(text(a).startsWith("CLOUD DENIED"));
        c.pause().stop().destroy();
    }
    @Test public void allowedUploadStillRequiresIntentReviewAndDenyRunsNothing() throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();int[] calls={0};configure(a,calls);
        command(a,"what hour might it be");dialog(a).getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle();
        assertEquals(1,calls[0]);AlertDialog review=dialog(a);assertTrue("status="+text(a), review.isShowing());
        assertEquals("THINKING",text(a).split("\\n")[0]);
        review.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();shadowOf(Looper.getMainLooper()).idle();
        assertTrue(text(a).startsWith("PROPOSAL DENIED"));c.pause().stop().destroy();
    }
    @Test public void staleUploadConsentAfterNewCommandCannotSend() throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();int[] calls={0};configure(a,calls);
        command(a,"what hour might it be");AlertDialog old=dialog(a);
        command(a,"provider use deterministic");assertFalse(old.isShowing());
        old.getButton(AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(Looper.getMainLooper()).idle();settle();assertEquals(0,calls[0]);c.pause().stop().destroy();
    }
    @Test public void backgroundClearsKeyAndStaleApprovalCannotSend() throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();int[] calls={0};configure(a,calls);
        GroqReasoningProvider p=(GroqReasoningProvider)field(a,"cloudProvider");
        command(a,"what hour might it be");AlertDialog old=dialog(a);c.pause().stop();
        assertFalse(p.configured());assertEquals(ProviderMode.DETERMINISTIC_ONLY,selector(a).mode());
        old.getButton(AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(Looper.getMainLooper()).idle();settle();assertEquals(0,calls[0]);c.destroy();
    }
    @Test public void forgetRemovesCloudRoute() throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();configure(a,new int[]{0});
        command(a,"cloud forget");assertFalse(selector(a).isAvailable(ProviderMode.CLOUD));assertNull(field(a,"cloudProvider"));
        command(a,"provider use cloud");assertTrue(text(a).startsWith("PROVIDER NOT CHANGED"));c.pause().stop().destroy();
    }
    @Test public void continuingReviewedProposalUsesExistingDispatch() throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();int[] calls={0};configure(a,calls);
        command(a,"what hour might it be");dialog(a).getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle();
        dialog(a).getButton(AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1,calls[0]);assertFalse(text(a).startsWith("THINKING"));assertFalse(text(a).startsWith("MODEL"));
        c.pause().stop().destroy();
    }
    @Test public void cancelInvalidatesPendingIntentReview() throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();int[] calls={0};configure(a,calls);
        command(a,"what hour might it be");dialog(a).getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle();AlertDialog old=dialog(a);
        command(a,"cancel request");String status=text(a);assertFalse(old.isShowing());
        old.getButton(AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(Looper.getMainLooper()).idle();assertEquals(status,text(a));
        c.pause().stop().destroy();
    }

    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void renderSetupAndUploadDialogsForVisualReview() throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();
        command(a,"cloud setup");render(dialog(a),"cloud-setup");dialog(a).dismiss();
        configure(a,new int[]{0});command(a,"what hour might it be");render(dialog(a),"cloud-upload");
        dialog(a).getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle();render(dialog(a),"cloud-review");
        c.pause().stop().destroy();
    }
    private static void render(AlertDialog d,String name) throws Exception {
        View v=d.getWindow().getDecorView();
        v.measure(View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.AT_MOST));
        v.layout(0,0,480,v.getMeasuredHeight());
        android.graphics.Bitmap b=android.graphics.Bitmap.createBitmap(480,v.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
        disableIndicators(v);
        v.draw(new android.graphics.Canvas(b));
        java.io.File dir=new java.io.File("build/visual-checks");dir.mkdirs();
        try(java.io.FileOutputStream f=new java.io.FileOutputStream(new java.io.File(dir,name+".png"))){b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,f);}
    }

    private static void disableIndicators(View v) {
        v.setScrollIndicators(0);
        if(v instanceof android.view.ViewGroup){android.view.ViewGroup g=(android.view.ViewGroup)v;for(int i=0;i<g.getChildCount();i++)disableIndicators(g.getChildAt(i));}
    }

}
