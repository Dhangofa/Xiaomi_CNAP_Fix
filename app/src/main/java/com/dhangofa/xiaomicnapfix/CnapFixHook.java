package com.dhangofa.xiaomicnapfix;

import android.content.Context;
import android.database.Cursor;
import android.provider.CallLog;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.View;
import android.widget.TextView;

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

    // Xiaomi Phone/Contacts app: Recents list row (com.android.contacts, dump of this build)
    private static final String PKG_CONTACTS = "com.android.contacts";
    private static final String CLS_CALL_VH = "com.android.contacts.dialer.list.VH.DialerCallVH";

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

                                // A real name (contact, CNAP already applied, presentation string...) wins.
                                String existingName =
                                        (String) XposedHelpers.getObjectField(entry, "name");

                                if (!TextUtils.isEmpty(existingName)) {
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

                                XposedHelpers.setObjectField(
                                        entry,
                                        "name",
                                        cnap
                                );

                                String number =
                                        (String) XposedHelpers.getObjectField(
                                                entry,
                                                "number"
                                        );

                                debug("buildEntry: entry.name <- CNAP '" + cnap
                                        + "' (number " + mask(number) + ")");

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
        if (!XposedHelpers.getBooleanField(vh, "k3")) return;

        Object vm = XposedHelpers.getObjectField(vh, "g3");
        if (vm == null) return;

        // A real contact (or yellow-page hit) already has its own title.
        if (XposedHelpers.getLongField(vm, "e") > 0) return;

        TextView title = (TextView) XposedHelpers.getObjectField(vh, "O2");
        if (title == null) return;

        // Only replace a title that is just the number; tags, FDN names, "Unknown" etc. stay.
        String shown = String.valueOf(title.getText());
        String numberDigits = digits(number);
        if (numberDigits.isEmpty() || !numberDigits.equals(digits(shown))) return;

        Context ctx = (Context) XposedHelpers.getObjectField(vh, "I2");
        long callId = XposedHelpers.getLongField(vm, "d");
        String cnap = lookupStoredCnap(ctx, callId, number);
        if (cnap == null) return;

        title.setText(cnap);
        title.setContentDescription(cnap);

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

        debug("Recents: title <- CNAP '" + cnap + "' (call " + callId + ", number " + mask(number) + ")");
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
