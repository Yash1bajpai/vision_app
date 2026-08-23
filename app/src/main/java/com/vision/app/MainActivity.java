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
        status.setOnClickListener(v -> openNotificationSettings());
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.setMargins(0, dp(24), 0, dp(22));
        root.addView(status, statusParams);

        Button readNotification = actionButton("Read latest notification");
        readNotification.setTextSize(13);
        readNotification.setTextColor(TEXT);
        readNotification.setGravity(Gravity.CENTER);
        readNotification.setBackground(round(PANEL, 12));
        readNotification.setOnClickListener(v -> onReadNotificationButtonClicked());
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
                VisionAction action = VisionActionParser.parse(command);
                if (action.type == VisionAction.Type.UNKNOWN) {
                    activityText.setText("REQUEST NOT RECOGNIZED\n\nVision did not perform anything. Try:\n\nRead my latest notification\nReply I'll be there soon\nOpen WhatsApp");
                    return;
                }
                String dialogTitle = action.type == VisionAction.Type.REPLY_NOTIFICATION
                        ? "Vision wants to send a reply"
                        : "Vision wants to proceed";
                String dialogMessage = action.type == VisionAction.Type.REPLY_NOTIFICATION
                        ? "Action: " + action.label() + "\n\nReply text:\n\"" + action.replyText + "\"\n\nAllow Vision to send this reply?"
                        : "Action: " + action.label() + "\n\nRequest: " + command + "\n\nAllow Vision to continue?";

                new AlertDialog.Builder(this)
                        .setTitle(dialogTitle)
                        .setMessage(dialogMessage)
                        .setNegativeButton("Deny", (dialog, which) -> {
                            action.state = VisionAction.State.DENIED;
                            activityText.setText("DENIED\n\n" + action.label() + "\n\nVision stopped this action.");
                        })
                        .setPositiveButton("Allow", (dialog, which) -> {
                            action.state = VisionAction.State.APPROVED;
                            executeAction(action);
                            input.setText("");
                            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                            if (imm != null && input.getWindowToken() != null) {
                                imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
                            }
                        }).show();
            }
        });
        root.addView(composer, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        updateAccessStatus();
    }

    private void executeAction(VisionAction action) {
        action.state = VisionAction.State.RUNNING;
        if (action.type == VisionAction.Type.READ_NOTIFICATION) {
            executeNotificationRead(action);
        } else if (action.type == VisionAction.Type.REPLY_NOTIFICATION) {
            executeNotificationReply(action);
        } else if (action.type == VisionAction.Type.OPEN_APP) {
            Intent launch = resolveAppLaunchIntent(action.target);
            if (launch == null) {
                action.state = VisionAction.State.FAILED;
                activityText.setText("FAILED\n\nCould not open " + action.target + ".\nThe app is not installed or has no launch screen.");
                return;
            }
            try {
                startActivity(launch);
                action.state = VisionAction.State.SUCCEEDED;
                activityText.setText("SUCCEEDED\n\nOpened " + action.target + ".");
            } catch (Exception e) {
                action.state = VisionAction.State.FAILED;
                activityText.setText("FAILED\n\nCould not start " + action.target + ".");
            }
        } else {
            action.state = VisionAction.State.FAILED;
            activityText.setText("FAILED\n\nVision could not process this request.");
        }
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

    private void openNotificationSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
                activityText.setText("FAILED\n\nCould not open notification settings.");
            }
        }
    }

    private void executeNotificationRead(VisionAction action) {
        if (!isNotificationAccessEnabled()) {
            action.state = VisionAction.State.FAILED;
            new AlertDialog.Builder(this)
                    .setTitle("Notification access needed")
                    .setMessage("Vision needs notification access to read supported-app notifications. Android will show exactly what access is being requested.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Open settings", (dialog, which) -> openNotificationSettings())
                    .show();
            activityText.setText("FAILED\n\nNotification access is not enabled.");
            return;
        }
        VisionNotificationListener.NotificationSnapshot snapshot = VisionNotificationListener.getLatestNotification();
        if (snapshot == null) {
            action.state = VisionAction.State.FAILED;
            activityText.setText("NO SUPPORTED NOTIFICATION\n\nVision has not received a Gmail, WhatsApp, Telegram, Messages, or Calendar notification yet.");
            return;
        }
        action.state = VisionAction.State.SUCCEEDED;
        String source = sourceName(snapshot.packageName);
        String title = snapshot.title.isEmpty() ? "(no sender shown)" : snapshot.title;
        String text = snapshot.text.isEmpty() ? "(no message text shown)" : snapshot.text;
        activityText.setText("SUCCEEDED\n\n" + source + "\n" + title + "\n\n" + text);
    }

    private void executeNotificationReply(VisionAction action) {
        if (!isNotificationAccessEnabled()) {
            action.state = VisionAction.State.FAILED;
            new AlertDialog.Builder(this)
                    .setTitle("Notification access needed")
                    .setMessage("Vision needs notification access to reply to notifications. Android will show exactly what access is being requested.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Open settings", (dialog, which) -> openNotificationSettings())
                    .show();
            activityText.setText("FAILED\n\nNotification access is not enabled.");
            return;
        }
        VisionNotificationListener.NotificationReplyCapability replyCap = VisionNotificationListener.getLatestReplyCapability();
        if (replyCap == null) {
            action.state = VisionAction.State.FAILED;
            VisionNotificationListener.NotificationSnapshot snapshot = VisionNotificationListener.getLatestNotification();
            if (snapshot != null) {
                String source = sourceName(snapshot.packageName);
                activityText.setText("NO REPLYABLE NOTIFICATION\n\nThe latest notification from " + source + " does not support direct reply.");
            } else {
                activityText.setText("NO SUPPORTED NOTIFICATION\n\nVision has not received a notification to reply to yet.");
            }
            return;
        }

        boolean success = VisionNotificationListener.sendReply(this, replyCap, action.replyText);
        if (success) {
            action.state = VisionAction.State.SUCCEEDED;
            String source = sourceName(replyCap.packageName);
            String recipient = replyCap.senderOrTitle.isEmpty() ? source : replyCap.senderOrTitle;
            activityText.setText("SUCCEEDED\n\nReplied to " + source + " (" + recipient + "):\n\n\"" + action.replyText + "\"");
        } else {
            action.state = VisionAction.State.FAILED;
            activityText.setText("FAILED\n\nCould not send reply. The notification action may have expired or was cancelled by Android.");
        }
    }

    private void onReadNotificationButtonClicked() {
        if (!isNotificationAccessEnabled()) {
            new AlertDialog.Builder(this)
                    .setTitle("Notification access needed")
                    .setMessage("Vision needs notification access to read supported-app notifications. Android will show exactly what access is being requested.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Open settings", (dialog, which) -> openNotificationSettings())
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

    private java.util.List<String> getCandidatePackages(String target) {
        java.util.List<String> list = new java.util.ArrayList<>();
        if ("WhatsApp".equalsIgnoreCase(target)) {
            list.add("com.whatsapp");
            list.add("com.whatsapp.w4b");
        } else if ("Telegram".equalsIgnoreCase(target)) {
            list.add("org.telegram.messenger");
        } else if ("Gmail".equalsIgnoreCase(target)) {
            list.add("com.google.android.gm");
        } else if ("Calendar".equalsIgnoreCase(target)) {
            list.add("com.google.android.calendar");
        } else if ("Messages".equalsIgnoreCase(target)) {
            list.add("com.google.android.apps.messaging");
            list.add("com.android.messaging");
        }
        return list;
    }

    private Intent resolveAppLaunchIntent(String target) {
        android.content.pm.PackageManager pm = getPackageManager();
        java.util.List<String> packages = getCandidatePackages(target);
        for (String pkg : packages) {
            try {
                Intent launch = pm.getLaunchIntentForPackage(pkg);
                if (launch != null) {
                    return launch;
                }
            } catch (Exception ignored) { }
            try {
                Intent query = new Intent(Intent.ACTION_MAIN);
                query.addCategory(Intent.CATEGORY_LAUNCHER);
                query.setPackage(pkg);
                java.util.List<android.content.pm.ResolveInfo> activities = pm.queryIntentActivities(query, 0);
                if (activities != null && !activities.isEmpty()) {
                    android.content.pm.ActivityInfo ai = activities.get(0).activityInfo;
                    if (ai != null) {
                        Intent explicit = new Intent(Intent.ACTION_MAIN);
                        explicit.addCategory(Intent.CATEGORY_LAUNCHER);
                        explicit.setComponent(new android.content.ComponentName(ai.packageName, ai.name));
                        explicit.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        return explicit;
                    }
                }
            } catch (Exception ignored) { }
        }
        if ("Calendar".equalsIgnoreCase(target)) {
            try {
                Intent cal = new Intent(Intent.ACTION_MAIN);
                cal.addCategory(Intent.CATEGORY_APP_CALENDAR);
                cal.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (cal.resolveActivity(pm) != null) {
                    return cal;
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    private boolean isNotificationAccessEnabled() {
        String listeners = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return !TextUtils.isEmpty(listeners) && listeners.contains(getPackageName());
    }

    private String sourceName(String packageName) {
        if ("com.whatsapp".equals(packageName) || "com.whatsapp.w4b".equals(packageName)) return "WhatsApp";
        if ("org.telegram.messenger".equals(packageName)) return "Telegram";
        if ("com.google.android.gm".equals(packageName)) return "Gmail";
        if ("com.google.android.calendar".equals(packageName)) return "Calendar";
        if ("com.google.android.apps.messaging".equals(packageName) || "com.android.messaging".equals(packageName)) return "Messages";
        return "Notification";
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
