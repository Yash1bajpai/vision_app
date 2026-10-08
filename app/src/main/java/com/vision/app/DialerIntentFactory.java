package com.vision.app;

import android.content.Intent;
import android.net.Uri;

/** Numeric dialer handoff only. No CALL_PHONE permission and no direct call. */
public final class DialerIntentFactory {
    private DialerIntentFactory() { }
    public static Intent create(String number) {
        if (number == null || !number.matches("\\+[0-9]{8,15}")) {
            throw new IllegalArgumentException("Use an explicit international number");
        }
        return new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null));
    }
}
