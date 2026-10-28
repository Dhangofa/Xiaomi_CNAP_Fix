package com.dhangofa.xiaomicnapfix;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.View;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Makes Xiaomi/MIUI/HyperOS InCallUI (com.android.incallui) show the network CNAP name.
 *
 * Why it is needed (from the dexdump of this InCallUI build):
 *  - Call.getCnapName() -> telecom Call.Details.getCallerDisplayName()  (works, Google dialer uses the same)
 *  - ContactInfoCache.populateCacheEntry() only honours CNAP when the CallerInfo it receives still carries
 *    cnapName/name. The CallerInfo that comes back from the contact / call-log / yellow-page lookups is a new
 *    object without it, so ContactInfoCache.buildEntry() yields ContactCacheEntry.name == null and the UI
 *    falls back to the raw number.
 *
 * Hook 1 (primary):  ContactInfoCache.buildEntry(...)   -> fills ContactCacheEntry.name with the CNAP name.
 *                    Every consumer that reads entry.name benefits: call card, heads-up/notification title
 *                    (StatusBarNotifier.getContentTitle), hold-call name, dialpad name, bubble, car mode,
 *                    conference rows, relay, screen share.
 * Hook 2 (fallback): CallCardInfo.createCallCardInfo(...) -> if the model still shows the bare number, put the
 *                    CNAP name in and clear CallCardInfo.nameIsNumber (otherwise the UI hides the number line).
 */
    public class CnapFixHook implements IXposedHookLoadPackage {

    private static final String TAG = "XiaomiCNAPFix: ";
    private static final boolean DEBUG = true;   // set false once it works (logs are masked, but still noisy)

    private static final String PKG = "com.android.incallui";
    private static final String CLS_CALL = "com.android.incallui.Call";
    private static final String CLS_CALL_LIST = "com.android.incallui.CallList";
    private static final String CLS_CALLER_INFO = "com.android.incallui.CallerInfo";
    private static final String CLS_CACHE = "com.android.incallui.ContactInfoCache";
    private static final String CLS_ENTRY = "com.android.incallui.ContactInfoCache$ContactCacheEntry";
    private static final String CLS_CARD_INFO = "com.android.incallui.model.CallCardInfo";

    // Telecom (runs inside the system process, uid 1000, holds the call-log permissions)
    private static final String PKG_TELECOM = "com.android.server.telecom";
    private static final String CLS_LOG_TASK = "com.android.server.telecom.CallLogManager$LogCallAsyncTask";

    // Xiaomi Phone/Contacts app: Recents list row (com.android.contacts, dump of this build)
    private static final String PKG_CONTACTS = "com.android.contacts";
    private static final String CLS_CALL_VH = "com.android.contacts.dialer.list.VH.DialerCallVH";

    /** Missed-call rows get a repeat count appended to the title: "+91... (3)". */
    private static final Pattern COUNT_SUFFIX = Pattern.compile("\\s*\\(\\d+\\)\\s*$");

    private static volatile int rejectLogBudget = 40;

    /** call-log _id -> CNAP name stored in call_log.name (positive hits only). */
    private static final LruCache<Long, String> NAME_CACHE = new LruCache<Long, String>(256);

    // android.telecom.TelecomManager.PRESENTATION_RESTRICTED
    private static final int PRESENTATION_RESTRICTED = 2;

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) {
        if (PKG.equals(lpparam.packageName)) {
            log("loaded into " + lpparam.packageName
                    + " (process " + lpparam.processName + ")");

            hookBuildEntry(lpparam);
            hookCreateCallCardInfo(lpparam);

        } else if (PKG_CONTACTS.equals(lpparam.packageName)) {
            log("loaded into " + lpparam.packageName
                    + " (process " + lpparam.processName + ")");

            hookRecentsTitle(lpparam);

        } else if (PKG_TELECOM.equals(lpparam.packageName)) {
            log("loaded into " + lpparam.packageName
                    + " (process " + lpparam.processName + ")");

            hookTelecomCallLog(lpparam);

        } else if ("android".equals(lpparam.packageName)) {
            log("system framework scope active (process " + lpparam.processName + ")");
        }
    }

    // ------------------------------------------------------------------------------------------
    // Hook 1: ContactInfoCache.buildEntry(Context, String callId, CallerInfo, int presentation, boolean isIncoming)
    //         private, returns ContactCacheEntry. Used by findInfoQueryComplete, the call-log callback and the
    //         yellow-page callback, i.e. every path that (re)builds the cached entry.
    // ------------------------------------------------------------------------------------------
    private void hookBuildEntry(final LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    CLS_CACHE,
                    lpparam.classLoader,
                    "buildEntry",
                    Context.class,
                    String.class,
                    CLS_CALLER_INFO,
                    int.class,
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object entry = param.getResult();
                                String callId = (String) param.args[1];

                                if (entry == null || callId == null) {
                                    return;
                                }

                                if (XposedHelpers.getBooleanField(
                                        entry, "contactExists")) {
                                    return;
                                }

                                if (XposedHelpers.getBooleanField(
                                        entry, "isEmergencyNumber")) {
                                    return;
                                }

                                if (XposedHelpers.getBooleanField(
                                        entry, "isVioceMailNumber")) { // sic (Xiaomi typo)
                                    return;
                                }

                                Object call = findCallById(
                                        lpparam.classLoader,
                                        callId
                                );

                                if (call == null) {
                                    debug("buildEntry: no Call for id " + callId);
                                    return;
                                }

                                String cnap = readCnapName(call);

                                if (cnap == null) {
                                    debug("buildEntry: no usable CNAP for call " + callId);
                                    return;
                                }

                                // On-screen name: only fill an empty one (a real name wins).
                                String existingName =
                                        (String) XposedHelpers.getObjectField(entry, "name");
                                boolean nameWasEmpty = TextUtils.isEmpty(existingName);

                                if (nameWasEmpty) {
                                    XposedHelpers.setObjectField(entry, "name", cnap);
                                }

                                String number = (String) XposedHelpers.callMethod(call, "getNumber");

                                debug("buildEntry: CNAP '" + cnap + "' nameWasEmpty=" + nameWasEmpty
                                        + " (number " + mask(number) + ")");

                            } catch (Throwable t) {
                                log("buildEntry hook error: " + t);
                            }
                        }
                    }
            );

            log("hooked ContactInfoCache.buildEntry");

        } catch (Throwable t) {
            log("FAILED to hook ContactInfoCache.buildEntry: " + t);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Hook 2: CallCardInfo.createCallCardInfo(Call, ContactCacheEntry)   (public static)
    // ------------------------------------------------------------------------------------------
    private void hookCreateCallCardInfo(final LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    CLS_CARD_INFO,
                    lpparam.classLoader,
                    "createCallCardInfo",
                    CLS_CALL,
                    CLS_ENTRY,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object call = param.args[0];
                                Object entry = param.args[1];
                                Object info = param.getResult();

                                if (call == null || entry == null || info == null) {
                                    return;
                                }

                                if (XposedHelpers.getBooleanField(
                                        info, "isEmergencyNumber")) {
                                    return;
                                }

                                if (XposedHelpers.getBooleanField(
                                        entry, "contactExists")) {
                                    return;
                                }

                                if (XposedHelpers.getBooleanField(
                                        entry, "isEmergencyNumber")) {
                                    return;
                                }

                                if (XposedHelpers.getBooleanField(
                                        entry, "isVioceMailNumber")) {
                                    return;
                                }

                                String name =
                                        (String) XposedHelpers.getObjectField(
                                                info,
                                                "name"
                                        );

                                boolean nameIsNumber =
                                        XposedHelpers.getBooleanField(
                                                info,
                                                "nameIsNumber"
                                        );

                                // Only touch the model when it is showing nothing / just the number.
                                // (relay names, anti-fraud label, FDN names etc. are neither.)
                                if (!TextUtils.isEmpty(name) && !nameIsNumber) {
                                    return;
                                }

                                String cnap = readCnapName(call);

                                if (cnap == null) {
                                    return;
                                }

                                XposedHelpers.setObjectField(
                                        info,
                                        "name",
                                        cnap
                                );

                                XposedHelpers.setBooleanField(
                                        info,
                                        "nameIsNumber",
                                        false
                                );

                                debug("createCallCardInfo: name <- CNAP '" + cnap + "'");

                            } catch (Throwable t) {
                                log("createCallCardInfo hook error: " + t);
                            }
                        }
                    }
            );

            log("hooked CallCardInfo.createCallCardInfo");

        } catch (Throwable t) {
            log("FAILED to hook CallCardInfo.createCallCardInfo: " + t);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Hook 3 (com.android.contacts): Recents row title.
    //
    // DialerCallVH.c(String number) builds the row title. For a number without a contact
    // (DialerItemVM.e <= 0, e = cursor column 1) it falls through to "append the raw number";
    // call_log.name is never read (CalllogQuery / the T9 smart-dialer cursor have no such use).
    // After c() ran we replace a bare-number title with the CNAP name stored in call_log.name
    // for that call (DialerItemVM.d = call-log _id), and show the number on the secondary line.
    //
    // Obfuscated names below are valid for the dumped build only:
    //   DialerCallVH.g3 = DialerItemVM, .O2 = title TextView, .S2 = number TextView (lazy, V()),
    //   .I2 = Context, .k3 = number presentation allowed; DialerItemVM.d/e = long.
    // ------------------------------------------------------------------------------------------
    private void hookRecentsTitle(final LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    CLS_CALL_VH, lpparam.classLoader, "c", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                applyCnapToRecentsRow(param.thisObject, (String) param.args[0]);
                            } catch (Throwable t) {
                                log("Recents hook error: " + t);
                            }
                        }
                    });
            log("hooked DialerCallVH.c(String)");
        } catch (Throwable t) {
            log("FAILED to hook DialerCallVH.c(String): " + t);
        }
    }

    private static void applyCnapToRecentsRow(Object vh, String number) {
        if (vh == null || TextUtils.isEmpty(number)) return;

        // Number presentation must be allowed (otherwise the row shows "Private"/"Unknown").
        if (!XposedHelpers.getBooleanField(vh, "k3")) {
            reject("presentation not allowed", -1, -1, null, number);
            return;
        }

        Object vm = XposedHelpers.getObjectField(vh, "g3");
        if (vm == null) return;

        long callId = XposedHelpers.getLongField(vm, "d");
        long contactKey = XposedHelpers.getLongField(vm, "e");

        // A real contact (or yellow-page hit) already has its own title.
        if (contactKey > 0) {
            reject("contact key > 0", callId, contactKey, null, number);
            return;
        }

        TextView title = (TextView) XposedHelpers.getObjectField(vh, "O2");
        if (title == null) return;

        // Missed calls: title is "<number> (<count>)". Keep the count, replace the number.
        String shown = String.valueOf(title.getText());
        String suffix = "";
        Matcher m = COUNT_SUFFIX.matcher(shown);
        if (m.find()) {
            suffix = shown.substring(m.start());
            shown = shown.substring(0, m.start());
        }

        // Only replace a title that is just the number; tags, FDN names, "Unknown" etc. stay.
        String numberDigits = digits(number);
        if (numberDigits.isEmpty() || !numberDigits.equals(digits(shown))) {
            reject("title is not the bare number", callId, contactKey, shown, number);
            return;
        }

        Context ctx = (Context) XposedHelpers.getObjectField(vh, "I2");
        String cnap = lookupStoredCnap(ctx, callId, number);
        if (cnap == null) {
            reject("no stored name in call_log", callId, contactKey, shown, number);
            return;
        }

        title.setText(cnap + suffix);
        title.setContentDescription(cnap + suffix);

        // Number on the secondary line, like a normal contact row.
        try {
            XposedHelpers.callMethod(vh, "V"); // inflates S2 if it is not there yet
            TextView secondary = (TextView) XposedHelpers.getObjectField(vh, "S2");
            if (secondary != null) {
                secondary.setText(number);
                secondary.setVisibility(View.VISIBLE);
            }
        } catch (Throwable t) {
            debug("secondary number line failed: " + t);
        }

        debug("Recents: title <- CNAP '" + cnap + "'" + suffix + " (call " + callId
                + ", number " + mask(number) + ")");
    }

    private static void reject(String reason, long callId, long contactKey, String shown, String number) {
        if (!DEBUG || rejectLogBudget <= 0) return;
        rejectLogBudget--;
        debug("Recents skip: " + reason + " [call " + callId + ", key " + contactKey
                + ", title '" + (shown == null ? "-" : mask(shown)) + "', number " + mask(number) + "]");
    }

    /** call_log.name of one row, if it is a real name (not empty, not just the number). */
    private static String lookupStoredCnap(Context ctx, long callId, String number) {
        if (ctx == null || callId <= 0) return null;

        String cached = NAME_CACHE.get(callId);
        if (cached != null) return cached;

        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(
                    CallLog.Calls.CONTENT_URI,
                    new String[]{CallLog.Calls.CACHED_NAME},
                    CallLog.Calls._ID + "=?",
                    new String[]{String.valueOf(callId)},
                    null);

            if (c != null && c.moveToFirst()) {
                String name = sanitizeCnap(c.getString(0));
                if (name != null && !digits(name).equals(digits(number))) {
                    NAME_CACHE.put(callId, name);
                    return name;
                }
            }
        } catch (Throwable t) {
            log("call_log name lookup failed: " + t);
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // Hook 4 (Telecom, system process): stamp the CNAP name onto the call-log row it just inserted.
    //
    // CallLogManager$LogCallAsyncTask.doInBackground(AddCallArgs[]) -> Uri[]: on MIUI builds the row
    // is inserted by MiTelecommStub.addCall(Context, AddCallParams, Call), otherwise by
    // CallLog.Calls.addCall. AddCallArgs.call is the Telecom Call, whose getCallerDisplayName() is
    // the CNAP name. Telecom can write the call log itself, so no other process is involved.
    // ------------------------------------------------------------------------------------------
    private void hookTelecomCallLog(final LoadPackageParam lpparam) {
        try {
            Class<?> task = XposedHelpers.findClassIfExists(CLS_LOG_TASK, lpparam.classLoader);
            if (task == null) {
                log("Telecom LogCallAsyncTask not found");
                return;
            }

            boolean hooked = false;
            for (Method m : task.getDeclaredMethods()) {
                if (!m.getName().equals("doInBackground") || m.isBridge() || m.isSynthetic()) continue;

                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            stampCnapOnLoggedCalls(param.args, param.getResult());
                        } catch (Throwable t) {
                            log("Telecom doInBackground hook error: " + t);
                        }
                    }
                });
                hooked = true;
                log("hooked Telecom CallLogManager.LogCallAsyncTask.doInBackground");
            }

            if (!hooked) log("Telecom: no doInBackground method found to hook");
        } catch (Throwable t) {
            log("FAILED to hook Telecom call logging: " + t);
        }
    }

    private static void stampCnapOnLoggedCalls(Object[] hookArgs, Object result) {
        if (hookArgs == null || hookArgs.length == 0 || !(hookArgs[0] instanceof Object[])
                || !(result instanceof Object[])) {
            return;
        }

        Object[] callArgs = (Object[]) hookArgs[0];
        Object[] uris = (Object[]) result;

        for (int i = 0; i < callArgs.length && i < uris.length; i++) {
            Object args = callArgs[i];
            Object uri = uris[i];
            if (args == null || !(uri instanceof Uri)) continue;

            Context ctx = (Context) XposedHelpers.getObjectField(args, "context");
            Object telecomCall = XposedHelpers.getObjectField(args, "call");
            if (ctx == null || telecomCall == null) continue;

            String cnap = sanitizeCnap(
                    (String) XposedHelpers.callMethod(
                            telecomCall,
                            "getCallerDisplayName"
                    )
            );
            if (cnap == null) continue;

            int presentation = (Integer) XposedHelpers.callMethod(
                    telecomCall,
                    "getCallerDisplayNamePresentation"
            );
            if (presentation == PRESENTATION_RESTRICTED) continue;

            stampRow(ctx, (Uri) uri, cnap);
        }
    }

    private static void stampRow(Context ctx, Uri rowUri, String cnap) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(
                    rowUri,
                    new String[]{
                            CallLog.Calls.CACHED_NAME,
                            CallLog.Calls.TYPE,
                            CallLog.Calls.NUMBER
                    },
                    null,
                    null,
                    null
            );

            if (c == null || !c.moveToFirst()) {
                debug("Telecom: inserted row not readable: " + rowUri);
                return;
            }

            String existing = c.getString(0);
            int type = c.getInt(1);
            String number = c.getString(2);

            // Contacts (or anything Telecom already named) win; CNAP only matters for incoming calls.
            if (!TextUtils.isEmpty(existing)) {
                debug("Telecom: row " + rowUri + " already named, skipping");
                return;
            }
            if (type != CallLog.Calls.INCOMING_TYPE
                    && type != CallLog.Calls.MISSED_TYPE
                    && type != CallLog.Calls.REJECTED_TYPE) {
                return;
            }
            if (!digits(cnap).isEmpty() && digits(cnap).equals(digits(number))) {
                return;
            }

            ContentValues values = new ContentValues();
            values.put(CallLog.Calls.CACHED_NAME, cnap);
            int updated = ctx.getContentResolver().update(
                    rowUri,
                    values,
                    null,
                    null
            );

            debug("Telecom: call-log row " + rowUri + " named '" + cnap + "' (updated " + updated
                    + ", number " + mask(number) + ", type " + type + ")");
        } catch (Throwable t) {
            log("Telecom: stamping call-log row failed: " + t);
        } finally {
            if (c != null) c.close();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /** CallList.getInstance().getCallById(id) -- both exist in this build (seen in the dump). */
    private static Object findCallById(ClassLoader classLoader, String callId) {
        try {
            Class<?> callListClass = XposedHelpers.findClass(
                    CLS_CALL_LIST,
                    classLoader
            );

            Object callList = XposedHelpers.callStaticMethod(
                    callListClass,
                    "getInstance"
            );

            if (callList == null) {
                return null;
            }

            return XposedHelpers.callMethod(
                    callList,
                    "getCallById",
                    callId
            );

        } catch (Throwable t) {
            log("findCallById failed: " + t);
            return null;
        }
    }

    /**
     * Returns the CNAP name of an InCallUI Call, or null if there is none / it must not be shown.
     * Call.getCnapName() == telecom Call.Details.getCallerDisplayName().
     */
    private static String readCnapName(Object call) {
        if (call == null) {
            return null;
        }

        try {
            String cnap = (String) XposedHelpers.callMethod(
                    call,
                    "getCnapName"
            );

            cnap = sanitizeCnap(cnap);

            if (cnap == null) {
                return null;
            }

            int presentation = (Integer) XposedHelpers.callMethod(
                    call,
                    "getCnapNamePresentation"
            );

            if (presentation == PRESENTATION_RESTRICTED) {
                return null;
            }

            // Some networks just echo the number as the "name" - that is not a CNAP name.
            String number = (String) XposedHelpers.callMethod(
                    call,
                    "getNumber"
            );

            if (number != null
                    && !digits(cnap).isEmpty()
                    && digits(cnap).equals(digits(number))) {
                return null;
            }

            return cnap;

        } catch (Throwable t) {
            log("readCnapName failed: " + t);
            return null;
        }
    }

    private static String sanitizeCnap(String cnap) {
        if (cnap == null) {
            return null;
        }

        cnap = cnap.trim();

        if (cnap.isEmpty()) {
            return null;
        }

        return cnap.length() > 256
                ? cnap.substring(0, 256)
                : cnap;
    }

    private static String digits(String value) {
        return value == null
                ? ""
                : value.replaceAll("[^0-9+]", "");
    }

    private static String mask(String number) {
        if (number == null) {
            return "null";
        }

        int length = number.length();

        return length <= 4
                ? "****"
                : "****" + number.substring(length - 4);
    }

    private static void log(String message) {
        XposedBridge.log(TAG + message);
    }

    private static void debug(String message) {
        if (DEBUG) {
            XposedBridge.log(TAG + message);
        }
    }
}
