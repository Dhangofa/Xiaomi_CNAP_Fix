package com.dhangofa.xiaomicnapfix;

import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContentProvider;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.os.Build;
import android.provider.CallLog;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.View;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;
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

    // Call-log provider (Telecom's insert goes through it; it holds WRITE_CALL_LOG, InCallUI does not)
    private static final String PKG_CALL_LOG_PROVIDER = "com.android.providers.contacts";
    private static final String CLS_CALL_LOG_PROVIDER =
            "com.android.providers.contacts.CallLogProvider";
    private static final String PROCESS_ACORE = "android.process.acore"; // shared by several providers
    private static final String ACTION_CNAP_READY = "com.dhangofa.xiaomicnapfix.action.CNAP_READY";
    private static final String EXTRA_NUMBER = "com.dhangofa.xiaomicnapfix.extra.NUMBER";
    private static final String EXTRA_CNAP = "com.dhangofa.xiaomicnapfix.extra.CNAP";

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
        } else if (PKG_CALL_LOG_PROVIDER.equals(lpparam.packageName)
                || PROCESS_ACORE.equals(lpparam.processName)) {
            // Several providers (contacts, calendar, user dictionary ...) share android.process.acore,
            // so LSPosed may report another package for that process. Log it, and hook the provider
            // when Android attaches it instead of relying on the package name.
            log("loaded into " + lpparam.packageName
                    + " (process " + lpparam.processName + ", first=" + lpparam.isFirstApplication + ")");

            hookCallLogProvider(lpparam);
            hookProviderAttach();
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

                                // Call history: remember it so the new call-log row gets the name.
                                String number = (String) XposedHelpers.callMethod(call, "getNumber");
                                sendCnap((Context) param.args[0], number, cnap);

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
    // Call-history write path.
    //
    // InCallUI (only READ_CALL_LOG) cannot write the call log, so it tells the call-log provider
    // process (broadcast) which CNAP name belongs to which number. The provider process hook then
    // sets cached_name on Telecom's insert, only when Telecom did not supply a name itself.
    // ------------------------------------------------------------------------------------------
    private static final class Pending {
        final String cnap;
        final long created;

        Pending(String cnap, long created) {
            this.cnap = cnap;
            this.created = created;
        }
    }

    private static final long PENDING_TTL_MS = 4L * 60 * 60 * 1000;
    private static final long RESEND_MS = 20L * 1000;

    /** key = last 10 digits of the number. */
    private static final ConcurrentHashMap<String, Pending> PENDING =
            new ConcurrentHashMap<String, Pending>();
    private static final ConcurrentHashMap<String, Long> LAST_SENT =
            new ConcurrentHashMap<String, Long>();
    private static volatile boolean providerReceiverRegistered;

    private static String tail(String number) {
        String d = digits(number).replace("+", "");
        return d.length() > 10 ? d.substring(d.length() - 10) : d;
    }

    // ---- InCallUI side ------------------------------------------------------------------------
    private static void sendCnap(Context ctx, String number, String cnap) {
        if (ctx == null || TextUtils.isEmpty(number) || TextUtils.isEmpty(cnap)) return;

        String key = tail(number);
        if (key.isEmpty()) return;

        long now = System.currentTimeMillis();
        Long last = LAST_SENT.get(key + "|" + cnap);
        if (last != null && now - last < RESEND_MS) return; // buildEntry runs several times per call
        LAST_SENT.put(key + "|" + cnap, now);

        Intent intent = new Intent(ACTION_CNAP_READY);
        intent.setPackage(PKG_CALL_LOG_PROVIDER);
        intent.putExtra(EXTRA_NUMBER, number);
        intent.putExtra(EXTRA_CNAP, cnap);
        ctx.sendBroadcast(intent);

        debug("sent CNAP to call-log provider, number=" + mask(number));
    }

    // ---- call-log provider side ---------------------------------------------------------------
    private static volatile boolean providerHooksInstalled;
    private static volatile boolean attachHooked;

    /** Package-based path: works when LSPosed reports com.android.providers.contacts itself. */
    private void hookCallLogProvider(final LoadPackageParam lpparam) {
        try {
            Class<?> providerClass = XposedHelpers.findClassIfExists(
                    CLS_CALL_LOG_PROVIDER, lpparam.classLoader);
            if (providerClass == null) {
                debug("CallLogProvider not in classloader of " + lpparam.packageName);
                return;
            }
            installProviderHooks(providerClass);
        } catch (Throwable t) {
            log("FAILED to hook CallLogProvider: " + t);
        }
    }

    /** Process-based path: hook ContentProvider.attachInfo and pick out CallLogProvider. */
    private synchronized void hookProviderAttach() {
        if (attachHooked) return;
        try {
            XposedHelpers.findAndHookMethod(
                    ContentProvider.class, "attachInfo", Context.class, ProviderInfo.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                ProviderInfo info = (ProviderInfo) param.args[1];
                                if (info == null || !CLS_CALL_LOG_PROVIDER.equals(info.name)) return;

                                log("CallLogProvider attached (authority " + info.authority + ")");
                                installProviderHooks(param.thisObject.getClass());
                                registerProviderReceiver((Context) param.args[0]);
                            } catch (Throwable t) {
                                log("attachInfo hook error: " + t);
                            }
                        }
                    });
            attachHooked = true;
            log("hooked ContentProvider.attachInfo");
        } catch (Throwable t) {
            log("FAILED to hook ContentProvider.attachInfo: " + t);
        }
    }

    private synchronized void installProviderHooks(Class<?> providerClass) {
        if (providerHooksInstalled) return;
        providerHooksInstalled = true;

        XposedBridge.hookAllMethods(providerClass, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    registerProviderReceiver(
                            (Context) XposedHelpers.callMethod(param.thisObject, "getContext"));
                } catch (Throwable t) {
                    log("CallLogProvider.onCreate hook error: " + t);
                }
            }
        });

        hookProviderWriters(providerClass);
        log("installed call-log provider hooks on " + providerClass.getName());
    }

    private static synchronized void registerProviderReceiver(Context context) {
        if (providerReceiverRegistered || context == null) return;

        final Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                try {
                    if (intent == null || !ACTION_CNAP_READY.equals(intent.getAction())) return;

                    // Android 14+: accept only the InCallUI package as sender (best effort).
                    if (Build.VERSION.SDK_INT >= 34) {
                        try {
                            int from = (Integer) XposedHelpers.callMethod(this, "getSentFromUid");
                            int expected = app.getPackageManager().getPackageUid(PKG, 0);
                            if (from != -1 && from != expected) {
                                debug("ignored CNAP broadcast from uid " + from);
                                return;
                            }
                        } catch (Throwable ignored) {
                            // cannot verify: accept
                        }
                    }

                    String number = intent.getStringExtra(EXTRA_NUMBER);
                    String cnap = sanitizeCnap(intent.getStringExtra(EXTRA_CNAP));
                    String key = tail(number);

                    if (key.isEmpty() || cnap == null || digits(cnap).equals(digits(number))) return;

                    Pending old = PENDING.get(key);
                    PENDING.put(key, new Pending(cnap,
                            old != null ? old.created : System.currentTimeMillis()));

                    debug("provider cached CNAP for " + mask(number));
                } catch (Throwable t) {
                    log("provider receiver error: " + t);
                }
            }
        };

        IntentFilter filter = new IntentFilter(ACTION_CNAP_READY);
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            app.registerReceiver(receiver, filter);
        }

        providerReceiverRegistered = true;
        log("registered call-log provider CNAP receiver");
    }

    /** Hook only CallLogProvider's own, non-abstract writers (never framework ContentProvider). */
    private void hookProviderWriters(Class<?> providerClass) {
        boolean hooked = false;
        StringBuilder seen = new StringBuilder();

        for (final Method m : providerClass.getDeclaredMethods()) {
            String n = m.getName();
            if (!n.equals("insert") && !n.equals("insertInTransaction") && !n.equals("bulkInsert")) {
                continue;
            }
            seen.append(n).append(' ');
            if (Modifier.isAbstract(m.getModifiers())) continue;

            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            injectPendingCnap(findContentValues(param.args));
                        } catch (Throwable t) {
                            log(m.getName() + " hook error: " + t);
                        }
                    }
                });
                hooked = true;
                log("hooked call-log writer: " + n);
            } catch (Throwable t) {
                log("FAILED to hook call-log writer " + n + ": " + t);
            }
        }

        if (!hooked) log("no call-log writer hooked; saw: [" + seen + "]");
    }

    private static ContentValues findContentValues(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof ContentValues) return (ContentValues) arg;
        }
        return null;
    }

    private static void injectPendingCnap(ContentValues values) {
        if (values == null) return;

        String number = values.getAsString(CallLog.Calls.NUMBER);
        Integer type = values.getAsInteger(CallLog.Calls.TYPE);
        String key = tail(number);

        debug("call-log insert seen: number=" + mask(number) + ", type=" + type
                + ", pending=" + PENDING.size());

        if (key.isEmpty() || type == null) return;
        // incoming (1), missed (3), rejected (5)
        if (type != 1 && type != 3 && type != 5) return;

        Pending p = PENDING.get(key);
        if (p == null) return;

        if (System.currentTimeMillis() - p.created > PENDING_TTL_MS) {
            PENDING.remove(key);
            return;
        }

        if (!TextUtils.isEmpty(values.getAsString(CallLog.Calls.CACHED_NAME))) {
            debug("call-log insert already has a name for " + mask(number));
            PENDING.remove(key);
            return;
        }

        values.put(CallLog.Calls.CACHED_NAME, p.cnap);
        PENDING.remove(key);
        debug("call-log cached_name <- CNAP '" + p.cnap + "' (number " + mask(number)
                + ", type " + type + ")");
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
                                
