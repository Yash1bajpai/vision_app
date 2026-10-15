package com.vision.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, qualifiers="w360dp-h640dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SavedPreferenceUiTest {
    Object field(MainActivity a,String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(a);}
    void memory(MainActivity a,SavedPreferenceMemory m)throws Exception{Field f=MainActivity.class.getDeclaredField("savedPreferenceMemory");f.setAccessible(true);f.set(a,m);}
    Button send(View v){if(v instanceof Button&&"↑".contentEquals(((Button)v).getText()))return(Button)v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){Button b=send(g.getChildAt(i));if(b!=null)return b;}}return null;}
    void command(MainActivity a,String s)throws Exception{((EditText)field(a,"inputField")).setText(s);send(a.getWindow().getDecorView()).performClick();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();}
    void enableSaved(MainActivity a)throws Exception{
        command(a,"enable saved preference memory");
        android.app.AlertDialog dialog=(android.app.AlertDialog)field(a,"activeDialog");
        assertNotNull(dialog);dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }
    String text(MainActivity a)throws Exception{return((TextView)field(a,"activityText")).getText().toString();}
    SavedPreferenceMemory fixture(MainActivity a)throws Exception{return new SavedPreferenceMemory(new PreferenceMemoryStore(a.getSharedPreferences("ui-saved-test",0),new PreferenceMemoryStoreTest.TestCrypto()));}
    void capture(MainActivity a,String name)throws Exception{
        View root=a.getWindow().getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY));root.layout(0,0,1080,1920);
        Bitmap b=Bitmap.createBitmap(1080,1920,Bitmap.Config.ARGB_8888);root.draw(new Canvas(b));
        try(FileOutputStream out=new FileOutputStream(new File(System.getProperty("java.io.tmpdir"),name))){b.compress(Bitmap.CompressFormat.PNG,100,out);}
    }
    void captureBottom(MainActivity a,String name)throws Exception{
        android.widget.ScrollView scroll=(android.widget.ScrollView)field(a,"activityScroll");
        capture(a,"vision-saved-before-scroll.png");
        scroll.scrollTo(0,scroll.getChildAt(0).getHeight());
        assertTrue("Long preference content must be reachable by scrolling",scroll.getScrollY()>0);
        capture(a,name);
    }
    @Test public void savedCommandsLocalOptInNoticeAndNoSessionMigration()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();memory(a,fixture(a));
        command(a,"enable preference memory");command(a,"remember preference response_style concise");
        enableSaved(a);assertTrue(text(a).contains("No session value copied"));assertTrue(text(a).contains("30 days"));capture(a,"vision-saved-opt-in.png");captureBottom(a,"vision-saved-opt-in-bottom.png");
        command(a,"show saved preferences");assertTrue(text(a).contains("not set"));
        command(a,"save preference response_style concise");assertTrue(text(a).startsWith("PREFERENCE SAVED ON DEVICE"));capture(a,"vision-saved-result.png");
        command(a,"save preference approved yes");assertTrue(text(a).contains("NOT SAVED"));
        assertTrue(((VisionSessionContext)field(a,"sessionContext")).snapshot(SystemClock.elapsedRealtime()).isEmpty());
        command(a,"show preferences");assertTrue(text(a).contains("SESSION PREFERENCES"));assertTrue(text(a).contains("SAVED DEVICE PREFERENCES"));capture(a,"vision-saved-inspect.png");captureBottom(a,"vision-saved-inspect-bottom.png");
        c.pause().stop().destroy();
    }
    @Test public void savedPreferenceSurvivesLifecycleSessionDoesNotAndChangesOnlyStatus()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();
        SavedPreferenceMemoryTest.Disk disk=new SavedPreferenceMemoryTest.Disk();memory(a,new SavedPreferenceMemory(disk));
        enableSaved(a);command(a,"save preference response_style concise");
        command(a,"enable preference memory");command(a,"remember preference response_style standard");command(a,"check time");assertTrue(text(a).contains("It is "));
        c.pause().stop().start().resume();command(a,"check time");assertTrue(text(a).contains("Time: "));
        c.recreate();a=c.get();memory(a,new SavedPreferenceMemory(disk));command(a,"check time");assertTrue(text(a).contains("Time: "));
        command(a,"show preferences");assertTrue(text(a).contains("SESSION PREFERENCES\n\nMemory: off"));assertTrue(text(a).contains("response_style: concise"));
        command(a,"not a command");assertFalse(text(a).contains("response_style"));
        command(a,"disable saved preference memory");command(a,"check time");assertTrue(text(a).contains("It is "));
        c.pause().stop().destroy();
    }
    @Test public void unavailableRealKeystoreDoesNotClaimSaveInRobolectric()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();
        enableSaved(a);assertTrue(text(a).contains("UNAVAILABLE"));
        command(a,"save preference response_style concise");assertFalse(text(a).startsWith("PREFERENCE SAVED ON DEVICE"));
        command(a,"check time");assertTrue(text(a).contains("It is "));assertTrue(text(a).contains("UNAVAILABLE"));
        c.pause().stop().destroy();
    }
    @Test public void noticeMustBeAcceptedBeforeOptInAndDismissalDoesNotSave()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();
        SavedPreferenceMemoryTest.Disk disk=new SavedPreferenceMemoryTest.Disk();memory(a,new SavedPreferenceMemory(disk));
        command(a,"enable saved preference memory");assertNull(disk.value);
        android.app.AlertDialog dialog=(android.app.AlertDialog)field(a,"activeDialog");
        View decor=dialog.getWindow().getDecorView();decor.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.AT_MOST));decor.layout(0,0,1080,decor.getMeasuredHeight());
        Bitmap notice=Bitmap.createBitmap(1080,decor.getMeasuredHeight(),Bitmap.Config.ARGB_8888);decor.draw(new Canvas(notice));
        try(FileOutputStream out=new FileOutputStream(new File(System.getProperty("java.io.tmpdir"),"vision-saved-consent-dialog.png"))){notice.compress(Bitmap.CompressFormat.PNG,100,out);}
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick();assertNull(disk.value);
        command(a,"enable saved preference memory");((android.app.AlertDialog)field(a,"activeDialog")).cancel();assertNull(disk.value);
        enableSaved(a);assertNotNull(disk.value);c.pause().stop().destroy();
    }
    @Test public void inspectIsNotPutInSavedActivityState()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();memory(a,fixture(a));
        enableSaved(a);command(a,"save preference response_style concise");command(a,"show preferences");
        Bundle b=new Bundle();c.saveInstanceState(b);assertFalse(b.containsKey("activity_text"));
        for(String key:b.keySet()){Object value=b.get(key);assertFalse(key, value instanceof CharSequence&&value.toString().contains("response_style"));}
        c.pause().stop().destroy();
    }
    @Test public void existingDeleteClearDisableAlsoRemoveSavedValues()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();SavedPreferenceMemory m=fixture(a);memory(a,m);
        enableSaved(a);command(a,"save preference response_style concise");command(a,"forget preference response_style");assertNull(m.style(System.currentTimeMillis()));
        command(a,"save preference response_style standard");command(a,"clear preferences");assertNull(m.style(System.currentTimeMillis()));
        command(a,"save preference response_style concise");command(a,"disable preference memory");assertTrue(m.inspect(System.currentTimeMillis()).contains("Memory: off"));
        c.pause().stop().destroy();
    }
}
