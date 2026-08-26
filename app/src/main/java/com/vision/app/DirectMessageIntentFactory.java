package com.vision.app;

import android.content.Intent;
import android.net.Uri;

import java.util.Locale;

/** Builds only explicit, supported compose handoffs. It never performs delivery. */
public final class DirectMessageIntentFactory {
    private DirectMessageIntentFactory() { }

    public static Intent create(VisionAction action) {
        if (action == null || action.type != VisionAction.Type.SEND_MESSAGE_DIRECT
                || action.target.trim().isEmpty() || action.replyText.trim().isEmpty()) return null;
        String channel = action.channel.toLowerCase(Locale.US);
        String destination = action.target.trim();
        String body = action.replyText;
        if ("sms".equals(channel)) {
            if (!isInternationalPhone(destination)) return null;
            return new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + normalizePhone(destination)))
                    .putExtra("sms_body", body);
        }
        if ("email".equals(channel)) {
            if (!isEmail(destination)) return null;
            return new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(destination, "@")))
                    .putExtra(Intent.EXTRA_TEXT, body);
        }
        if ("whatsapp".equals(channel) || "whatsapp_business".equals(channel)) {
            if (!isInternationalPhone(destination)) return null;
            return new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://wa.me/" + digitsOnly(destination) + "?text=" + Uri.encode(body)))
                    .setPackage("whatsapp_business".equals(channel) ? "com.whatsapp.w4b" : "com.whatsapp");
        }
        if ("telegram".equals(channel)) {
            if (!isTelegramUsername(destination)) return null;
            String username = destination.startsWith("@") ? destination.substring(1) : destination;
            return new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://t.me/" + Uri.encode(username) + "?text=" + Uri.encode(body)))
                    .setPackage("org.telegram.messenger");
        }
        return null;
    }

    private static String normalizePhone(String value) {
        String trimmed = value.trim();
        boolean plus = trimmed.startsWith("+");
        String digits = digitsOnly(trimmed);
        return plus ? "+" + digits : digits;
    }

    private static String digitsOnly(String value) {
        return value.replaceAll("[^0-9]", "");
    }

    private static boolean isPhone(String value) {
        String digits = digitsOnly(value);
        return value.matches("^\\+?[0-9][0-9 .()-]*$")
                && digits.length() >= 7 && digits.length() <= 15;
    }

    private static boolean isEmail(String value) {
        return value.matches("^[A-Za-z0-9.!#$%&'*+^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$");
    }

    private static boolean isInternationalPhone(String value) {
        return value.startsWith("+") && isPhone(value);
    }

    private static boolean isTelegramUsername(String value) {
        return value.matches("^@?[A-Za-z][A-Za-z0-9_]{4,31}$");
    }
}
