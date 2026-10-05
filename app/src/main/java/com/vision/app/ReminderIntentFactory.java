package com.vision.app;

import android.content.Intent;
import android.provider.AlarmClock;

/** Labeled clock handoff, not an internal scheduler or a dated reminder. */
public final class ReminderIntentFactory {
    private ReminderIntentFactory() { }
    public static Intent create(String time, String label) {
        if (time == null || !time.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")
                || !VisionActionParser.isReminderLabel(label)) throw new IllegalArgumentException("Invalid reminder");
        String[] parts = time.split(":");
        return new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, Integer.parseInt(parts[0]))
                .putExtra(AlarmClock.EXTRA_MINUTES, Integer.parseInt(parts[1]))
                .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                .addCategory(Intent.CATEGORY_DEFAULT);
    }
}
