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
public class VisionSessionUiTest {
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
    @Test public void typedControlsAndRealExpiryCallback() throws Exception {
        org.robolectric.android.controller.ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a=c.get(); command(a,"check battery");
        VisionSessionContext context=(VisionSessionContext)field(a,"sessionContext");
        assertEquals(1,context.snapshot(android.os.SystemClock.elapsedRealtime()).size());
        command(a,"forget context"); assertTrue(context.snapshot(android.os.SystemClock.elapsedRealtime()).isEmpty());
        command(a,"help"); shadowOf(Looper.getMainLooper()).idleFor(60,TimeUnit.SECONDS);
        assertNull(field(a,"contextExpiry")); assertTrue(context.snapshot(android.os.SystemClock.elapsedRealtime()).isEmpty());
        command(a,"cancel plan"); assertEquals("NO ACTIVE PLAN",((TextView)field(a,"activityText")).getText().toString());
        VisionPlan p=new VisionPlan("test",Arrays.asList(new VisionAction(VisionAction.Type.CREATE_CALENDAR_EVENT,"test","Synthetic event","2027-01-01T10:00"),new VisionAction(VisionAction.Type.READ_TIME,"time","")));
        Method start=MainActivity.class.getDeclaredMethod("startPlan",VisionPlan.class,EditText.class);start.setAccessible(true);start.invoke(a,p,field(a,"inputField"));
        AlertDialog dialog=(AlertDialog)field(a,"activeDialog");assertTrue(dialog.isShowing());
        command(a,"cancel plan"); shadowOf(Looper.getMainLooper()).idle();
        assertFalse(dialog.isShowing()); assertNull(field(a,"pendingPlan"));
        View root=a.getWindow().getDecorView(); root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY));root.layout(0,0,1080,1920);
        Bitmap bitmap=Bitmap.createBitmap(1080,1920,Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));
        try(FileOutputStream out=new FileOutputStream("/tmp/vision-cancel-preview.png")){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}
        c.pause().stop().destroy();
    }
}
