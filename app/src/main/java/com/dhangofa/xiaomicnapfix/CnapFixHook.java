package com.dhangofa.xiaomicnapfix;

import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import java.util.Set;
import android.provider.CallLog;
import android.text.TextUtils;
import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final boolean DEBUG = false;  // true = verbose logs (masked numbers); costs I/O, keep off
    private static final boolean DIAG = true;    // TEMPORARY: reports which activity opens the Unknown contact page

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
    private static final String CLS_TITLE_VIEW = "com.android.contacts.detail.ContactDetailTitleView";
    private static final String CLS_UNKNOWN_ACTIVITY =
            "com.android.contacts.activities.UnknownContactActivity";
    // Intent extra keys read by UnknownContactActivity.onCreate (public contract, not obfuscated).
    private static final String EXTRA_NUMBER = "number";
    private static final String EXTRA_NUMBER_ALT = "data1";
    private static final String EXTRA_NAME = "name";

    /** last-10-digits of a number -> newest name stored in call_log.name for it. */
    private static final ConcurrentHashMap<String, String> NAMES =
            new ConcurrentHashMap<String, String>();
    private static volatile long lastNamesLoad;
    private static final long NAMES_REFRESH_MS = 5L * 1000;  // at most one reload per 5 s on a miss
    private static final int NAMES_MAX_ROWS = 500;

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
            hookUnknownContactTitle(lpparam);
            hookDetailTitleView(lpparam);
            if (DIAG) hookActivityWatcher();

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
    // Hook 3 (com.android.contacts): Recents row title - written to survive app updates.
    //
    // The Contacts app is obfuscated: method and field names change with every build. So nothing here
    // depends on them. What is used instead:
    //   * the class name DialerCallVH and the *type* DialerItemVM (kept by the obfuscator);
    //   * methods found by SHAPE: the bind method (first parameter is a DialerItemVM) and the
    //     number-title setters ((String) -> void);
    //   * the title is found in the row's view tree: the visible TextView with the biggest text size
    //     (ties: first in layout order). A row of an unknown number shows the bare number there;
    //     a contact row shows the contact's name, so it is left alone.
    // If the shape is not found, one log line says so and the screen is simply left unchanged.
    // ------------------------------------------------------------------------------------------
    private void hookRecentsTitle(final LoadPackageParam lpparam) {
        try {
            Class<?> vhClass = XposedHelpers.findClassIfExists(CLS_CALL_VH, lpparam.classLoader);
            if (vhClass == null) {
                log("Recents: DialerCallVH not found (Contacts app changed?)");
                return;
            }

            XC_MethodHook callback = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        applyToRecentsRow(param.thisObject);
                    } catch (Throwable t) {
                        log("Recents hook error: " + t);
                    }
                }
            };

            int hooked = 0;
            for (Method m : vhClass.getDeclaredMethods()) {
                int mod = m.getModifiers();
                if (m.isBridge() || m.isSynthetic() || Modifier.isStatic(mod) || Modifier.isAbstract(mod)
                        || m.getReturnType() != void.class) {
                    continue;
                }
                Class<?>[] p = m.getParameterTypes();
                boolean bind = p.length > 0 && p[0].getName().endsWith(".DialerItemVM");
                boolean numberSetter = p.length == 1 && p[0] == String.class;
                if (!bind && !numberSetter) continue;

                XposedBridge.hookMethod(m, callback);
                hooked++;
            }

            if (hooked == 0) {
                log("Recents: no row binder found in DialerCallVH (Contacts app changed?)");
            } else {
                log("hooked " + hooked + " DialerCallVH row binder method(s)");
            }
        } catch (Throwable t) {
            log("FAILED to hook Recents rows: " + t);
        }
    }

    private static final int MAX_ROW_VIEWS = 120;
    private static final int MAX_ROW_ASCENT = 6;

    private static void applyToRecentsRow(Object vh) {
        if (vh == null) return;

        TextView any = firstTextViewField(vh);
        if (any == null) return;

        View root = rowRoot(any);
        if (root == null) return;

        TextView title = biggestVisibleText(root);
        if (title == null) return;

        // Split "<number> (3)" (missed-call repeat count) into number and suffix.
        String text = String.valueOf(title.getText()).trim();
        String suffix = "";
        int open = text.lastIndexOf('(');
        if (open > 0 && text.endsWith(")") && allDigits(text, open + 1, text.length() - 1)) {
            int cut = open;
            while (cut > 0 && text.charAt(cut - 1) == ' ') cut--;
            suffix = text.substring(cut);
            text = text.substring(0, cut);
        }

        // Only a bare number is replaced: names, spam tags, "Unknown", "Private" etc. are left alone.
        if (!looksLikeNumber(text)) return;

        String name = lookupName(title.getContext(), text);
        if (name == null) return;

        title.setText(name + suffix);
        title.setContentDescription(name + suffix);
        showNumberLineIfKnown(vh, text);

        if (DEBUG) debug("Recents: title <- CNAP '" + name + "'" + suffix + " (number " + mask(text) + ")");
    }

    // ---- row/view discovery (reflection handles cached per class) ------------------------------
    private static Field[] textViewFields;

    private static synchronized Field[] textViewFieldsOf(Class<?> c) {
        if (textViewFields != null) return textViewFields;
        java.util.ArrayList<Field> list = new java.util.ArrayList<Field>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (TextView.class.isAssignableFrom(f.getType()) && !Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    list.add(f);
                }
            }
        }
        textViewFields = list.toArray(new Field[0]);
        return textViewFields;
    }

    private static TextView firstTextViewField(Object vh) {
        try {
            for (Field f : textViewFieldsOf(vh.getClass())) {
                Object v = f.get(vh);
                if (v instanceof TextView) return (TextView) v;
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return null;
    }

    /**
     * The row's own root view. A holder that is not attached yet has no parent; an attached one
     * has the RecyclerView/ListView as parent. Anything else is not trusted (never touch other UI).
     */
    private static View rowRoot(View v) {
        for (int depth = 0; depth < MAX_ROW_ASCENT; depth++) {
            ViewParent parent = v.getParent();
            if (parent == null) return v;
            String cls = parent.getClass().getName();
            if (cls.contains("RecyclerView") || cls.contains("ListView")) return v;
            if (!(parent instanceof View)) return null;
            v = (View) parent;
        }
        return null;
    }

    private static TextView biggestVisibleText(View root) {
        int[] budget = {MAX_ROW_VIEWS};
        TextView[] best = {null};
        float[] bestSize = {-1f};
        scan(root, budget, best, bestSize);
        return budget[0] < 0 ? null : best[0]; // too many views: not a list row, do nothing
    }

    private static void scan(View v, int[] budget, TextView[] best, float[] bestSize) {
        if (--budget[0] < 0 || v.getVisibility() != View.VISIBLE) return;

        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            if (tv.getText() != null && tv.getText().length() > 0 && tv.getTextSize() > bestSize[0]) {
                best[0] = tv;
                bestSize[0] = tv.getTextSize();
            }
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount() && budget[0] >= 0; i++) scan(g.getChildAt(i), budget, best, bestSize);
        }
    }

    // ---- text helpers (allocation-light) ---------------------------------------------------------
    private static boolean allDigits(String s, int from, int to) {
        if (from >= to) return false;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** "+91 86178 91191", "08617891191", "(0861) 789-1191": digits plus + space - ( ) . only, 5+ digits. */
    private static boolean looksLikeNumber(String s) {
        int digits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') digits++;
            else if (c != '+' && c != ' ' && c != '-' && c != '(' && c != ')' && c != '.') return false;
        }
        return digits >= 5;
    }

    /** Allocation-free "same digits (and +) in the same order", ignoring spaces, dashes, brackets. */
    private static boolean sameNumber(CharSequence a, CharSequence b) {
        int i = 0, j = 0, na = a.length(), nb = b.length();
        boolean any = false;
        while (true) {
            while (i < na && !isNumberChar(a.charAt(i))) i++;
            while (j < nb && !isNumberChar(b.charAt(j))) j++;
            if (i >= na || j >= nb) return any && i >= na && j >= nb;
            if (a.charAt(i++) != b.charAt(j++)) return false;
            any = true;
        }
    }

    private static boolean isNumberChar(char c) {
        return (c >= '0' && c <= '9') || c == '+';
    }

    // ---- optional number line (only where the tested build's helper names still exist) -----------
    private static void showNumberLineIfKnown(Object vh, String number) {
        try {
            Field s2 = XposedHelpers.findFieldIfExists(vh.getClass(), "S2");
            Method v = XposedHelpers.findMethodExactIfExists(vh.getClass(), "V");
            if (s2 == null || v == null) return; // other build: the number stays on the detail page
            v.invoke(vh); // inflates the secondary line if needed
            Object tv = s2.get(vh);
            if (tv instanceof TextView) {
                ((TextView) tv).setText(number);
                ((TextView) tv).setVisibility(View.VISIBLE);
            }
        } catch (Throwable ignored) {
            // cosmetic only
        }
    }

    // ---- name store ----------------------------------------------------------------------------
    /**
     * Newest stored call_log.name for this number (matched on its last 10 digits). Served from memory;
     * one batched query refills the map, at most once per NAMES_REFRESH_MS and only on a miss.
     */
    private static String lookupName(Context ctx, String number) {
        String key = tail(number);
        if (ctx == null || key.isEmpty()) return null;

        String name = NAMES.get(key);
        long now = System.currentTimeMillis();
        if (name == null && now - lastNamesLoad > NAMES_REFRESH_MS) {
            loadNames(ctx, now);
            name = NAMES.get(key);
        }

        if (name == null || sameNumber(name, number)) return null;
        return name;
    }

    private static synchronized void loadNames(Context ctx, long now) {
        lastNamesLoad = now;

        Map<String, String> fresh = new HashMap<String, String>();
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(
                    CallLog.Calls.CONTENT_URI,
                    new String[]{CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME},
                    CallLog.Calls.CACHED_NAME + " IS NOT NULL AND " + CallLog.Calls.CACHED_NAME + " != ''",
                    null,
                    CallLog.Calls.DATE + " DESC");

            int rows = 0;
            while (c != null && c.moveToNext() && rows++ < NAMES_MAX_ROWS) {
                String key = tail(c.getString(0));
                String name = sanitizeCnap(c.getString(1));
                if (!key.isEmpty() && name != null && !fresh.containsKey(key)) fresh.put(key, name); // newest wins
            }
        } catch (Throwable t) {
            log("call_log names load failed: " + t);
            return;
        } finally {
            if (c != null) c.close();
        }

        NAMES.clear();
        NAMES.putAll(fresh);
    }

    // ------------------------------------------------------------------------------------------
    // Hook 3b (com.android.contacts): header of the "Unknown contact" detail page - via the intent.
    //
    // UnknownContactActivity.onCreate reads the intent extras "number" and "name"; with an empty name
    // it shows "Unknown contact". onCreate is a framework lifecycle method and the extra keys are the
    // page's input contract, so neither is renamed by the obfuscator. Before onCreate runs we put the
    // stored CNAP name into the "name" extra. (A spam/fraud label that would otherwise win over
    // "Unknown contact" is then replaced by the name; the number line below is unchanged.)
    // ------------------------------------------------------------------------------------------
    private void hookUnknownContactTitle(final LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    CLS_UNKNOWN_ACTIVITY, lpparam.classLoader, "onCreate", Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!CLS_UNKNOWN_ACTIVITY.equals(param.thisObject.getClass().getName())) return;

                                Activity activity = (Activity) param.thisObject;
                                Intent intent = activity.getIntent();
                                if (intent == null) {
                                    if (DIAG) log("DIAG UnknownContact.onCreate: no intent");
                                    return;
                                }

                                String existingName = intent.getStringExtra(EXTRA_NAME);
                                String number = intent.getStringExtra(EXTRA_NUMBER);
                                if (TextUtils.isEmpty(number)) number = intent.getStringExtra(EXTRA_NUMBER_ALT);

                                String name = null;
                                if (TextUtils.isEmpty(existingName) && !TextUtils.isEmpty(number)) {
                                    name = lookupName(activity, number);
                                    if (name != null) intent.putExtra(EXTRA_NAME, name);
                                }

                                if (DIAG) {
                                    log("DIAG UnknownContact.onCreate: extras=" + extraKeys(intent)
                                            + " nameExtra=" + (TextUtils.isEmpty(existingName) ? "empty" : "set")
                                            + " numberExtra=" + (TextUtils.isEmpty(number) ? "MISSING" : "found")
                                            + " lookup=" + (name != null ? "hit" : "miss"));
                                } else if (name != null && DEBUG) {
                                    debug("UnknownContact: name extra <- CNAP '" + name + "' (number " + mask(number) + ")");
                                }
                            } catch (Throwable t) {
                                log("UnknownContact hook error: " + t);
                            }
                        }
                    });
            log("hooked UnknownContactActivity.onCreate");
        } catch (Throwable t) {
            log("FAILED to hook UnknownContactActivity.onCreate: " + t);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Hook 3c (com.android.contacts): the title view itself - independent of which activity hosts it.
    //
    // ContactDetailTitleView.setTitle(String) is where a detail page puts its title. If the page was
    // opened for a number (intent has "number"/"data1", no "name", no contact URI) and a CNAP name is
    // stored for that number, the name is shown. Contact pages carry a contact URI and are skipped.
    // ------------------------------------------------------------------------------------------
    private void hookDetailTitleView(final LoadPackageParam lpparam) {
        try {
            Class<?> titleView = XposedHelpers.findClassIfExists(CLS_TITLE_VIEW, lpparam.classLoader);
            if (titleView == null) {
                log("UnknownContact: ContactDetailTitleView not found (Contacts app changed?)");
                return;
            }

            int hooked = 0;
            for (Method m : titleView.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (!m.getName().equals("setTitle") || Modifier.isStatic(m.getModifiers()) || p.length != 1
                        || (p[0] != String.class && p[0] != CharSequence.class)) {
                    continue;
                }

                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            applyToDetailTitle(param);
                        } catch (Throwable t) {
                            log("UnknownContact title hook error: " + t);
                        }
                    }
                });
                hooked++;
            }

            if (hooked == 0) log("UnknownContact: no setTitle(String) found on ContactDetailTitleView");
            else log("hooked ContactDetailTitleView.setTitle (" + hooked + ")");
        } catch (Throwable t) {
            log("FAILED to hook ContactDetailTitleView: " + t);
        }
    }

    private static void applyToDetailTitle(XC_MethodHook.MethodHookParam param) {
        Activity host = hostActivity(((View) param.thisObject).getContext());
        if (host == null) return;

        Intent intent = host.getIntent();
        if (intent == null) return;

        String number = intent.getStringExtra(EXTRA_NUMBER);
        if (TextUtils.isEmpty(number)) number = intent.getStringExtra(EXTRA_NUMBER_ALT);
        boolean nameExtra = !TextUtils.isEmpty(intent.getStringExtra(EXTRA_NAME));
        boolean contactPage = intent.getData() != null;
        String name = (TextUtils.isEmpty(number) || nameExtra || contactPage) ? null : lookupName(host, number);

        if (DIAG) {
            log("DIAG UnknownContact.setTitle: host=" + host.getClass().getName()
                    + " extras=" + extraKeys(intent) + " contactUri=" + contactPage
                    + " nameExtra=" + nameExtra + " numberExtra=" + !TextUtils.isEmpty(number)
                    + " lookup=" + (name != null ? "hit" : "miss") + " arg='" + param.args[0] + "'");
        }

        if (name != null) param.args[0] = name;
    }

    private static Activity hostActivity(Context c) {
        for (int i = 0; i < 6 && c != null; i++) {
            if (c instanceof Activity) return (Activity) c;
            if (!(c instanceof ContextWrapper)) return null;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    // ---- TEMPORARY diagnostics (DIAG): which Contacts activity is created, and what its intent carries.
    private void hookActivityWatcher() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Activity a = (Activity) param.thisObject;
                        String cls = a.getClass().getName();
                        if (!cls.startsWith("com.android.contacts")) return;

                        Intent i = a.getIntent();
                        log("DIAG activity: " + cls + " extras=" + extraKeys(i)
                                + " action=" + (i == null ? "-" : i.getAction())
                                + " dataScheme=" + (i == null || i.getData() == null ? "none" : i.getData().getScheme()));
                    } catch (Throwable ignored) {
                        // diagnostics only
                    }
                }
            });
            log("DIAG activity watcher installed");
        } catch (Throwable t) {
            log("DIAG watcher failed: " + t);
        }
    }

    /** Extra KEY names only (never values), so logs stay free of numbers and names. */
    private static String extraKeys(Intent i) {
        if (i == null || i.getExtras() == null) return "[]";
        Set<String> keys = i.getExtras().keySet();
        return keys == null ? "[]" : keys.toString();
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

    /** Last 10 digits of a number, to match +91..., 0..., and bare formats in LIKE queries. */
    private static String tail(String number) {
        String d = digits(number).replace("+", "");
        return d.length() > 10 ? d.substring(d.length() - 10) : d;
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
                
