package com.vision.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.text.TextUtils;
import android.app.AlertDialog;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(16, 21, 29);
    private static final int PANEL = Color.rgb(25, 32, 43);
    private static final int TEXT = Color.rgb(239, 244, 242);
    private static final int MUTED = Color.rgb(151, 165, 170);
    private static final int MINT = Color.rgb(158, 230, 194);
    private TextView activityText;
    private TextView statusText;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildScreen();
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(24), dp(22), dp(16));
        root.setBackgroundColor(BG);

        LinearLayout header = row();
        TextView mark = label("V", 18, MINT);
        mark.setGravity(Gravity.CENTER);
        mark.setTypeface(null, 1);
        mark.setBackground(round(MINT, 18));
        header.addView(mark, new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout titleBox = column();
        TextView title = label("Vision", 22, TEXT);
        title.setTypeface(null, 1);
        titleBox.addView(title);
        titleBox.addView(label("Your private intelligence layer", 12, MUTED));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1);
        titleParams.setMargins(dp(12), 0, 0, 0);
        header.addView(titleBox, titleParams);
        TextView settings = label("•••", 20, MUTED);
        settings.setGravity(Gravity.CENTER);
        header.addView(settings, new LinearLayout.LayoutParams(dp(42), dp(42)));
        root.addView(header);

        TextView greeting = label("Good evening, Yash", 28, TEXT);
        greeting.setTypeface(null, 1);
        LinearLayout.LayoutParams greetingParams = new LinearLayout.LayoutParams(-1, -2);
        greetingParams.setMargins(0, dp(38), 0, dp(4));
        root.addView(greeting, greetingParams);
        root.addView(label("What would you like me to help with?", 14, MUTED));

        LinearLayout status = row();
        status.setPadding(dp(14), dp(12), dp(14), dp(12));
        status.setBackground(round(PANEL, 12));
        TextView dot = label("●", 13, MINT);
        status.addView(dot);
        statusText = label("  Offline mode     •     Model setup pending", 12, TEXT);
        status.addView(statusText);
        status.setOnClickListener(v -> startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.setMargins(0, dp(24), 0, dp(22));
        root.addView(status, statusParams);

        Button readNotification = actionButton("Read latest notification");
        readNotification.setTextSize(13);
        readNotification.setTextColor(TEXT);
        readNotification.setGravity(Gravity.CENTER);
        readNotification.setBackground(round(PANEL, 12));
        readNotification.setOnClickListener(v -> requestNotificationRead());
        LinearLayout.LayoutParams readParams = new LinearLayout.LayoutParams(-1, dp(48));
        readParams.setMargins(0, 0, 0, dp(22));
        root.addView(readNotification, readParams);

        root.addView(label("RECENT ACTIVITY", 11, MUTED));
        activityText = label("No activity yet\n\nYour actions will appear here after you start a conversation.", 14, MUTED);
        activityText.setGravity(Gravity.CENTER);
        activityText.setPadding(dp(20), dp(26), dp(20), dp(26));
        LinearLayout.LayoutParams activityParams = new LinearLayout.LayoutParams(-1, 0, 1);
        activityParams.setMargins(0, dp(8), 0, dp(16));
        root.addView(activityText, activityParams);

        LinearLayout composer = row();
        composer.setPadding(dp(14), dp(8), dp(8), dp(8));
        composer.setBackground(round(PANEL, 18));
        EditText input = new EditText(this);
        input.setHint("Ask Vision anything...");
        input.setHintTextColor(MUTED);
        input.setTextColor(TEXT);
        input.setTextSize(14);
        input.setSingleLine(true);
        input.setBackgroundColor(Color.TRANSPARENT);
        composer.addView(input, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button send = actionButton("↑");
        composer.addView(send, new LinearLayout.LayoutParams(dp(48), dp(48)));
        send.setOnClickListener(v -> {
            String command = input.getText().toString().trim();
            if (!command.isEmpty()) {
                new AlertDialog.Builder(this)
                        .setTitle("Vision wants to proceed")
                        .setMessage("I am going to process this request locally:\n\n" + command)
                        .setNegativeButton("Deny", (dialog, which) -> activityText.setText("REQUEST DENIED\n\nVision stopped this action."))
                        .setPositiveButton("Allow", (dialog, which) -> {
                            activityText.setText("JUST NOW\n\nYou asked: " + command + "\n\nVision is ready to process this locally.");
                            input.setText("");
                            ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                                    .hideSoftInputFromWindow(input.getWindowToken(), 0);
                        }).show();
            }
        });
        root.addView(composer, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        updateAccessStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusText != null) updateAccessStatus();
    }

    private void updateAccessStatus() {
        boolean enabled = false;
        String listeners = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (!TextUtils.isEmpty(listeners)) {
            enabled = listeners.contains(getPackageName());
        }
        statusText.setText(enabled
                ? "  Offline mode     •     Notifications connected"
                : "  Offline mode     •     Tap to connect notifications");
    }

    private void requestNotificationRead() {
        if (!isNotificationAccessEnabled()) {
            new AlertDialog.Builder(this)
                    .setTitle("Notification access needed")
                    .setMessage("Vision needs notification access to read supported-app notifications. Android will show exactly what access is being requested.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Open settings", (dialog, which) -> startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")))
                    .show();
            return;
        }
        VisionNotificationListener.NotificationSnapshot snapshot = VisionNotificationListener.getLatestNotification();
        if (snapshot == null) {
            activityText.setText("NO SUPPORTED NOTIFICATION\n\nVision has not received a Gmail, WhatsApp, Telegram, Messages, or Calendar notification yet.");
            return;
        }
        String preview = snapshot.title.isEmpty() ? "the latest notification" : "the notification from " + snapshot.title;
        new AlertDialog.Builder(this)
                .setTitle("Vision wants to read a notification")
                .setMessage("I am going to read " + preview + ". Continue?")
                .setNegativeButton("Deny", (dialog, which) -> activityText.setText("REQUEST DENIED\n\nVision did not read the notification."))
                .setPositiveButton("Allow", (dialog, which) -> {
                    String source = sourceName(snapshot.packageName);
                    String title = snapshot.title.isEmpty() ? "(no sender shown)" : snapshot.title;
                    String text = snapshot.text.isEmpty() ? "(no message text shown)" : snapshot.text;
                    activityText.setText("JUST NOW\n\n" + source + "\n" + title + "\n\n" + text);
                }).show();
    }

    private boolean isNotificationAccessEnabled() {
        String listeners = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return !TextUtils.isEmpty(listeners) && listeners.contains(getPackageName());
    }

    private String sourceName(String packageName) {
        if ("com.whatsapp".equals(packageName)) return "WhatsApp";
        if ("org.telegram.messenger".equals(packageName)) return "Telegram";
        if ("com.google.android.gm".equals(packageName)) return "Gmail";
        if ("com.google.android.calendar".equals(packageName)) return "Calendar";
        return "Messages";
    }

    private Button actionButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(22);
        button.setTextColor(BG);
        button.setGravity(Gravity.CENTER);
        button.setPadding(0, 0, 0, dp(2));
        button.setBackground(round(MINT, 16));
        return button;
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private GradientDrawable round(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
}
