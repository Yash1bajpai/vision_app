package com.vision.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.FileOutputStream;
import java.util.Arrays;
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
@Config(sdk=28, qualifiers="w360dp-h640dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class VisionReminderUiTest {
    private Object field(MainActivity a,String n) throws Exception { Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);return f.get(a); }
    private void show(MainActivity a,VisionAction action) throws Exception {
        Method m=MainActivity.class.getDeclaredMethod("handleReminderAction",VisionAction.class,Runnable.class);m.setAccessible(true);m.invoke(a,action,null);
    }
    private VisionAction action() { return VisionActionParser.parse("remind me Drink Water at 7 pm"); }
    @Test public void noDispatchUntilAllowAndDenyStopsIt() throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();
        VisionAction x=action();show(a,x);AlertDialog d=(AlertDialog)field(a,"activeDialog");
        assertNull(shadowOf(a).getNextStartedActivity());d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();shadowOf(Looper.getMainLooper()).idle();
        assertEquals(VisionAction.State.DENIED,x.state);assertNull(shadowOf(a).getNextStartedActivity());c.pause().stop().destroy();
    }
    @Test public void missingClockFailsAndRendersReview() throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();
        VisionAction x=action();show(a,x);shadowOf(Looper.getMainLooper()).idle();AlertDialog d=(AlertDialog)field(a,"activeDialog");
        View root=d.getWindow().getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1050,View.MeasureSpec.EXACTLY));root.layout(0,0,1000,1050);
        Bitmap b=Bitmap.createBitmap(1000,1050,Bitmap.Config.ARGB_8888);root.draw(new Canvas(b));
        if (!System.getProperty("os.name").toLowerCase().contains("windows")) {
            try(FileOutputStream out=new FileOutputStream("/tmp/vision-reminder-review.png")){b.compress(Bitmap.CompressFormat.PNG,100,out);}
        }
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(Looper.getMainLooper()).idle();
        assertNull(shadowOf(a).getNextStartedActivity());assertEquals(VisionAction.State.FAILED,x.state);
        c.pause().stop().destroy();
    }
    @Test public void cancelledPlanCannotUseStaleAllow() throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();VisionAction x=action();
        VisionPlan p=new VisionPlan("synthetic",Arrays.asList(x,new VisionAction(VisionAction.Type.READ_TIME,"time","time")));
        Method start=MainActivity.class.getDeclaredMethod("startPlan",VisionPlan.class,EditText.class);start.setAccessible(true);start.invoke(a,p,field(a,"inputField"));
        AlertDialog d=(AlertDialog)field(a,"activeDialog");android.widget.Button allow=d.getButton(AlertDialog.BUTTON_POSITIVE);
        a.onBackPressed();allow.performClick();shadowOf(Looper.getMainLooper()).idle();
        assertNull(shadowOf(a).getNextStartedActivity());assertNull(field(a,"pendingPlan"));assertEquals(VisionAction.State.DENIED,x.state);c.pause().stop().destroy();
    }
}
