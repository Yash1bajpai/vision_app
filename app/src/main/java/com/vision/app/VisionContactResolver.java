package com.vision.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.ContactsContract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic, privacy-preserving contact resolver.
 *
 * Safety & Privacy Invariants:
 * 1. Zero disk persistence: contact data is never persisted to databases, files, or preferences.
 * 2. Minimal projection: queries only DISPLAY_NAME and NUMBER columns from ContactsContract.
 * 3. Exact match priority: exact full-name matches take precedence over token/partial matches.
 * 4. Token safe matching: when no exact match exists, queries match only against full whitespace-delimited tokens.
 * 5. Fail-closed on ambiguity: 0 matches and multiple matches strictly fail closed without opening a composer.
 * 6. International phone requirement: phone numbers must resolve to valid international numbers with country codes (+).
 * 7. Masked number formatting: provides safe masked representation for user confirmation dialogs.
 */
public final class VisionContactResolver {

    public enum ResolutionStatus {
        MATCH_FOUND,
        NO_MATCH,
        MULTIPLE_MATCHES,
        MALFORMED_NUMBER,
        PERMISSION_DENIED
    }

    public static final class ContactEntry {
        public final String displayName;
        public final String phoneNumber;

        public ContactEntry(String displayName, String phoneNumber) {
            this.displayName = displayName != null ? displayName.trim() : "";
            this.phoneNumber = phoneNumber != null ? phoneNumber.trim() : "";
        }
    }

    public static final class ResolutionResult {
        public final ResolutionStatus status;
        public final String query;
        public final String resolvedName;
        public final String resolvedNumber;
        public final String maskedNumber;
        public final String errorMessage;

        public ResolutionResult(ResolutionStatus status, String query, String resolvedName,
                                String resolvedNumber, String maskedNumber, String errorMessage) {
            this.status = status;
            this.query = query != null ? query : "";
            this.resolvedName = resolvedName != null ? resolvedName : "";
            this.resolvedNumber = resolvedNumber != null ? resolvedNumber : "";
            this.maskedNumber = maskedNumber != null ? maskedNumber : "";
            this.errorMessage = errorMessage != null ? errorMessage : "";
        }

        public static ResolutionResult success(String query, String resolvedName, String resolvedNumber, String maskedNumber) {
            return new ResolutionResult(ResolutionStatus.MATCH_FOUND, query, resolvedName, resolvedNumber, maskedNumber, "");
        }

        public static ResolutionResult noMatch(String query) {
            return new ResolutionResult(ResolutionStatus.NO_MATCH, query, "", "", "",
                    "No contact found matching \"" + (query != null ? query : "") + "\".");
        }

        public static ResolutionResult multipleMatches(String query, int matchCount) {
            return new ResolutionResult(ResolutionStatus.MULTIPLE_MATCHES, query, "", "", "",
                    "Multiple contacts (" + matchCount + ") match \"" + (query != null ? query : "") + "\". Please specify the full name.");
        }

        public static ResolutionResult malformedNumber(String query, String contactName, String rawNumber) {
            return new ResolutionResult(ResolutionStatus.MALFORMED_NUMBER, query, contactName, "", "",
                    "Contact \"" + (contactName != null ? contactName : "") + "\" does not have a valid international phone number (+ country code required).");
        }

        public static ResolutionResult permissionDenied(String query) {
            return new ResolutionResult(ResolutionStatus.PERMISSION_DENIED, query, "", "", "",
                    "Contacts permission is needed to resolve contact names.");
        }

        public boolean isSuccess() {
            return status == ResolutionStatus.MATCH_FOUND;
        }
    }

    private VisionContactResolver() { }

    /**
     * Resolves a query against a list of contact entries using deterministic, fail-closed matching.
     */
    public static ResolutionResult resolve(String query, List<ContactEntry> contacts) {
        if (query == null || query.trim().isEmpty()) {
            return ResolutionResult.noMatch("");
        }
        String cleanQuery = query.trim();
        String normQuery = cleanQuery.toLowerCase(Locale.US);

        if (contacts == null || contacts.isEmpty()) {
            return ResolutionResult.noMatch(cleanQuery);
        }

        // Filter valid candidate contacts
        List<ContactEntry> candidates = new ArrayList<>();
        for (ContactEntry entry : contacts) {
            if (entry != null && !entry.displayName.trim().isEmpty()) {
                candidates.add(entry);
            }
        }
        if (candidates.isEmpty()) {
            return ResolutionResult.noMatch(cleanQuery);
        }

        // 1. Check for Exact Match
        List<ContactEntry> exactMatches = new ArrayList<>();
        for (ContactEntry entry : candidates) {
            if (entry.displayName.trim().toLowerCase(Locale.US).equals(normQuery)) {
                exactMatches.add(entry);
            }
        }

        if (!exactMatches.isEmpty()) {
            return evaluateMatchGroup(cleanQuery, exactMatches);
        }

        // 2. Check for Unique Safe Token Matches
        String[] queryTokens = normQuery.split("\\s+");
        List<ContactEntry> tokenMatches = new ArrayList<>();
        for (ContactEntry entry : candidates) {
            String normName = entry.displayName.trim().toLowerCase(Locale.US);
            String[] nameTokens = normName.split("[\\s,.-]+");
            if (matchesAllTokens(queryTokens, nameTokens)) {
                tokenMatches.add(entry);
            }
        }

        if (tokenMatches.isEmpty()) {
            return ResolutionResult.noMatch(cleanQuery);
        }

        // Check if token matches refer to multiple distinct contact names
        Map<String, List<ContactEntry>> byName = new LinkedHashMap<>();
        for (ContactEntry entry : tokenMatches) {
            String key = entry.displayName.trim().toLowerCase(Locale.US);
            List<ContactEntry> list = byName.get(key);
            if (list == null) {
                list = new ArrayList<>();
                byName.put(key, list);
            }
            list.add(entry);
        }

        if (byName.size() > 1) {
            return ResolutionResult.multipleMatches(cleanQuery, byName.size());
        }

        // Exactly one matching contact name found via token matching
        List<ContactEntry> singleContactEntries = byName.values().iterator().next();
        return evaluateMatchGroup(cleanQuery, singleContactEntries);
    }

    private static boolean matchesAllTokens(String[] queryTokens, String[] nameTokens) {
        for (String qToken : queryTokens) {
            if (qToken.isEmpty()) continue;
            boolean found = false;
            for (String nToken : nameTokens) {
                if (nToken.equals(qToken)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private static ResolutionResult evaluateMatchGroup(String query, List<ContactEntry> entries) {
        String contactName = entries.get(0).displayName;
        List<String> distinctValidNumbers = new ArrayList<>();
        boolean hasMalformed = false;
        String lastMalformed = "";

        for (ContactEntry entry : entries) {
            String normalized = normalizeInternationalPhone(entry.phoneNumber);
            if (normalized != null) {
                if (!distinctValidNumbers.contains(normalized)) {
                    distinctValidNumbers.add(normalized);
                }
            } else {
                hasMalformed = true;
                lastMalformed = entry.phoneNumber;
            }
        }

        if (distinctValidNumbers.isEmpty()) {
            return ResolutionResult.malformedNumber(query, contactName, lastMalformed);
        }

        if (distinctValidNumbers.size() > 1) {
            return ResolutionResult.multipleMatches(query, distinctValidNumbers.size());
        }

        String validNumber = distinctValidNumbers.get(0);
        String masked = maskPhoneNumber(validNumber);
        return ResolutionResult.success(query, contactName, validNumber, masked);
    }

    /**
     * Normalizes and validates an international phone number.
     * Must start with '+' and contain 7 to 15 digits.
     * Returns normalized phone (e.g. "+919876543210") or null if invalid/malformed.
     */
    public static String normalizeInternationalPhone(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (!trimmed.startsWith("+")) return null;
        String digits = trimmed.replaceAll("[^0-9]", "");
        if (digits.length() < 7 || digits.length() > 15) return null;
        if (!trimmed.matches("^\\+?[0-9][0-9 .()-]*$")) return null;
        return "+" + digits;
    }

    /**
     * Masks middle digits of a phone number for user-facing confirmation.
     * e.g. "+919876543210" -> "+91 •••• 3210"
     */
    public static String maskPhoneNumber(String phone) {
        if (phone == null || phone.trim().isEmpty()) return "";
        String trimmed = phone.trim();
        String digits = trimmed.replaceAll("[^0-9]", "");
        if (digits.length() < 4) {
            return trimmed;
        }
        boolean hasPlus = trimmed.startsWith("+");
        int keepStart = (hasPlus && digits.length() >= 7) ? 2 : (digits.length() >= 7 ? 2 : 1);
        int keepEnd = digits.length() >= 7 ? 4 : 2;
        if (keepStart + keepEnd >= digits.length()) {
            keepStart = 1;
            keepEnd = 2;
        }
        String start = digits.substring(0, keepStart);
        String end = digits.substring(digits.length() - keepEnd);
        StringBuilder masked = new StringBuilder();
        if (hasPlus) masked.append("+");
        masked.append(start);
        masked.append(" •••• ");
        masked.append(end);
        return masked.toString();
    }

    /**
     * Queries Android Contacts Provider on device and resolves query against returned entries.
     * Reads only DISPLAY_NAME and NUMBER columns. Never persists to disk.
     */
    public static ResolutionResult queryAndResolve(Context context, String query) {
        if (context == null) {
            return ResolutionResult.noMatch(query);
        }
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return ResolutionResult.permissionDenied(query);
        }
        if (query == null || query.trim().isEmpty()) {
            return ResolutionResult.noMatch("");
        }

        List<ContactEntry> entries = new ArrayList<>();
        String[] projection = new String[] {
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
        };

        try (Cursor cursor = context.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                null
        )) {
            if (cursor != null) {
                int nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                int numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                while (cursor.moveToNext()) {
                    String name = nameIdx >= 0 ? cursor.getString(nameIdx) : "";
                    String number = numberIdx >= 0 ? cursor.getString(numberIdx) : "";
                    if (name != null && !name.trim().isEmpty() && number != null && !number.trim().isEmpty()) {
                        entries.add(new ContactEntry(name, number));
                    }
                }
            }
        } catch (Exception e) {
            return ResolutionResult.noMatch(query);
        }

        return resolve(query, entries);
    }
}
