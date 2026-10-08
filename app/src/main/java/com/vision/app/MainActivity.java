package com.vision.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(16, 21, 29);
    private static final int PANEL = Color.rgb(25, 32, 43);
    private static final int TEXT = Color.rgb(239, 244, 242);
    private static final int MUTED = Color.rgb(151, 165, 170);
    private static final int MINT = Color.rgb(158, 230, 194);
    private static final int REQUEST_CODE_READ_CONTACTS = 101;
    private static final int REQUEST_CODE_READ_CALENDAR = 102;
    private static final int REQUEST_CODE_WRITE_CALENDAR = 103;

    private static final String STATE_PENDING_ACTION_TYPE = "pending_action_type";
    private static final String STATE_PENDING_ACTION_REQUEST = "pending_action_request";
    private static final String STATE_PENDING_ACTION_TARGET = "pending_action_target";
    private static final String STATE_PENDING_ACTION_REPLY_TEXT = "pending_action_reply_text";
    private static final String STATE_PENDING_ACTION_CHANNEL = "pending_action_channel";
    private static final String STATE_PENDING_ACTION_STATE = "pending_action_state";
    private static final String STATE_PENDING_CALENDAR_TYPE = "pending_calendar_type";
    private static final String STATE_PENDING_CALENDAR_STATE = "pending_calendar_state";
    private static final String STATE_PENDING_EVENT_WRITE_TYPE = "pending_event_write_type";
    private static final String STATE_PENDING_EVENT_WRITE_REQUEST = "pending_event_write_request";
    private static final String STATE_PENDING_EVENT_WRITE_TARGET = "pending_event_write_target";
    private static final String STATE_PENDING_EVENT_WRITE_TEXT = "pending_event_write_text";
    private static final String STATE_PENDING_EVENT_WRITE_STATE = "pending_event_write_state";
    private static final String STATE_ACTIVITY_TEXT = "activity_text";

    private OfflineVoiceInput voiceInput = new OfflineVoiceInput();
    private final VisionVoiceSession voiceSession = new VisionVoiceSession();
    private Button voiceButton;
    private Runnable voiceTimeout;
    private static final int REQUEST_CODE_MIC = 104;
    private TextView activityText;
    private TextView statusText;
    private AlertDialog activeDialog;
    private VisionAction pendingContactAction;
    private EditText inputField;
    private final ReasoningProvider reasoningProvider = new NoOpReasoningProvider();

    // Multi-step plan state (in-memory only, never persisted; fail-closed on lifecycle loss)
    private VisionPlan pendingPlan;
    private int planStepIndex = 0;
    private final VisionPlanLifetime planLifetime = new VisionPlanLifetime();
    private final VisionSessionContext sessionContext = new VisionSessionContext();
    private final Handler planHandler = new Handler(Looper.getMainLooper());
    private Runnable planTimeout;
    private long planToken;
    private Runnable contextExpiry;
    private static final java.util.concurrent.atomic.AtomicInteger NEXT_PLAN_PERMISSION_CODE =
            new java.util.concurrent.atomic.AtomicInteger(1000);
    private int activePlanPermissionCode;
    private int activePlanPermissionKind;
    private Runnable pendingStepCompletion;

    // Pending calendar read across the runtime permission dialog (in-memory only)
    private VisionAction pendingCalendarAction;
    private Runnable pendingCalendarCompletion;

    // Pending calendar event write across the runtime permission dialog (in-memory only;
    // gated behind the CONFIRMED modal, which has already been approved by the time this is set)
    private VisionAction pendingEventWriteAction;
    private Runnable pendingEventWriteCompletion;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildScreen();
        if (state != null) {
            restoreInstanceState(state);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (outState != null) {
            // A pending action belonging to a plan step is deliberately NOT saved: the plan
            // dies with the Activity, and restoring the step as a standalone action would
            // orphan it after lifecycle loss (fail-closed).
            if (pendingContactAction != null && pendingStepCompletion == null) {
                if (pendingContactAction.type != null) {
                    outState.putString(STATE_PENDING_ACTION_TYPE, pendingContactAction.type.name());
                }
                outState.putString(STATE_PENDING_ACTION_REQUEST, pendingContactAction.request);
                outState.putString(STATE_PENDING_ACTION_TARGET, pendingContactAction.target);
                outState.putString(STATE_PENDING_ACTION_REPLY_TEXT, pendingContactAction.replyText);
                outState.putString(STATE_PENDING_ACTION_CHANNEL, pendingContactAction.channel);
                if (pendingContactAction.state != null) {
                    outState.putString(STATE_PENDING_ACTION_STATE, pendingContactAction.state.name());
                }
            }
            // A pending calendar read is a standalone SAFE action (never a plan step when
            // pending: plan-step pendings carry pendingStepCompletion and are skipped above),
            // so it is safe to restore — same lifecycle-recovery contract as Phase 8 contacts.
            if (pendingCalendarAction != null && pendingCalendarCompletion == null) {
                outState.putString(STATE_PENDING_CALENDAR_TYPE, "READ_CALENDAR");
                if (pendingCalendarAction.state != null) {
                    outState.putString(STATE_PENDING_CALENDAR_STATE, pendingCalendarAction.state.name());
                }
            }
            // A pending event write is always modal-approved already; a plan-step write
            // carries pendingEventWriteCompletion and is skipped (plan dies, fail-closed).
            if (pendingEventWriteAction != null && pendingEventWriteCompletion == null) {
                outState.putString(STATE_PENDING_EVENT_WRITE_TYPE, "CREATE_CALENDAR_EVENT");
                outState.putString(STATE_PENDING_EVENT_WRITE_REQUEST, pendingEventWriteAction.request);
                outState.putString(STATE_PENDING_EVENT_WRITE_TARGET, pendingEventWriteAction.target);
                outState.putString(STATE_PENDING_EVENT_WRITE_TEXT, pendingEventWriteAction.replyText);
                if (pendingEventWriteAction.state != null) {
                    outState.putString(STATE_PENDING_EVENT_WRITE_STATE, pendingEventWriteAction.state.name());
                }
            }
            if (pendingPlan == null && activityText != null && activityText.getText() != null) {
                outState.putCharSequence(STATE_ACTIVITY_TEXT, activityText.getText());
            }
        }
    }

    private void restoreInstanceState(Bundle state) {
        if (state == null) return;
        CharSequence savedText = state.getCharSequence(STATE_ACTIVITY_TEXT);
        if (savedText != null && activityText != null) {
            activityText.setText(savedText);
        }
        String typeStr = state.getString(STATE_PENDING_ACTION_TYPE);
        if (typeStr != null) {
            try {
                VisionAction.Type type = VisionAction.Type.valueOf(typeStr);
                String request = state.getString(STATE_PENDING_ACTION_REQUEST, "");
                String target = state.getString(STATE_PENDING_ACTION_TARGET, "");
                String replyText = state.getString(STATE_PENDING_ACTION_REPLY_TEXT, "");
                String channel = state.getString(STATE_PENDING_ACTION_CHANNEL, "");
                if (type == VisionAction.Type.SEND_MESSAGE_DIRECT && target != null && !target.trim().isEmpty()) {
                    VisionAction restored = new VisionAction(type, request, target, replyText, channel);
                    String stateStr = state.getString(STATE_PENDING_ACTION_STATE);
                    if (stateStr != null) {
                        try {
                            restored.state = VisionAction.State.valueOf(stateStr);
                        } catch (Exception ignored) { }
                    }
                    pendingContactAction = restored;
                } else {
                    pendingContactAction = null;
                }
            } catch (Exception e) {
                pendingContactAction = null;
            }
        }
        if ("READ_CALENDAR".equals(state.getString(STATE_PENDING_CALENDAR_TYPE))) {
            VisionAction restored = new VisionAction(VisionAction.Type.READ_CALENDAR, "", "calendar");
            String stateStr = state.getString(STATE_PENDING_CALENDAR_STATE);
            if (stateStr != null) {
                try {
                    restored.state = VisionAction.State.valueOf(stateStr);
                } catch (Exception ignored) { }
            }
            pendingCalendarAction = restored;
        }
        if ("CREATE_CALENDAR_EVENT".equals(state.getString(STATE_PENDING_EVENT_WRITE_TYPE))) {
            String target = state.getString(STATE_PENDING_EVENT_WRITE_TARGET, "");
            String text = state.getString(STATE_PENDING_EVENT_WRITE_TEXT, "");
            if (VisionActionParser.isValidEventTitle(target) && VisionActionParser.isValidCanonicalEventTime(text)) {
                VisionAction restored = new VisionAction(VisionAction.Type.CREATE_CALENDAR_EVENT,
                        state.getString(STATE_PENDING_EVENT_WRITE_REQUEST, ""), target, text);
                String stateStr = state.getString(STATE_PENDING_EVENT_WRITE_STATE);
                if (stateStr != null) {
                    try {
                        restored.state = VisionAction.State.valueOf(stateStr);
                    } catch (Exception ignored) { }
                }
                pendingEventWriteAction = restored;
            }
        }
    }

    @Override
    protected void onDestroy() {
        cancelVoice();
        stopPlan("PLAN CANCELLED", false);
        clearSessionContext();
        super.onDestroy();
        if (activeDialog != null) {
            if (activeDialog.isShowing()) {
                activeDialog.dismiss();
            }
            activeDialog = null;
        }
        // Fail-closed: a plan dies with the Activity; no step ever resumes after lifecycle loss.
        pendingContactAction = null;
        pendingCalendarAction = null;
        pendingCalendarCompletion = null;
        pendingEventWriteAction = null;
        pendingEventWriteCompletion = null;
        pendingPlan = null;
        planStepIndex = 0;
        pendingStepCompletion = null;
        inputField = null;
    }

    private void showManagedDialog(AlertDialog dialog) {
        if (activeDialog != null && activeDialog.isShowing()) {
            activeDialog.dismiss();
        }
        activeDialog = dialog;
        activeDialog.show();
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(24), dp(22), dp(16));
        root.setBackgroundColor(BG);

        LinearLayout header = row();
        TextView mark = label("V", 18, MINT);
        mark.setGravity(Gravity.CENTER);
        mark.setTypeface(null, android.graphics.Typeface.BOLD);
        mark.setBackground(round(MINT, 18));
        header.addView(mark, new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout titleBox = column();
        TextView title = label("Vision", 22, TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
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
        greeting.setTypeface(null, android.graphics.Typeface.BOLD);
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
        readNotification.setOnClickListener(v -> onReadNotificationButtonClicked(null));
        LinearLayout.LayoutParams readParams = new LinearLayout.LayoutParams(-1, dp(48));
        readParams.setMargins(0, 0, 0, dp(22));
        root.addView(readNotification, readParams);

        root.addView(label("RECENT ACTIVITY", 11, MUTED));
        activityText = label("No activity yet\n\nYour actions will appear here after you start a conversation.", 14, MUTED);
        activityText.setMovementMethod(new ScrollingMovementMethod());
        activityText.setGravity(Gravity.CENTER);
        activityText.setPadding(dp(20), dp(26), dp(20), dp(26));
        LinearLayout.LayoutParams activityParams = new LinearLayout.LayoutParams(-1, 0, 1);
        activityParams.setMargins(0, dp(8), 0, dp(16));
        root.addView(activityText, activityParams);

        LinearLayout composer = row();
        composer.setPadding(dp(14), dp(8), dp(8), dp(8));
        composer.setBackground(round(PANEL, 18));
        composer.setGravity(Gravity.BOTTOM);
        EditText input = new EditText(this);
        inputField = input;
        input.setHint("Ask Vision anything... (Enter for newline)");
        input.setHintTextColor(MUTED);
        input.setTextColor(TEXT);
        input.setTextSize(14);
        input.setSingleLine(false);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_NONE | EditorInfo.IME_FLAG_NO_ENTER_ACTION);
        input.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        input.setMinLines(1);
        input.setMaxLines(5);
        input.setVerticalScrollBarEnabled(true);
        input.setMovementMethod(new ScrollingMovementMethod());
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setPadding(dp(4), dp(6), dp(8), dp(6));
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        inputParams.gravity = Gravity.CENTER_VERTICAL;
        composer.addView(input, inputParams);
        Button send = actionButton("↑");
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        sendParams.gravity = Gravity.BOTTOM;
        sendParams.setMargins(dp(4), 0, 0, dp(2));
        composer.addView(send, sendParams);
        send.setOnClickListener(v -> {
            cancelVoice();
            String command = input.getText().toString().trim();
            if (!command.isEmpty()) {
                // The command is captured; clear the composer so every action type and
                // error surface starts from an empty field (send is final regardless of outcome).
                input.setText("");
                if (command.equalsIgnoreCase("cancel plan")) {
                    if (pendingPlan != null) stopPlan("PLAN CANCELLED", true);
                    else activityText.setText("NO ACTIVE PLAN");
                    return;
                }
                if (command.equalsIgnoreCase("forget context")) {
                    clearSessionContext();
                    activityText.setText("CONTEXT CLEARED");
                    return;
                }
                if (pendingPlan != null) {
                    activityText.setText("PLAN IN PROGRESS\n\nA plan is currently executing. Type cancel plan to stop it before starting a new request.");
                    return;
                }
                ReasoningCoordinator.CoordinationResult coordination =
                        ReasoningCoordinator.coordinateFull(command, reasoningProvider,
                                sessionContext.snapshot(SystemClock.elapsedRealtime()));
                sessionContext.remember(command, SystemClock.elapsedRealtime());
                if (contextExpiry != null) planHandler.removeCallbacks(contextExpiry);
                contextExpiry = this::clearSessionContext;
                planHandler.postDelayed(contextExpiry, VisionSessionContext.TTL_MILLIS);
                if (coordination.plan != null) {
                    startPlan(coordination.plan, input);
                    return;
                }
                VisionAction action = coordination.action;
                if (action.type == VisionAction.Type.UNKNOWN) {
                    activityText.setText("REQUEST NOT RECOGNIZED\n\nVision did not perform anything. Type help to see supported commands.");
                    return;
                }
                if (action.type == VisionAction.Type.OPEN_DIALER) {
                    handleDialerAction(action, null);
                } else if (action.type == VisionAction.Type.SHOW_HELP) {
                    handleHelpAction(action, null);
                } else if (action.type == VisionAction.Type.REPLY_NOTIFICATION) {
                    handleReplyAction(action, command, input, null);
                } else if (action.type == VisionAction.Type.SEND_MESSAGE_DIRECT) {
                    handleDirectMessageAction(action, input, null);
                } else if (action.type == VisionAction.Type.READ_NOTIFICATION) {
                    handleReadAction(action, command, input, null);
                } else if (action.type == VisionAction.Type.OPEN_APP) {
                    handleOpenAppAction(action, command, input, null);
                } else if (action.type == VisionAction.Type.READ_BATTERY) {
                    handleReadBatteryAction(action, null);
                } else if (action.type == VisionAction.Type.READ_NETWORK) {
                    handleReadNetworkAction(action, null);
                } else if (action.type == VisionAction.Type.READ_TIME) {
                    handleReadTimeAction(action, null);
                } else if (action.type == VisionAction.Type.READ_CALENDAR) {
                    handleReadCalendarAction(action, null);
                } else if (action.type == VisionAction.Type.SET_TIMER) {
                    handleSetTimerAction(action, null);
                } else if (action.type == VisionAction.Type.SET_REMINDER) {
                    handleReminderAction(action, null);
                } else if (action.type == VisionAction.Type.SET_ALARM) {
                    handleSetAlarmAction(action, null);
                } else if (action.type == VisionAction.Type.NAVIGATE_TO) {
                    handleNavigateAction(action, null);
                } else if (action.type == VisionAction.Type.MEDIA_CONTROL) {
                    handleMediaControlAction(action, null);
                } else if (action.type == VisionAction.Type.SET_VOLUME) {
                    handleSetVolumeAction(action, null);
                } else if (action.type == VisionAction.Type.TOGGLE_TORCH) {
                    handleToggleTorchAction(action, null);
                } else if (action.type == VisionAction.Type.CREATE_CALENDAR_EVENT) {
                    handleCreateCalendarEventAction(action, null);
                } else {
                    action.state = VisionAction.State.FAILED;
                    activityText.setText("FAILED\n\nVision could not process this request.");
                }
            }
        });
        voiceButton = actionButton("Hold to talk");
        voiceButton.setTextSize(12);
        voiceButton.setContentDescription("Hold to talk offline; release for a draft, then tap Send");
        voiceButton.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) { beginVoice(); return true; }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                try { voiceInput.stop(); } catch (Exception e) { cancelVoice(); }
                voiceButton.setText("Hold to talk");v.performClick();return true;
            }
            if (event.getAction() == MotionEvent.ACTION_CANCEL) { cancelVoice();return true; }
            return true;
        });
        voiceButton.setOnClickListener(v -> { });
        root.addView(voiceButton, new LinearLayout.LayoutParams(-1, dp(44)));
        root.addView(composer, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        updateAccessStatus();
    }

    private void handleDialerAction(VisionAction action, Runnable onComplete) {
        final long token = pendingPlan != null ? planToken : 0;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Open phone dialer?")
                .setMessage("Number: " + action.target + "\n\nVision only opens the dialer. You must tap Call in the phone app.")
                .setNegativeButton("Deny", (d, which) -> {
                    if (!mayExecutePendingAction(token)) return;
                    action.state = VisionAction.State.DENIED;
                    activityText.setText("DENIED\n\nDialer was not opened.");
                })
                .setPositiveButton("Open dialer", (d, which) -> {
                    if (isFinishing() || isDestroyed() || !mayExecutePendingAction(token)) return;
                    try {
                        startActivity(DialerIntentFactory.create(action.target));
                        action.state = VisionAction.State.COMPOSER_OPENED;
                        activityText.setText("DIALER OPENED\n\nNo call was placed by Vision.");
                    } catch (Exception e) {
                        action.state = VisionAction.State.FAILED;
                        activityText.setText("FAILED\n\nNo compatible dialer was opened.");
                    }
                })
                .setOnDismissListener(d -> {
                    if (token != 0 && (pendingPlan == null || token != planToken)) return;
                    if (activeDialog == d) activeDialog = null;
                    if (action.state == VisionAction.State.PROPOSED) action.state = VisionAction.State.DENIED;
                    if (onComplete != null) onComplete.run();
                }).create();
        activeDialog = dialog;
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.rgb(23, 99, 74));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.rgb(23, 99, 74));
    }

    private void beginVoice() {
        cancelVoice();
        if (pendingPlan != null || (activeDialog != null && activeDialog.isShowing())) {
            activityText.setText("VOICE UNAVAILABLE\n\nFinish or cancel the current confirmation or plan first.");return;
        }
        if (!voiceInput.available(this)) {
            activityText.setText("OFFLINE VOICE UNAVAILABLE\n\nAndroid 12+ and an installed on-device speech service are required. Type your request instead. No online fallback is used.");return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            activityText.setText("MICROPHONE PERMISSION NEEDED\n\nAllow microphone access, then hold to talk again. Voice only fills a draft.");
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_CODE_MIC);return;
        }
        final long token=voiceSession.start(inputField.getText().toString());
        voiceButton.setText("Release to finish");
        activityText.setText("LISTENING OFFLINE\n\nRelease to finish. Review the draft, then tap Send. Voice never submits a command.");
        voiceTimeout=() -> {
            cancelVoice();
            if (activityText!=null) activityText.setText("VOICE TIMED OUT\n\nNo command ran. Try again or type your request.");
        };planHandler.postDelayed(voiceTimeout,30_000);
        try {
            voiceInput.start(this,new OfflineVoiceInput.Callback() {
                public void result(String text) {
                    if (isFinishing() || isDestroyed() || inputField==null) return;
                    boolean accepted=voiceSession.accepts(token,inputField.getText().toString(),text);
                    if (!accepted) {
                        if (voiceSession.isCurrent(token)) cancelVoice();
                        return;
                    }
                    cancelVoice();inputField.setText(text.trim());inputField.setSelection(inputField.length());
                    activityText.setText("VOICE DRAFT READY\n\nCheck the text, then tap Send. Nothing has run.");
                }
                public void error() {
                    if (inputField==null || !voiceSession.isCurrent(token)) return;
                    cancelVoice();activityText.setText("VOICE STOPPED\n\nNo voice draft was accepted. Try again or type your request.");
                }
            });
        } catch (Exception e) { cancelVoice();activityText.setText("VOICE UNAVAILABLE\n\nCould not start offline recognition. Type your request instead."); }
    }

    private void cancelVoice() {
        voiceSession.cancel();
        if (voiceTimeout!=null) planHandler.removeCallbacks(voiceTimeout);
        voiceTimeout=null;
        try { voiceInput.cancel(); } catch (Exception ignored) { }
        if (voiceButton!=null) voiceButton.setText("Hold to talk");
    }

    private void handleHelpAction(VisionAction action, Runnable onComplete) {
        activityText.setText(VisionToolRegistry.helpText());
        action.state = VisionAction.State.SUCCEEDED;
        if (onComplete != null) onComplete.run();
    }

    // ===================== Device status reads (Tier SAFE) =====================

    private void handleReadBatteryAction(VisionAction action, Runnable onComplete) {
        try {
            BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
            int percent = bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
            if (percent < 0) {
                action.state = VisionAction.State.FAILED;
                activityText.setText("FAILED\n\nCould not read the battery status.");
                if (onComplete != null) onComplete.run();
                return;
            }
            // Charging state from the sticky battery-changed broadcast without registering a receiver.
            Intent battery = registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            int status = battery != null ? battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1) : -1;
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
            action.state = VisionAction.State.SUCCEEDED;
            activityText.setText("SUCCEEDED\n\nBattery is at " + percent + "%"
                    + (charging ? " and charging." : " and not charging."));
        } catch (Exception e) {
            action.state = VisionAction.State.FAILED;
            activityText.setText("FAILED\n\nCould not read the battery status.");
        }
        if (onComplete != null) onComplete.run();
    }

    private void handleReadNetworkAction(VisionAction action, Runnable onComplete) {
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean online = false;
            String kind = "no connection";
            if (cm != null) {
                android.net.Network active = cm.getActiveNetwork();
                android.net.NetworkCapabilities caps = active != null
                        ? cm.getNetworkCapabilities(active) : null;
                if (caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    online = true;
                    if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                        kind = "Wi-Fi";
                    } else if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        kind = "mobile data";
                    } else if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) {
                        kind = "Ethernet";
                    } else {
                        kind = "a connected network";
                    }
                }
            }
            action.state = VisionAction.State.SUCCEEDED;
            activityText.setText("SUCCEEDED\n\nThe device is "
                    + (online ? "online via " + kind + "." : "offline."));
        } catch (Exception e) {
            action.state = VisionAction.State.FAILED;
            activityText.setText("FAILED\n\nCould not read the network status.");
        }
        if (onComplete != null) onComplete.run();
    }

    private void handleReadTimeAction(VisionAction action, Runnable onComplete) {
        try {
            SimpleDateFormat dayFormat = new SimpleDateFormat("EEEE, d MMMM yyyy", Locale.US);
            SimpleDateFormat timeFormat = new SimpleDateFormat("h:mm a", Locale.US);
            Date now = new Date();
            action.state = VisionAction.State.SUCCEEDED;
            activityText.setText("SUCCEEDED\n\nIt is " + timeFormat.format(now)
                    + " on " + dayFormat.format(now) + ".");
        } catch (Exception e) {
            action.state = VisionAction.State.FAILED;
            activityText.setText("FAILED\n\nCould not read the current time.");
        }
        if (onComplete != null) onComplete.run();
    }

    private void handleReadCalendarAction(VisionAction action, Runnable onComplete) {
        if (checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingContactAction = null;
            pendingEventWriteAction = null;
            pendingEventWriteCompletion = null;
            pendingCalendarAction = action;
            pendingCalendarCompletion = onComplete;
            activityText.setText("CALENDAR PERMISSION NEEDED\n\nVision needs Calendar permission to read your next appointment.");
            requestActionPermissions(new String[]{android.Manifest.permission.READ_CALENDAR}, REQUEST_CODE_READ_CALENDAR);
            return;
        }
        executeCalendarRead(action, onComplete);
    }

    private void executeCalendarRead(VisionAction action, Runnable onComplete) {
        String[] projection = new String[] {
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME
        };
        long now = System.currentTimeMillis();
        long windowEnd = now + 7L * 24L * 60L * 60L * 1000L; // next 7 days
        StringBuilder result = new StringBuilder();
        android.net.Uri.Builder instancesUri = CalendarContract.Instances.CONTENT_URI.buildUpon();
        instancesUri.appendEncodedPath(Long.toString(now));
        instancesUri.appendEncodedPath(Long.toString(windowEnd));
        try (Cursor cursor = getContentResolver().query(
                instancesUri.build(),
                projection, null, null,
                CalendarContract.Instances.BEGIN + " ASC")) {
            int shown = 0;
            while (cursor != null && cursor.moveToNext() && shown < 3) {
                String title = cursor.getString(0);
                long begin = cursor.getLong(1);
                long end = cursor.getLong(2);
                boolean allDay = cursor.getInt(3) != 0;
                if (end < now) continue; // already ended; ongoing events are kept
                if (shown > 0) result.append("\n\n");
                SimpleDateFormat dayFormat = new SimpleDateFormat("EEEE, d MMMM", Locale.US);
                SimpleDateFormat timeFormat = new SimpleDateFormat("h:mm a", Locale.US);
                // All-day events are stored at UTC midnight; render in UTC to show the intended day.
                Date beginDate = allDay ? new Date(begin - java.util.TimeZone.getDefault().getOffset(begin)) : new Date(begin);
                Date endDate = allDay ? new Date(end - java.util.TimeZone.getDefault().getOffset(end)) : new Date(end);
                result.append("• ").append(title != null && !title.isEmpty() ? title : "(untitled event)")
                        .append("\n  ").append(dayFormat.format(beginDate));
                if (!allDay) {
                    result.append(" at ").append(timeFormat.format(beginDate))
                            .append(" – ").append(timeFormat.format(endDate));
                }
                shown++;
            }
        } catch (Exception e) {
            action.state = VisionAction.State.FAILED;
            activityText.setText("FAILED\n\nCould not read the calendar.");
            if (onComplete != null) onComplete.run();
            return;
        }
        action.state = VisionAction.State.SUCCEEDED;
        activityText.setText("SUCCEEDED\n\n"
                + (result.length() > 0
                        ? "Your next appointment(s):\n\n" + result
                        : "No upcoming events in the next 7 days."));
        if (onComplete != null) onComplete.run();
    }

    // ===================== Daily-driver intents (Phase 12) =====================

    private void handleSetTimerAction(VisionAction action, Runnable onComplete) {
        try {
            int seconds = (int) VisionActionParser.canonicalDurationSeconds(action.target);
            // The standard android.provider.action.SET_TIMER is tried first; some OEM clock
            // apps (BBK/vivo on this device) only register the nonstandard android.intent
            // action string, so that variant is the fallback. SKIP_UI is deliberately not
            // set: the vivo clock silently rejects the intent when asked to skip its UI,
            // and showing the timer screen doubles as a visual confirmation for the user.
            Intent timer = new Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "Vision timer")
                    .addCategory(Intent.CATEGORY_DEFAULT);
            if (timer.resolveActivity(getPackageManager()) == null) {
                timer = new Intent("android.intent.action.SET_TIMER")
                        .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, "Vision timer")
                        .addCategory(Intent.CATEGORY_DEFAULT);
            }
            if (timer.resolveActivity(getPackageManager()) == null) {
                action.state = VisionAction.State.FAILED;
                activityText.setText("FAILED\n\nNo clock app is available to set timers.");
            } else {
                startActivity(timer);
                action.state = VisionAction.State.SUCCEEDED;
                activityText.setText("SUCCEEDED\n\nTimer set for " + action.target
                        + ".\n\nThe countdown is running in your clock app.");
            }
        } catch (Exception e) {
            action.state = VisionAction.State.FAILED;
            activityText.setText("FAILED\n\nCould not set the timer.");
        }
        if (onComplete != null) onComplete.run();
    }

    private void handleReminderAction(VisionAction action, Runnable onComplete) {
        final long token = pendingPlan != null ? planToken : 0;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Review clock reminder?")
                .setMessage(action.replyText + "\nTime: " + action.target
                        + "\n\nClock alarm, not a dated reminder. Check the next date and saved alarm in Clock. Saving is not verified.")
                .setNegativeButton("Deny", (d, which) -> {
                    if (!mayExecutePendingAction(token)) return;
                    action.state = VisionAction.State.DENIED;
                    activityText.setText("DENIED\n\nClock was not opened.");
                })
                .setPositiveButton("Open clock", (d, which) -> {
                    if (isFinishing() || isDestroyed() || !mayExecutePendingAction(token)) return;
                    try {
                        Intent intent = ReminderIntentFactory.create(action.target, action.replyText);
                        if (intent.resolveActivity(getPackageManager()) == null) {
                            intent.setAction("android.intent.action.SET_ALARM");
                        }
                        if (intent.resolveActivity(getPackageManager()) == null) throw new android.content.ActivityNotFoundException();
                        startActivity(intent);
                        action.state = VisionAction.State.COMPOSER_OPENED;
                        activityText.setText("CLOCK OPENED\n\nCheck the date and saved alarm in the clock app. Vision has not verified a reminder was saved.");
                    } catch (Exception e) {
                        action.state = VisionAction.State.FAILED;
                        activityText.setText("FAILED\n\nNo compatible clock was opened.");
                    }
                })
                .setOnDismissListener(d -> {
                    if (token != 0 && (pendingPlan == null || token != planToken)) return;
                    if (activeDialog == d) activeDialog = null;
                    if (action.state == VisionAction.State.PROPOSED) action.state = VisionAction.State.DENIED;
                    if (onComplete != null) onComplete.run();
                }).create();
        showManagedDialog(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.rgb(23,99,74));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.rgb(23,99,74));
    }

    private void handleSetAlarmAction(VisionAction action, Runnable onComplete) {
        try {
            String[] parts = action.target.split(":");
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            Intent alarm = new Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "Vision alarm")
                    .addCategory(Intent.CATEGORY_DEFAULT);
            if (alarm.resolveActivity(getPackageManager()) == null) {
                alarm = new Intent("android.intent.action.SET_ALARM")
                        .putExtra(AlarmClock.EXTRA_HOUR, hour)
                        .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, "Vision alarm")
                        .addCategory(Intent.CATEGORY_DEFAULT);
            }
            if (alarm.resolveActivity(getPackageManager()) == null) {
                action.state = VisionAction.State.FAILED;
                activityText.setText("FAILED\n\nNo clock app is available to set alarms.");
            } else {
                startActivity(alarm);
                action.state = VisionAction.State.SUCCEEDED;
                activityText.setText("SUCCEEDED\n\nAlarm set for " + action.target
                        + ".\n\nThe alarm is scheduled in your clock app.");
            }
        } catch (Exception e) {
            action.state = VisionAction.State.FAILED;
            activityText.setText("FAILED\n\nCould not set the alarm.");
        }
        if (onComplete != null) onComplete.run();
    }

    private void handleNavigateAction(VisionAction action, Runnable onComplete) {
        try {
            android.net.Uri destination = android.net.Uri.parse("geo:0,0?q=" + android.net.Uri.encode(action.target));
            Intent navigate = new Intent(Intent.ACTION_VIEW, destination);
            if (navigate.resolveActivity(getPackageManager()) == null) {
                action.state = VisionAction.State.FAILED;
                activityText.setText("FAILED\n\nNo maps app is available for navigation.");
            } else {
                startActivity(navigate);
                action.state = VisionAction.State.SUCCEEDED;
                activityText.setText("SUCCEEDED\n\nNavigation opened to \"" + action.target
                        + "\".\n\nReview the route in your maps app before starting.");
            }
        } catch (Exception e) {
            action.state = VisionAction.State.FAILED;
            activityText.setText("FAILED\n\nCould not open navigation.");
        }
        if (onComplete != null) onComplete.run();
    }

    private void handleMediaControlAction(VisionAction action, Runnable onComplete) {
        try {
            int keyCode = VisionMediaCommand.keyCode(action.target);
            AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audio == null) {
                action.state = VisionAction.State.FAILED;
                activityText.setText("FAILED\n\nAudio service is not available.");
            } else {
                audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
                audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
                action.state = VisionAction.State.SUCCEEDED;
                activityText.setText("SUCCEEDED\n\nMedia command sent: " + action.target
                        + ".\n\nPlayback was not verified; the media app may ignore this command.");
            }
        } catch (Exception e) {
            action.state = VisionAction.State.FAILED;
            activityText.setText("FAILED\n\nCould not send the media command.");
        }
        if (onComplete != null) onComplete.run();
    }

    private void handleSetVolumeAction(VisionAction action, Runnable onComplete) {
        try {
            AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audio == null) {
                action.state = VisionAction.State.FAILED;
                activityText.setText("FAILED\n\nAudio service is not available.");
            } else if ("mute".equals(action.target)) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
                action.state = VisionAction.State.SUCCEEDED;
                activityText.setText("SUCCEEDED\n\nMedia volume muted.");
            } else if ("unmute".equals(action.target)) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
                action.state = VisionAction.State.SUCCEEDED;
                activityText.setText("SUCCEEDED\n\nMedia volume unmuted.");
            } else {
                int percent = Integer.parseInt(action.target);
                int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int index = (int) Math.round(max * percent / 100.0);
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0);
                action.state = VisionAction.State.SUCCEEDED;
                activityText.setText("SUCCEEDED\n\nMedia volume set to " + percent + "%.");
            }
        } catch (Exception e) {
            action.state = VisionAction.State.FAILED;
            activityText.setText("FAILED\n\nCould not change the volume.");
        }
        if (onComplete != null) onComplete.run();
    }

    private void handleToggleTorchAction(VisionAction action, Runnable onComplete) {
        boolean turnedOn = false;
        try {
            CameraManager cameras = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            boolean on = "on".equals(action.target);
            if (cameras != null) {
                for (String id : cameras.getCameraIdList()) {
                    Boolean hasFlash = cameras.getCameraCharacteristics(id)
                            .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (hasFlash != null && hasFlash) {
                        cameras.setTorchMode(id, on);
                        turnedOn = true;
                        break;
                    }
                }
            }
            if (turnedOn) {
                action.state = VisionAction.State.SUCCEEDED;
                activityText.setText("SUCCEEDED\n\nFlashlight turned " + (on ? "on" : "off") + ".");
            } else {
                action.state = VisionAction.State.FAILED;
                activityText.setText("FAILED\n\nNo flashlight is available on this device.");
            }
        } catch (CameraAccessException e) {
            action.state = VisionAction.State.FAILED;
            activityText.setText("FAILED\n\nCould not access the flashlight.");
        } catch (Exception e) {
            action.state = VisionAction.State.FAILED;
            activityText.setText("FAILED\n\nCould not toggle the flashlight.");
        }
        if (onComplete != null) onComplete.run();
    }

    // ===================== Calendar event creation (Tier CONFIRMED) =====================

    private void handleCreateCalendarEventAction(VisionAction action, Runnable onComplete) {
        final long actionPlanToken = pendingPlan != null ? planToken : 0;
        pendingEventWriteAction = null;
        pendingEventWriteCompletion = null;
        String when = formatEventTimeForDisplay(action.replyText);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Tony, may I create this event?")
                .setMessage("I am ready to add this event to your calendar:\n\n\""
                        + action.target + "\"\n" + when + "\n\nMay I proceed?")
                .setNegativeButton("Deny", (d, which) -> {
                    if (isFinishing() || isDestroyed() || !mayExecutePendingAction(actionPlanToken)) return;
                    action.state = VisionAction.State.DENIED;
                    activityText.setText("DENIED\n\nEvent \"" + action.target + "\"\n\nVision stopped this action.");
                })
                .setPositiveButton("Create event", (d, which) -> {
                    if (isFinishing() || isDestroyed() || !mayExecutePendingAction(actionPlanToken)) return;
                    action.state = VisionAction.State.APPROVED;
                    if (checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED
                            || checkSelfPermission(android.Manifest.permission.READ_CALENDAR)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        // Resolving the target calendar id queries the calendars provider,
                        // which needs READ_CALENDAR as well; request both together.
                        pendingContactAction = null;
                        pendingCalendarAction = null;
                        pendingEventWriteAction = action;
                        pendingEventWriteCompletion = onComplete;
                        activityText.setText("CALENDAR PERMISSION NEEDED\n\nVision needs Calendar permission to create events.");
                        requestActionPermissions(new String[]{android.Manifest.permission.WRITE_CALENDAR,
                                android.Manifest.permission.READ_CALENDAR}, REQUEST_CODE_WRITE_CALENDAR);
                        return;
                    }
                    executeCalendarEventInsert(action);
                })
                .setOnDismissListener(d -> {
                    if (actionPlanToken != 0 && (pendingPlan == null || actionPlanToken != planToken)) return;
                    if (activeDialog == d) activeDialog = null;
                    if (action.state == VisionAction.State.PROPOSED) {
                        action.state = VisionAction.State.DENIED;
                        if (isFinishing() || isDestroyed()) {
                            if (onComplete != null) onComplete.run();
                            return;
                        }
                        activityText.setText("CANCELLED\n\nEvent \"" + action.target + "\"\n\nConfirmation was dismissed.");
                    }
                    // When the write was deferred to the permission flow, the callback owns
                    // completion; otherwise this is the single fire-point.
                    if (pendingEventWriteAction == null && onComplete != null) {
                        onComplete.run();
                    }
                })
                .create();
        showManagedDialog(dialog);
    }

    /**
     * Inserts the approved event. Never fires completion: the modal dismiss listener
     * (button path) and the permission callback (deferred path) each own the latch
     * exclusively, so it fires exactly once on every path.
     */
    private void executeCalendarEventInsert(VisionAction action) {
        action.state = VisionAction.State.RUNNING;
        try {
            String calendarId = resolvePrimaryCalendarId();
            if (calendarId == null) {
                action.state = VisionAction.State.FAILED;
                activityText.setText("FAILED\n\nNo writable calendar was found on this device.");
                return;
            }
            java.time.LocalDateTime when = java.time.LocalDateTime.parse(action.replyText,
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            long start = when.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            long end = start + 60L * 60L * 1000L; // default 1-hour event
            ContentValues values = new ContentValues();
            values.put(CalendarContract.Events.CALENDAR_ID, calendarId);
            values.put(CalendarContract.Events.TITLE, action.target);
            values.put(CalendarContract.Events.DTSTART, start);
            values.put(CalendarContract.Events.DTEND, end);
            values.put(CalendarContract.Events.EVENT_TIMEZONE,
                    java.util.TimeZone.getDefault().getID());
            android.net.Uri inserted = getContentResolver().insert(CalendarContract.Events.CONTENT_URI, values);
            if (inserted == null) {
                action.state = VisionAction.State.FAILED;
                activityText.setText("FAILED\n\nThe calendar rejected the event.");
            } else {
                action.state = VisionAction.State.SUCCEEDED;
                activityText.setText("EVENT CREATED\n\n\"" + action.target + "\" was added for "
                        + formatEventTimeForDisplay(action.replyText) + ".");
            }
        } catch (Exception e) {
            action.state = VisionAction.State.FAILED;
            activityText.setText("FAILED\n\nCould not create the event.");
        }
    }

    /** Returns the device's primary (or first visible) calendar id, or null when none is writable. */
    private String resolvePrimaryCalendarId() {
        String[] projection = new String[] {
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.IS_PRIMARY,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        };
        String fallback = null;
        try (Cursor cursor = getContentResolver().query(
                CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                String id = cursor.getString(0);
                int isPrimary = cursor.getInt(1);
                int access = cursor.getInt(2);
                boolean writable = access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR;
                if (!writable) continue;
                if (isPrimary == 1) return id;
                if (fallback == null) fallback = id;
            }
        } catch (Exception e) {
            return null;
        }
        return fallback;
    }

    private String formatEventTimeForDisplay(String canonicalTime) {
        try {
            java.time.LocalDateTime when = java.time.LocalDateTime.parse(canonicalTime,
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            return when.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM 'at' HH:mm", Locale.US));
        } catch (Exception e) {
            return canonicalTime;
        }
    }

    // ===================== Multi-step plan execution =====================
    // Sequential, one step at a time. Every step flows through the same handler and the
    // same risk policy as a single typed command; approval of one step never approves
    // the next; a DENIED or FAILED step halts the plan and remaining steps never run.

    private void startPlan(VisionPlan plan, EditText input) {
        if (pendingPlan != null || plan == null || plan.stepCount() == 0) return;
        pendingPlan = plan;
        planToken = planLifetime.start(SystemClock.elapsedRealtime());
        final long token = planToken;
        planTimeout = () -> {
            if (pendingPlan != null && token == planToken) stopPlan("PLAN TIMED OUT", true);
        };
        planHandler.postDelayed(planTimeout, VisionPlanLifetime.TIMEOUT_MILLIS);
        planStepIndex = 0;
        pendingStepCompletion = null;
        activityText.setText("PLAN STARTED\n\n" + plan.stepCount() + " steps proposed. Step 1 of " + plan.stepCount() + " is starting.");
        executeNextPlanStep(input);
    }

    private void executeNextPlanStep(final EditText input) {
        if (pendingPlan == null) return;
        if (!planLifetime.isCurrent(planToken, SystemClock.elapsedRealtime())) {
            stopPlan("PLAN TIMED OUT", true);
            return;
        }
        if (planStepIndex >= pendingPlan.stepCount()) {
            finishPlan(input, true);
            return;
        }
        VisionAction step = pendingPlan.step(planStepIndex);
        // One-shot latch: dialog buttons and the dismiss listener may both fire completion
        // for the same step; only the first advances the plan.
        final VisionCompletionGate stepGate = new VisionCompletionGate();
        final long token = planToken;
        Runnable advance = () -> stepGate.fireOnce(() -> {
            if (pendingPlan == null || token != planToken) return;
            if (!planLifetime.isCurrent(token, SystemClock.elapsedRealtime())) {
                stopPlan("PLAN TIMED OUT", true);
                return;
            }
            onPlanStepTerminal(input);
        });
        if (step.type == VisionAction.Type.OPEN_DIALER) {
            handleDialerAction(step, advance);
        } else if (step.type == VisionAction.Type.SHOW_HELP) {
            handleHelpAction(step, advance);
        } else if (step.type == VisionAction.Type.READ_NOTIFICATION) {
            handleReadAction(step, step.request, input, advance);
        } else if (step.type == VisionAction.Type.OPEN_APP) {
            handleOpenAppAction(step, step.request, input, advance);
        } else if (step.type == VisionAction.Type.REPLY_NOTIFICATION) {
            handleReplyAction(step, step.request, input, advance);
        } else if (step.type == VisionAction.Type.SEND_MESSAGE_DIRECT) {
            handleDirectMessageAction(step, input, advance);
        } else if (step.type == VisionAction.Type.READ_BATTERY) {
            handleReadBatteryAction(step, advance);
        } else if (step.type == VisionAction.Type.READ_NETWORK) {
            handleReadNetworkAction(step, advance);
        } else if (step.type == VisionAction.Type.READ_TIME) {
            handleReadTimeAction(step, advance);
        } else if (step.type == VisionAction.Type.READ_CALENDAR) {
            handleReadCalendarAction(step, advance);
        } else if (step.type == VisionAction.Type.SET_TIMER) {
            handleSetTimerAction(step, advance);
        } else if (step.type == VisionAction.Type.SET_REMINDER) {
            handleReminderAction(step, advance);
        } else if (step.type == VisionAction.Type.SET_ALARM) {
            handleSetAlarmAction(step, advance);
        } else if (step.type == VisionAction.Type.NAVIGATE_TO) {
            handleNavigateAction(step, advance);
        } else if (step.type == VisionAction.Type.MEDIA_CONTROL) {
            handleMediaControlAction(step, advance);
        } else if (step.type == VisionAction.Type.SET_VOLUME) {
            handleSetVolumeAction(step, advance);
        } else if (step.type == VisionAction.Type.TOGGLE_TORCH) {
            handleToggleTorchAction(step, advance);
        } else if (step.type == VisionAction.Type.CREATE_CALENDAR_EVENT) {
            handleCreateCalendarEventAction(step, advance);
        } else {
            step.state = VisionAction.State.FAILED;
            onPlanStepTerminal(input);
        }
    }

    private void onPlanStepTerminal(EditText input) {
        if (pendingPlan == null) return;
        VisionAction step = pendingPlan.step(Math.min(planStepIndex, pendingPlan.stepCount() - 1));
        if (!VisionPlanExecutor.shouldProceedToNextStep(step.state)) {
            finishPlan(input, false);
            return;
        }
        planStepIndex++;
        if (planStepIndex >= pendingPlan.stepCount()) {
            finishPlan(input, true);
            return;
        }
        executeNextPlanStep(input);
    }

    private void finishPlan(EditText input, boolean completed) {
        int total = pendingPlan != null ? pendingPlan.stepCount() : 0;
        int succeeded = completed ? total : planStepIndex;
        String headline = completed ? "PLAN COMPLETED" : "PLAN STOPPED";
        String body = completed
                ? "All " + total + " steps finished."
                : succeeded + " of " + total + " steps finished. The remaining steps were not executed.";
        clearPlanState();
        if (isFinishing() || isDestroyed()) return;
        activityText.setText(headline + "\n\n" + body);
        if (input != null) {
            input.setText("");
            hideKeyboard(input);
        }
    }

    private void clearSessionContext() {
        if (contextExpiry != null) planHandler.removeCallbacks(contextExpiry);
        contextExpiry = null;
        sessionContext.clear();
    }

    private void requestActionPermissions(String[] permissions, int kind) {
        if (pendingPlan == null) { requestPermissions(permissions, kind); return; }
        // Each plan permission prompt has a unique ID: a late result cannot resume
        // the same permission kind in a replacement plan. Never recycle an ID.
        int code = NEXT_PLAN_PERMISSION_CODE.getAndIncrement();
        if (code > 65535) { stopPlan("PLAN STOPPED", true); return; }
        activePlanPermissionKind = kind;
        activePlanPermissionCode = code;
        requestPermissions(permissions, activePlanPermissionCode);
    }

    private boolean mayExecutePendingAction() {
        return mayExecutePendingAction(pendingPlan != null ? planToken : 0);
    }

    private boolean mayExecutePendingAction(long token) {
        if (token != 0 && (pendingPlan == null || token != planToken)) return false;
        if (pendingPlan == null) return true;
        if (planLifetime.isCurrent(planToken, SystemClock.elapsedRealtime())) return true;
        stopPlan("PLAN TIMED OUT", true);
        return false;
    }

    private void clearPlanState() {
        planLifetime.stop();
        if (planTimeout != null) planHandler.removeCallbacks(planTimeout);
        planTimeout = null;
        activePlanPermissionCode = 0;
        pendingPlan = null;
        planStepIndex = 0;
        pendingStepCompletion = null;
        pendingContactAction = null;
        pendingCalendarAction = null;
        pendingCalendarCompletion = null;
        pendingEventWriteAction = null;
        pendingEventWriteCompletion = null;
    }

    private void stopPlan(String headline, boolean report) {
        if (pendingPlan == null) return;
        int total = pendingPlan.stepCount();
        int finished = planStepIndex;
        VisionAction step = pendingPlan.step(Math.min(planStepIndex, total - 1));
        if (!VisionPlanExecutor.isStepTerminal(step.state)) step.state = VisionAction.State.DENIED;
        // Invalidate all callbacks BEFORE dismissal can try to advance the plan.
        clearPlanState();
        clearSessionContext();
        if (activeDialog != null) {
            AlertDialog dialog = activeDialog;
            activeDialog = null;
            dialog.dismiss();
        }
        if (report && !isFinishing() && !isDestroyed() && activityText != null) {
            activityText.setText(headline + "\n\n" + finished + " of " + total
                    + " steps finished. No remaining steps will run. Already dispatched actions cannot be undone.");
        }
    }

    @Override
    protected void onStop() {
        cancelVoice();
        stopPlan("PLAN CANCELLED", true);
        clearSessionContext();
        super.onStop();
    }

    @Override
    public void onBackPressed() {
        if (pendingPlan != null) stopPlan("PLAN CANCELLED", true);
        else super.onBackPressed();
    }

    // Tier CONFIRMED: opening an external composer is not proof that a message was sent.
    private void handleDirectMessageAction(VisionAction action, EditText input, Runnable onComplete) {
        if (action.isContactDestination()) {
            handleContactDirectMessageAction(action, input, onComplete);
            return;
        }
        handleExplicitDirectMessageAction(action, input, action.target, action.target, onComplete);
    }

    private void handleContactDirectMessageAction(VisionAction action, EditText input, Runnable onComplete) {
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingCalendarAction = null;
            pendingCalendarCompletion = null;
            pendingEventWriteAction = null;
            pendingEventWriteCompletion = null;
            pendingContactAction = action;
            pendingStepCompletion = onComplete;
            activityText.setText("CONTACTS PERMISSION NEEDED\n\nVision needs Contacts permission to resolve \"" + action.target + "\".");
            requestActionPermissions(new String[]{android.Manifest.permission.READ_CONTACTS}, REQUEST_CODE_READ_CONTACTS);
            return;
        }

        VisionContactResolver.ResolutionResult res = VisionContactResolver.queryAndResolve(this, action.target);
        if (res.status == VisionContactResolver.ResolutionStatus.NO_MATCH) {
            action.state = VisionAction.State.FAILED;
            activityText.setText("NO CONTACT FOUND\n\nNo contact found matching \"" + action.target + "\". No message was prepared.");
            if (onComplete != null) onComplete.run();
            return;
        }
        if (res.status == VisionContactResolver.ResolutionStatus.MULTIPLE_MATCHES) {
            action.state = VisionAction.State.FAILED;
            activityText.setText("AMBIGUOUS CONTACT\n\nMultiple contacts match \"" + action.target + "\". Please specify the full name.");
            if (onComplete != null) onComplete.run();
            return;
        }
        if (res.status == VisionContactResolver.ResolutionStatus.MALFORMED_NUMBER) {
            action.state = VisionAction.State.FAILED;
            activityText.setText("INVALID CONTACT NUMBER\n\nContact \"" + res.resolvedName + "\" does not have a valid international phone number (+ country code required).");
            if (onComplete != null) onComplete.run();
            return;
        }
        if (res.status == VisionContactResolver.ResolutionStatus.PERMISSION_DENIED) {
            action.state = VisionAction.State.FAILED;
            activityText.setText("FAILED\n\nContacts permission is needed to resolve contact names.");
            if (onComplete != null) onComplete.run();
            return;
        }
        if (!res.isSuccess() || res.resolvedNumber.isEmpty()) {
            action.state = VisionAction.State.FAILED;
            activityText.setText("FAILED\n\n" + (res.errorMessage.isEmpty() ? "Could not resolve contact." : res.errorMessage));
            if (onComplete != null) onComplete.run();
            return;
        }

        VisionAction boundAction = action.withResolvedContact(res.resolvedName, res.resolvedNumber);
        String destDisplay = res.resolvedName + " (" + res.maskedNumber + ")";
        handleExplicitDirectMessageAction(boundAction, input, destDisplay, res.resolvedName, onComplete);
    }

    private void handleExplicitDirectMessageAction(VisionAction action, EditText input, String destDisplay, String targetName, Runnable onComplete) {
        final long actionPlanToken = pendingPlan != null ? planToken : 0;
        Intent compose = DirectMessageIntentFactory.create(action);
        if (compose == null || compose.resolveActivity(getPackageManager()) == null) {
            action.state = VisionAction.State.FAILED;
            activityText.setText("FAILED\n\nNo supported composer is available for " + action.channel + ".");
            if (onComplete != null) onComplete.run();
            return;
        }

        String channel = action.channel.toUpperCase(java.util.Locale.US).replace('_', ' ');
        String confirmation = "I am ready to open the " + channel + " composer for "
                + destDisplay + ":\n\n\"" + action.replyText + "\"\n\n"
                + "The message will not be reported as sent until you send it in that app.\n\nMay I proceed?";
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Tony, may I prepare this message?")
                .setMessage(confirmation)
                .setNegativeButton("Deny", (d, which) -> {
                    if (isFinishing() || isDestroyed() || !mayExecutePendingAction(actionPlanToken)) return;
                    action.state = VisionAction.State.DENIED;
                    activityText.setText("DENIED\n\nMessage to " + destDisplay + "\n\nVision stopped this action.");
                })
                .setPositiveButton("Open composer", (d, which) -> {
                    if (isFinishing() || isDestroyed() || !mayExecutePendingAction(actionPlanToken)) return;
                    action.state = VisionAction.State.APPROVED;
                    action.state = VisionAction.State.RUNNING;
                    try {
                        startActivity(compose);
                        action.state = VisionAction.State.COMPOSER_OPENED;
                        activityText.setText("COMPOSER OPENED\n\n" + channel + " composer opened for "
                                + destDisplay + ".\n\nThe message has not been reported as sent.");
                        if (input != null) {
                            input.setText("");
                            hideKeyboard(input);
                        }
                    } catch (Exception e) {
                        action.state = VisionAction.State.FAILED;
                        activityText.setText("FAILED\n\nCould not open the " + channel + " composer.");
                    }
                })
                .setOnDismissListener(d -> {
                    if (actionPlanToken != 0 && (pendingPlan == null || actionPlanToken != planToken)) return;
                    if (activeDialog == d) activeDialog = null;
                    if (action.state == VisionAction.State.PROPOSED) {
                        action.state = VisionAction.State.DENIED;
                        if (isFinishing() || isDestroyed()) {
                            if (onComplete != null) onComplete.run();
                            return;
                        }
                        activityText.setText("CANCELLED\n\nMessage to " + destDisplay + "\n\nConfirmation was dismissed.");
                    }
                    // Single fire-point: every dismissal path (button, back, outside tap,
                    // programmatic) passes here exactly once.
                    if (onComplete != null) onComplete.run();
                })
                .create();
        showManagedDialog(dialog);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode >= 1000) {
            if (requestCode != activePlanPermissionCode || pendingPlan == null) return;
            requestCode = activePlanPermissionKind;
            activePlanPermissionCode = 0;
        }
        if (pendingPlan != null && !mayExecutePendingAction()) return;
        if (pendingContactAction == null && pendingCalendarAction == null
                && pendingEventWriteAction == null) return;
        if (requestCode == REQUEST_CODE_WRITE_CALENDAR) {
            VisionAction pending = pendingEventWriteAction;
            Runnable completion = pendingEventWriteCompletion;
            pendingEventWriteAction = null;
            pendingEventWriteCompletion = null;
            boolean granted = grantResults != null && grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
                    && grantResults.length > 1
                    && grantResults[1] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (pending != null && granted && !isFinishing() && !isDestroyed()) {
                executeCalendarEventInsert(pending);
            } else {
                if (pending != null) pending.state = VisionAction.State.FAILED;
                if (!isFinishing() && !isDestroyed()) {
                    activityText.setText("FAILED\n\nCalendar permission was denied. Vision cannot create events without permission.");
                }
            }
            if (completion != null) completion.run();
            return;
        }
        if (requestCode == REQUEST_CODE_READ_CALENDAR) {
            VisionAction pending = pendingCalendarAction;
            Runnable completion = pendingCalendarCompletion;
            pendingCalendarAction = null;
            pendingCalendarCompletion = null;
            pendingEventWriteAction = null;
            pendingEventWriteCompletion = null;
            boolean granted = grantResults != null && grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (pending != null && granted && !isFinishing() && !isDestroyed()) {
                executeCalendarRead(pending, completion);
            } else {
                if (pending != null) pending.state = VisionAction.State.FAILED;
                if (!isFinishing() && !isDestroyed()) {
                    activityText.setText("FAILED\n\nCalendar permission was denied. Vision cannot read appointments without permission.");
                }
                if (completion != null) completion.run();
            }
            return;
        }
        if (requestCode == REQUEST_CODE_READ_CONTACTS) {
            VisionAction pending = pendingContactAction;
            EditText input = inputField;
            Runnable completion = pendingStepCompletion;
            pendingContactAction = null;
            pendingStepCompletion = null;
            pendingEventWriteAction = null;
            pendingEventWriteCompletion = null;
            if (grantResults != null && grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                if (pending != null && !isFinishing() && !isDestroyed()) {
                    handleContactDirectMessageAction(pending, input, completion);
                } else if (!isFinishing() && !isDestroyed()) {
                    if (pending != null) pending.state = VisionAction.State.FAILED;
                    activityText.setText("FAILED\n\nContacts permission was granted, but the pending request could not be recovered. Please make your request again.");
                    if (completion != null) completion.run();
                }
            } else {
                if (pending != null) {
                    pending.state = VisionAction.State.FAILED;
                }
                if (!isFinishing() && !isDestroyed()) {
                    activityText.setText("FAILED\n\nContacts permission was denied. Vision cannot resolve contact names without permission.");
                }
                if (completion != null) completion.run();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusText != null) updateAccessStatus();
    }

    private void updateAccessStatus() {
        boolean enabled = isNotificationAccessEnabled();
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

    // Tier CONFIRMED: modal Allow/Deny confirmation required before sending replies
    private void handleReplyAction(VisionAction action, String command, EditText input, Runnable onComplete) {
        final long actionPlanToken = pendingPlan != null ? planToken : 0;
        if (!isNotificationAccessEnabled()) {
            action.state = VisionAction.State.FAILED;
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("Notification access needed")
                    .setMessage("Vision needs notification access to reply to notifications. Android will show exactly what access is being requested.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Open settings", (d, which) -> {
                        if (!isFinishing() && !isDestroyed()) {
                            openNotificationSettings();
                        }
                    })
                    .setOnDismissListener(d -> {
                        if (activeDialog == d) {
                            activeDialog = null;
                        }
                    })
                    .create();
            showManagedDialog(dialog);
            activityText.setText("FAILED\n\nNotification access is not enabled.");
            if (onComplete != null) onComplete.run();
            return;
        }

        VisionNotificationListener.NotificationReplyCapability replyCap = VisionNotificationListener.getLatestReplyCapability();
        if (replyCap == null) {
            action.state = VisionAction.State.FAILED;
            VisionNotificationListener.NotificationSnapshot snapshot = VisionNotificationListener.getLatestNotification();
            VisionNotificationListener.ListenerState state = VisionNotificationListener.getListenerState();
            if (snapshot != null) {
                String source = sourceName(snapshot.packageName);
                activityText.setText("NO REPLYABLE NOTIFICATION\n\nThe latest notification from " + source + " does not support direct reply.");
            } else if (state != null && state.status == VisionNotificationListener.NotificationStatus.NOTIFICATION_REMOVED) {
                activityText.setText("NOTIFICATION REMOVED\n\nThe notification was dismissed or removed before you could reply.");
            } else {
                activityText.setText("NO SUPPORTED NOTIFICATION\n\nVision has not received a notification to reply to yet.");
            }
            if (onComplete != null) onComplete.run();
            return;
        }

        String source = sourceName(replyCap.packageName);

        if (!VisionNotificationListener.validateTarget(action.target, replyCap.packageName, source,
                replyCap.senderOrTitle, replyCap.conversationTitle, replyCap.senderPerson)) {
            action.state = VisionAction.State.FAILED;
            String mismatchDesc = formatDestinationDisplay(source, replyCap);
            activityText.setText("TARGET MISMATCH\n\nThe active notification is from " + mismatchDesc + ", not \"" + action.target + "\". No reply was sent.");
            if (onComplete != null) onComplete.run();
            return;
        }

        final VisionNotificationListener.NotificationReplyCapability boundCap = replyCap;
        String destDisplay = formatDestinationDisplay(source, replyCap);

        String dialogTitle = VisionRiskPolicy.formatReplyConfirmationTitle();
        String dialogMessage = VisionRiskPolicy.formatReplyConfirmationMessage(destDisplay, action.replyText);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(dialogTitle)
                .setMessage(dialogMessage)
                .setNegativeButton("Deny", (d, which) -> {
                    if (isFinishing() || isDestroyed() || !mayExecutePendingAction(actionPlanToken)) return;
                    action.state = VisionAction.State.DENIED;
                    activityText.setText("DENIED\n\nReply to " + destDisplay + "\n\nVision stopped this action.");
                })
                .setPositiveButton("Allow", (d, which) -> {
                    if (isFinishing() || isDestroyed() || !mayExecutePendingAction(actionPlanToken)) return;
                    action.state = VisionAction.State.APPROVED;
                    executeBoundNotificationReply(action, boundCap, destDisplay);
                    input.setText("");
                    hideKeyboard(input);
                })
                .setOnDismissListener(d -> {
                    if (actionPlanToken != 0 && (pendingPlan == null || actionPlanToken != planToken)) return;
                    if (activeDialog == d) {
                        activeDialog = null;
                    }
                    if (action.state == VisionAction.State.PROPOSED) {
                        action.state = VisionAction.State.DENIED;
                        if (isFinishing() || isDestroyed()) {
                            if (onComplete != null) onComplete.run();
                            return;
                        }
                        activityText.setText("CANCELLED\n\nReply to " + destDisplay + "\n\nConfirmation was dismissed.");
                    }
                    // Single fire-point: every dismissal path (button, back, outside tap,
                    // programmatic) passes here exactly once.
                    if (onComplete != null) onComplete.run();
                })
                .create();
        showManagedDialog(dialog);
    }

    private void executeBoundNotificationReply(VisionAction action, VisionNotificationListener.NotificationReplyCapability boundCap, String destDisplay) {
        action.state = VisionAction.State.RUNNING;
        VisionNotificationListener.ReplyResult result = VisionNotificationListener.sendBoundReply(this, boundCap, action.replyText);
        if (result == VisionNotificationListener.ReplyResult.SUCCESS) {
            action.state = VisionAction.State.SUCCEEDED;
            activityText.setText("REPLY REQUESTED\n\nAndroid accepted the reply request to " + destDisplay + ":\n\n\"" + action.replyText + "\"");
        } else if (result == VisionNotificationListener.ReplyResult.STALE_OR_REMOVED) {
            action.state = VisionAction.State.FAILED;
            activityText.setText("FAILED\n\nThe notification was dismissed, replaced, or expired before the reply could be sent. Please make a new request.");
        } else {
            action.state = VisionAction.State.FAILED;
            activityText.setText("FAILED\n\nCould not send reply. The notification action may have expired or was cancelled by Android.");
        }
    }

    // Tier SAFE: auto-executes immediately without modal confirmation dialog
    private void handleReadAction(VisionAction action, String command, EditText input, Runnable onComplete) {
        if (!isNotificationAccessEnabled()) {
            action.state = VisionAction.State.FAILED;
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("Notification access needed")
                    .setMessage("Vision needs notification access to read supported-app notifications. Android will show exactly what access is being requested.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Open settings", (d, which) -> {
                        if (!isFinishing() && !isDestroyed()) {
                            openNotificationSettings();
                        }
                    })
                    .setOnDismissListener(d -> {
                        if (activeDialog == d) {
                            activeDialog = null;
                        }
                    })
                    .create();
            showManagedDialog(dialog);
            activityText.setText("FAILED\n\nNotification access is not enabled.");
            if (onComplete != null) onComplete.run();
            return;
        }
        VisionNotificationListener.NotificationSnapshot snapshot = VisionNotificationListener.getLatestNotification();
        if (snapshot == null) {
            action.state = VisionAction.State.FAILED;
            VisionNotificationListener.ListenerState state = VisionNotificationListener.getListenerState();
            if (state != null && state.status == VisionNotificationListener.NotificationStatus.NOTIFICATION_REMOVED) {
                activityText.setText("NOTIFICATION REMOVED\n\nThe latest notification was dismissed or removed. Please wait for a new notification.");
            } else {
                activityText.setText("NO SUPPORTED NOTIFICATION\n\nVision has not received a Gmail, WhatsApp, Telegram, Messages, or Calendar notification yet.");
            }
            if (onComplete != null) onComplete.run();
            return;
        }

        executeBoundNotificationRead(action, snapshot);
        input.setText("");
        hideKeyboard(input);
        if (onComplete != null) onComplete.run();
    }

    private void executeBoundNotificationRead(VisionAction action, VisionNotificationListener.NotificationSnapshot boundSnapshot) {
        VisionNotificationListener.NotificationSnapshot current = VisionNotificationListener.getLatestNotification();
        if (current == null || current != boundSnapshot || !current.key.equals(boundSnapshot.key)) {
            if (action != null) action.state = VisionAction.State.FAILED;
            activityText.setText("FAILED\n\nThe notification was dismissed, replaced, or expired before it could be read. Please make a new request.");
            return;
        }
        if (action != null) action.state = VisionAction.State.SUCCEEDED;
        String source = sourceName(boundSnapshot.packageName);
        String title = boundSnapshot.title.isEmpty() ? "(no sender shown)" : boundSnapshot.title;
        String text = boundSnapshot.text.isEmpty() ? "(no message text shown)" : boundSnapshot.text;
        activityText.setText("SUCCEEDED\n\n" + source + "\n" + title + "\n\n" + text);
    }

    // Tier SAFE: auto-executes immediately without modal confirmation dialog
    private void handleOpenAppAction(VisionAction action, String command, EditText input, Runnable onComplete) {
        Intent launch = resolveAppLaunchIntent(action.target);
        if (launch == null) {
            action.state = VisionAction.State.FAILED;
            activityText.setText("FAILED\n\nCould not open " + action.target + ".\nThe app is not installed or has no launch screen.");
            if (onComplete != null) onComplete.run();
            return;
        }
        action.state = VisionAction.State.RUNNING;
        try {
            startActivity(launch);
            action.state = VisionAction.State.SUCCEEDED;
            activityText.setText("SUCCEEDED\n\nOpened " + action.target + ".");
        } catch (Exception e) {
            action.state = VisionAction.State.FAILED;
            activityText.setText("FAILED\n\nCould not start " + action.target + ".");
        }
        input.setText("");
        hideKeyboard(input);
        if (onComplete != null) onComplete.run();
    }

    private void onReadNotificationButtonClicked(Runnable onComplete) {
        if (pendingPlan != null) {
            activityText.setText("PLAN IN PROGRESS\n\nA plan is currently executing. Type cancel plan to stop it before starting a new request.");
            return;
        }
        if (!isNotificationAccessEnabled()) {
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("Notification access needed")
                    .setMessage("Vision needs notification access to read supported-app notifications. Android will show exactly what access is being requested.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Open settings", (d, which) -> {
                        if (!isFinishing() && !isDestroyed()) {
                            openNotificationSettings();
                        }
                    })
                    .setOnDismissListener(d -> {
                        if (activeDialog == d) {
                            activeDialog = null;
                        }
                    })
                    .create();
            showManagedDialog(dialog);
            return;
        }
        VisionNotificationListener.NotificationSnapshot snapshot = VisionNotificationListener.getLatestNotification();
        if (snapshot == null) {
            VisionNotificationListener.ListenerState state = VisionNotificationListener.getListenerState();
            if (state != null && state.status == VisionNotificationListener.NotificationStatus.NOTIFICATION_REMOVED) {
                activityText.setText("NOTIFICATION REMOVED\n\nThe latest notification was dismissed or removed. Please wait for a new notification.");
            } else {
                activityText.setText("NO SUPPORTED NOTIFICATION\n\nVision has not received a Gmail, WhatsApp, Telegram, Messages, or Calendar notification yet.");
            }
            return;
        }
        executeBoundNotificationRead(null, snapshot);
    }

    private java.util.List<String> getCandidatePackages(String target) {
        java.util.List<String> list = new java.util.ArrayList<>();
        if ("WhatsApp Business".equalsIgnoreCase(target)) {
            list.add("com.whatsapp.w4b");
            list.add("com.whatsapp");
        } else if ("WhatsApp".equalsIgnoreCase(target)) {
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
                        explicit.setComponent(new ComponentName(ai.packageName, ai.name));
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
        if (TextUtils.isEmpty(listeners)) return false;
        String myPkg = getPackageName();
        String[] components = listeners.split(":");
        for (String compStr : components) {
            if (compStr == null || compStr.trim().isEmpty()) continue;
            ComponentName cn = ComponentName.unflattenFromString(compStr.trim());
            if (cn != null && myPkg.equals(cn.getPackageName())) {
                return true;
            }
        }
        return false;
    }

    private String formatDestinationDisplay(String source, VisionNotificationListener.NotificationReplyCapability cap) {
        return VisionNotificationListener.formatDestinationDisplay(source, cap);
    }

    private String sourceName(String packageName) {
        return VisionNotificationListener.resolveSourceName(packageName);
    }

    private void hideKeyboard(View view) {
        if (view == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && view.getWindowToken() != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
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
