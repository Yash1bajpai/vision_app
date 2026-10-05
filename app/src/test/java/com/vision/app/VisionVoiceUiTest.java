package com.vision.app;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.FileOutputStream;
import java.io.File;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import static org.robolectric.Shadows.shadowOf;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="w360dp-h640dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class VisionVoiceUiTest {
    static class FakeVoice extends OfflineVoiceInput {
        Callback callback;boolean started,cancelled;boolean available=true;
        public boolean available(Context c){return available;}
        public void start(Context c,Callback x){callback=x;started=true;cancelled=false;}
        public void cancel(){cancelled=true;}
    }
    private Object field(MainActivity a,String n)throws Exception{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);return f.get(a);}
    private void set(MainActivity a,String n,Object x)throws Exception{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);f.set(a,x);}
    private void begin(MainActivity a)throws Exception{Method m=MainActivity.class.getDeclaredMethod("beginVoice");m.setAccessible(true);m.invoke(a);}
    private FakeVoice install(MainActivity a)throws Exception{FakeVoice v=new FakeVoice();set(a,"voiceInput",v);shadowOf(a).grantPermissions(android.Manifest.permission.RECORD_AUDIO);return v;}
    @Test public void acceptedSpeechIsOnlyADraftAndPixelsRender()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();FakeVoice v=install(a);begin(a);v.callback.result("help");
        assertEquals("help",((EditText)field(a,"inputField")).getText().toString());assertNull(shadowOf(a).getNextStartedActivity());assertNull(field(a,"pendingPlan"));assertTrue(v.cancelled);
        View root=a.getWindow().getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY));root.layout(0,0,1080,1920);
        Bitmap b=Bitmap.createBitmap(1080,1920,Bitmap.Config.ARGB_8888);root.draw(new Canvas(b));try(FileOutputStream out=new FileOutputStream(new File(System.getProperty("java.io.tmpdir"),"vision-voice-draft.png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}
        c.pause().stop().destroy();
    }
    @Test public void backgroundRejectsLateResult()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();FakeVoice v=install(a);begin(a);c.pause().stop();v.callback.result("help");assertEquals("",((EditText)field(a,"inputField")).getText().toString());assertTrue(v.cancelled);c.destroy();
    }
    @Test public void editsAndTimeoutRejectResults()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();FakeVoice v=install(a);begin(a);((EditText)field(a,"inputField")).setText("manual edit");v.callback.result("help");assertEquals("manual edit",((EditText)field(a,"inputField")).getText().toString());
        begin(a);shadowOf(Looper.getMainLooper()).idleFor(30,TimeUnit.SECONDS);v.callback.result("help");assertEquals("manual edit",((EditText)field(a,"inputField")).getText().toString());assertTrue(v.cancelled);c.pause().stop().destroy();
    }
    @Test public void oldAndroidHasNoOnlineFallback()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();assertFalse(new OfflineVoiceInput().available(a));begin(a);assertNull(shadowOf(a).getNextStartedActivity());assertEquals("",((EditText)field(a,"inputField")).getText().toString());c.pause().stop().destroy();
    }
    @Test public void missingPermissionNeverStartsMicrophone()throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();FakeVoice v=new FakeVoice();set(a,"voiceInput",v);begin(a);assertFalse(v.started);assertEquals("android.content.pm.action.REQUEST_PERMISSIONS",shadowOf(a).getNextStartedActivity().getAction());c.pause().stop().destroy();
    }
    @Test public void replacementIgnoresOldCallback()throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();FakeVoice v=install(a);begin(a);OfflineVoiceInput.Callback old=v.callback;begin(a);old.result("old text");assertEquals("",((EditText)field(a,"inputField")).getText().toString());v.callback.result("new text");assertEquals("new text",((EditText)field(a,"inputField")).getText().toString());c.pause().stop().destroy();
    }
}
