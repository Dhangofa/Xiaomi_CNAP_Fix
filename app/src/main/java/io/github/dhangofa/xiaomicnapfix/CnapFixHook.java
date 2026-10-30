package io.github.dhangofa.xiaomicnapfix;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CallLog;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Shows the network CNAP (caller name) in the Xiaomi dialer for numbers that are not contacts.
 *
 * Three scopes, all event-driven (no service, thread, receiver, timer or network access):
 *
 *  com.android.incallui         call screen + notification: fill the contact cache entry with the CNAP name.
 *  com.android.server.telecom   (System Framework scope) when a call is logged, store the CNAP name in
 *                               the new call-log row's name column - only if the row has no name yet.
 *  com.android.contacts         Recents rows and the "Unknown contact" detail page show that stored name.
 *
 * The Contacts app is obfuscated (member names change per build), so its hooks locate their targets by
 * class name, parameter types and view-tree shape, and never by renamed member names. If a target is
 * not found the hook is skipped, the screen is left stock, and one log line says so.
 */
public class CnapFixHook implements IXposedHookLoadPackage {

    private static final String TAG = "XiaomiCNAPFix: ";

    private static final String PKG_INCALLUI = "com.android.incallui";
    private static final String PKG_CONTACTS = "com.android.contacts";
    private static final String PKG_TELECOM = "com.android.server.telecom";

    // InCallUI (not obfuscated)
    private static final String CLS_CALL_LIST = "com.android.incallui.CallList";
    private static final String CLS_CALLER_INFO = "com.android.incallui.CallerInfo";
    private static final String CLS_CACHE = "com.android.incallui.ContactInfoCache";
    private static final String CLS_ENTRY = "com.android.incallui.ContactInfoCache$ContactCacheEntry";
    private static final String CLS_CALL = "com.android.incallui.Call";
    private static final String CLS_CARD_INFO = "com.android.incallui.model.CallCardInfo";

    // Telecom (not obfuscated)
    private static final String CLS_LOG_TASK = "com.android.server.telecom.CallLogManager$LogCallAsyncTask";

    // Contacts (obfuscated members; class names and intent extras are stable)
    private static final String CLS_CALL_VH = "com.android.contacts.dialer.list.VH.DialerCallVH";
    private static final String CLS_ITEM_VM_SUFFIX = ".DialerItemVM";
    private static final String CLS_UNKNOWN_ACTIVITY = "com.android.contacts.activities.UnknownContactActivity";
    private static final String CLS_TITLE_VIEW = "com.android.contacts.detail.ContactDetailTitleView";
    private static final String EXTRA_NUMBER = "number";
    private static final String EXTRA_NUMBER_ALT = "data1";
    private static final String EXTRA_NAME = "name";

    // android.telecom.TelecomManager.PRESENTATION_RESTRICTED
    private static final int PRESENTATION_RESTRICTED = 2;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        ClassLoader cl = lpparam.classLoader;
        int ok = 0;
        int total;

        if (PKG_INCALLUI.equals(pkg)) {
            total = 2;
            if (hookBuildEntry(cl)) ok++;
            if (hookCreateCallCardInfo(cl)) ok++;
        } else if (PKG_CONTACTS.equals(pkg)) {
            total = 3;
            if (hookRecentsRows(cl)) ok++;
            if (hookUnknownContactIntent(cl)) ok++;
            if (hookDetailTitleView(cl)) ok++;
        } else if (PKG_TELECOM.equals(pkg)) {
            total = 1;
            if (hookTelecomCallLog(cl)) ok++;
        } else {
            return;
        }

        log(pkg + ": " + ok + "/" + total + " hooks active");
    }

    // ==========================================================================================
    // InCallUI
    // ==========================================================================================

    /**
     * ContactInfoCache.buildEntry(Context, String callId, CallerInfo, int, boolean): every path that
     * (re)builds the cached entry ends here, and the rebuilt entry loses the CNAP name. Fill it back in.
     * Call card, notification, hold-call name, bubble and car mode all read entry.name.
     */
    private static boolean hookBuildEntry(final ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(CLS_CACHE, cl, "buildEntry",
                    Context.class, String.class, CLS_CALLER_INFO, int.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object entry = param.getResult();
                                String callId = (String) param.args[1];
                                if (entry == null || callId == null || isSpecialEntry(entry)) return;
                                if (!TextUtils.isEmpty((String) XposedHelpers.getObjectField(entry, "name"))) return;

                                String cnap = readCnapName(findCallById(cl, callId));
                                if (cnap != null) XposedHelpers.setObjectField(entry, "name", cnap);
                            } catch (Throwable t) {
                                logOnce("buildEntry hook error: " + t);
                            }
                        }
                    });
            return true;
        } catch (Throwable t) {
            log("FAILED to hook ContactInfoCache.buildEntry: " + t);
            return false;
        }
    }

    /**
     * CallCardInfo.createCallCardInfo(Call, ContactCacheEntry): safety net for the call card model. Also
     * clears nameIsNumber, which was computed from the bare number and makes the UI hide the number line.
     */
    private static boolean hookCreateCallCardInfo(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(CLS_CARD_INFO, cl, "createCallCardInfo", CLS_CALL, CLS_ENTRY,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object call = param.args[0];
                                Object entry = param.args[1];
                                Object info = param.getResult();
                                if (call == null || entry == null || info == null || isSpecialEntry(entry)) return;
                                if (XposedHelpers.getBooleanField(info, "isEmergencyNumber")) return;

                                // Only touch a model that shows nothing or just the number.
                                String name = (String) XposedHelpers.getObjectField(info, "name");
                                if (!TextUtils.isEmpty(name) && !XposedHelpers.getBooleanField(info, "nameIsNumber")) return;

                                String cnap = readCnapName(call);
                                if (cnap == null) return;
                                XposedHelpers.setObjectField(info, "name", cnap);
                                XposedHelpers.setBooleanField(info, "nameIsNumber", false);
                            } catch (Throwable t) {
                                logOnce("createCallCardInfo hook error: " + t);
                            }
                        }
                    });
            return true;
        } catch (Throwable t) {
            log("FAILED to hook CallCardInfo.createCallCardInfo: " + t);
            return false;
        }
    }

    /** Contacts, emergency numbers and voicemail keep their own names. */
    private static boolean isSpecialEntry(Object entry) {
        return XposedHelpers.getBooleanField(entry, "contactExists")
                || XposedHelpers.getBooleanField(entry, "isEmergencyNumber")
                || XposedHelpers.getBooleanField(entry, "isVioceMailNumber"); // sic (Xiaomi typo)
    }

    private static Object findCallById(ClassLoader cl, String callId) {
        try {
            Object callList = XposedHelpers.callStaticMethod(XposedHelpers.findClass(CLS_CALL_LIST, cl), "getInstance");
            return callList == null ? null : XposedHelpers.callMethod(callList, "getCallById", callId);
        } catch (Throwable t) {
            return null;
        }
    }

    /** CNAP name of an InCallUI Call (Call.getCnapName == telecom Details.getCallerDisplayName), or null. */
    private static String readCnapName(Object call) {
        if (call == null) return null;
        try {
            String cnap = sanitize((String) XposedHelpers.callMethod(call, "getCnapName"));
            if (cnap == null) return null;
            if ((Integer) XposedHelpers.callMethod(call, "getCnapNamePresentation") == PRESENTATION_RESTRICTED) return null;

            // Some networks just echo the number as the "name": not a CNAP name.
            String number = (String) XposedHelpers.callMethod(call, "getNumber");
            return number != null && sameNumber(cnap, number) ? null : cnap;
        } catch (Throwable t) {
            logOnce("readCnapName failed: " + t);
            return null;
        }
    }

    // ==========================================================================================
    // Telecom (system process): store the name in the call-log row it just inserted
    // ==========================================================================================

    /**
     * CallLogManager$LogCallAsyncTask.doInBackground(AddCallArgs[]) -> Uri[] of the inserted rows. The
     * Telecom Call (AddCallArgs.call) still holds the CNAP name; Telecom runs as the system user, so it
     * can update the row it just wrote.
     */
    private static boolean hookTelecomCallLog(ClassLoader cl) {
        try {
            Class<?> task = XposedHelpers.findClassIfExists(CLS_LOG_TASK, cl);
            if (task == null) {
                log("Telecom: LogCallAsyncTask not found");
                return false;
            }

            boolean hooked = false;
            for (Method m : task.getDeclaredMethods()) {
                if (!m.getName().equals("doInBackground") || m.isBridge() || m.isSynthetic()) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            stampLoggedCalls(param.args, param.getResult());
                        } catch (Throwable t) {
                            logOnce("Telecom hook error: " + t);
                        }
                    }
                });
                hooked = true;
            }
            if (!hooked) log("Telecom: doInBackground not found");
            return hooked;
        } catch (Throwable t) {
            log("FAILED to hook Telecom call logging: " + t);
            return false;
        }
    }

    private static void stampLoggedCalls(Object[] hookArgs, Object result) {
        if (hookArgs == null || hookArgs.length == 0 || !(hookArgs[0] instanceof Object[])
                || !(result instanceof Object[])) {
            return;
        }

        Object[] callArgs = (Object[]) hookArgs[0];
        Object[] uris = (Object[]) result;

        for (int i = 0; i < callArgs.length && i < uris.length; i++) {
            Object args = callArgs[i];
            if (args == null || !(uris[i] instanceof Uri)) continue;

            Context ctx = (Context) XposedHelpers.getObjectField(args, "context");
            Object telecomCall = XposedHelpers.getObjectField(args, "call");
            if (ctx == null || telecomCall == null) continue;

            String cnap = sanitize((String) XposedHelpers.callMethod(telecomCall, "getCallerDisplayName"));
            if (cnap == null) continue;
            if ((Integer) XposedHelpers.callMethod(telecomCall, "getCallerDisplayNamePresentation")
                    == PRESENTATION_RESTRICTED) {
                continue;
            }

            stampRow(ctx, (Uri) uris[i], cnap);
        }
    }

    private static void stampRow(Context ctx, Uri row, String cnap) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(row,
                    new String[]{CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.NUMBER},
                    null, null, null);
            if (c == null || !c.moveToFirst()) return;

            // A name Telecom already stored (a contact) wins; CNAP only applies to incoming calls.
            if (!TextUtils.isEmpty(c.getString(0))) return;
            int type = c.getInt(1);
            if (type != CallLog.Calls.INCOMING_TYPE && type != CallLog.Calls.MISSED_TYPE
                    && type != CallLog.Calls.REJECTED_TYPE) {
                return;
            }
            if (sameNumber(cnap, c.getString(2))) return;

            ContentValues values = new ContentValues();
            values.put(CallLog.Calls.CACHED_NAME, cnap);
            ctx.getContentResolver().update(row, values, null, null);
        } catch (Throwable t) {
            logOnce("Telecom: naming call-log row failed: " + t);
        } finally {
            if (c != null) c.close();
        }
    }

    // ==========================================================================================
    // Contacts: Recents rows
    // ==========================================================================================

    private static final int MAX_ROW_VIEWS = 120;
    private static final int MAX_ROW_ASCENT = 6;

    /**
     * Hooks DialerCallVH by SHAPE: the bind method (first parameter is a DialerItemVM) and the
     * (String) -> void number-title setters. After any of them ran, the row's title is the visible
     * TextView with the biggest text size: a bare number there means "no contact", and is replaced by
     * the stored name. A contact row shows its name there and is left alone.
     */
    private static boolean hookRecentsRows(ClassLoader cl) {
        try {
            Class<?> vh = XposedHelpers.findClassIfExists(CLS_CALL_VH, cl);
            if (vh == null) {
                log("Recents: DialerCallVH not found (Contacts app changed?)");
                return false;
            }

            XC_MethodHook callback = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        applyToRecentsRow(param.thisObject);
                    } catch (Throwable t) {
                        logOnce("Recents hook error: " + t);
                    }
                }
            };

            int hooked = 0;
            for (Method m : vh.getDeclaredMethods()) {
                int mod = m.getModifiers();
                if (m.isBridge() || m.isSynthetic() || Modifier.isStatic(mod) || Modifier.isAbstract(mod)
                        || m.getReturnType() != void.class) {
                    continue;
                }
                Class<?>[] p = m.getParameterTypes();
                boolean bind = p.length > 0 && p[0].getName().endsWith(CLS_ITEM_VM_SUFFIX);
                boolean numberSetter = p.length == 1 && p[0] == String.class;
                if (!bind && !numberSetter) continue;

                XposedBridge.hookMethod(m, callback);
                hooked++;
            }

            if (hooked == 0) log("Recents: no row binder found in DialerCallVH (Contacts app changed?)");
            return hooked > 0;
        } catch (Throwable t) {
            log("FAILED to hook Recents rows: " + t);
            return false;
        }
    }

    /** Runs for every row bind while scrolling: no allocation or IPC unless a bare number is found. */
    private static void applyToRecentsRow(Object vh) {
        if (vh == null) return;

        TextView any = firstTextViewField(vh);
        if (any == null) return;

        View root = rowRoot(any);
        if (root == null) return;

        TextView title = biggestVisibleText(root);
        if (title == null) return;

        // Split "<number> (3)" (repeat count of missed calls) into number and suffix.
        String text = String.valueOf(title.getText()).trim();
        String suffix = "";
        int open = text.lastIndexOf('(');
        if (open > 0 && text.endsWith(")") && allDigits(text, open + 1, text.length() - 1)) {
            int cut = open;
            while (cut > 0 && text.charAt(cut - 1) == ' ') cut--;
            suffix = text.substring(cut);
            text = text.substring(0, cut);
        }

        // Only a bare number is replaced: names, spam tags, "Unknown", "Private" etc. stay.
        if (!looksLikeNumber(text)) return;

        String name = lookupName(title.getContext(), text);
        if (name == null) return;

        title.setText(name + suffix);
        title.setContentDescription(name + suffix);
        showNumberLine(vh, text);
    }

    private static Field[] textViewFields;

    private static synchronized Field[] textViewFieldsOf(Class<?> c) {
        if (textViewFields != null) return textViewFields;
        ArrayList<Field> list = new ArrayList<Field>();
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
            // not a usable row
        }
        return null;
    }

    /**
     * The row's own root view: a holder that is not attached yet has no parent, an attached one has the
     * RecyclerView/ListView as parent. Anything else is not trusted, so other UI is never touched.
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
        return budget[0] < 0 ? null : best[0]; // too many views: not a list row
    }

    private static void scan(View v, int[] budget, TextView[] best, float[] bestSize) {
        if (--budget[0] < 0 || v.getVisibility() != View.VISIBLE) return;

        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            CharSequence text = tv.getText();
            if (text != null && text.length() > 0 && tv.getTextSize() > bestSize[0]) {
                best[0] = tv;
                bestSize[0] = tv.getTextSize();
            }
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount() && budget[0] >= 0; i++) {
                scan(g.getChildAt(i), budget, best, bestSize);
            }
        }
    }

    // Optional second line with the number, only where the row class still has the helpers of the
    // originally dumped build (field S2, method V). Resolved once; otherwise the number stays on the
    // detail page.
    private static boolean numberLineResolved;
    private static Field numberLineField;
    private static Method numberLineInflater;

    private static synchronized void resolveNumberLine(Class<?> vh) {
        if (numberLineResolved) return;
        numberLineResolved = true;
        try {
            Field f = XposedHelpers.findFieldIfExists(vh, "S2");
            Method m = XposedHelpers.findMethodExactIfExists(vh, "V");
            if (f != null && m != null && TextView.class.isAssignableFrom(f.getType())) {
                numberLineField = f;
                numberLineInflater = m;
            }
        } catch (Throwable ignored) {
            // leave unresolved
        }
    }

    private static void showNumberLine(Object vh, String number) {
        if (!numberLineResolved) resolveNumberLine(vh.getClass());
        if (numberLineField == null) return;
        try {
            numberLineInflater.invoke(vh); // inflates the line if it is not there yet
            Object tv = numberLineField.get(vh);
            if (tv instanceof TextView) {
                ((TextView) tv).setText(number);
                ((TextView) tv).setVisibility(View.VISIBLE);
            }
        } catch (Throwable ignored) {
            // cosmetic only
        }
    }

    // ==========================================================================================
    // Contacts: "Unknown contact" detail page
    // ==========================================================================================

    /**
     * Old builds: UnknownContactActivity.onCreate reads the intent extras "number" and "name" and shows
     * "Unknown contact" for an empty name. Put the stored name into the extra before it runs, which also
     * covers the collapsed toolbar title.
     */
    private static boolean hookUnknownContactIntent(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(CLS_UNKNOWN_ACTIVITY, cl, "onCreate", Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!CLS_UNKNOWN_ACTIVITY.equals(param.thisObject.getClass().getName())) return;
                                Activity activity = (Activity) param.thisObject;
                                String name = storedNameForDetailPage(activity);
                                if (name != null) activity.getIntent().putExtra(EXTRA_NAME, name);
                            } catch (Throwable t) {
                                logOnce("UnknownContact onCreate hook error: " + t);
                            }
                        }
                    });
            return true;
        } catch (Throwable t) {
            log("FAILED to hook UnknownContactActivity.onCreate: " + t);
            return false;
        }
    }

    /**
     * Newer builds host the page in another activity (SubActivity). ContactDetailTitleView.setTitle is
     * where any detail page puts its title, so the name is substituted there, whatever the host is.
     */
    private static boolean hookDetailTitleView(ClassLoader cl) {
        try {
            Class<?> titleView = XposedHelpers.findClassIfExists(CLS_TITLE_VIEW, cl);
            if (titleView == null) {
                log("UnknownContact: ContactDetailTitleView not found (Contacts app changed?)");
                return false;
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
                            Activity host = hostActivity(((View) param.thisObject).getContext());
                            String name = host == null ? null : storedNameForDetailPage(host);
                            if (name != null) param.args[0] = name;
                        } catch (Throwable t) {
                            logOnce("UnknownContact title hook error: " + t);
                        }
                    }
                });
                hooked++;
            }

            if (hooked == 0) log("UnknownContact: setTitle not found on ContactDetailTitleView");
            return hooked > 0;
        } catch (Throwable t) {
            log("FAILED to hook ContactDetailTitleView: " + t);
            return false;
        }
    }

    /**
     * The stored name for a detail page opened for a bare number: the intent carries a number, no name,
     * and no contact URI (contact pages carry one and are never touched).
     */
    private static String storedNameForDetailPage(Activity host) {
        Intent intent = host.getIntent();
        if (intent == null || intent.getData() != null) return null;
        if (!TextUtils.isEmpty(intent.getStringExtra(EXTRA_NAME))) return null;

        String number = intent.getStringExtra(EXTRA_NUMBER);
        if (TextUtils.isEmpty(number)) number = intent.getStringExtra(EXTRA_NUMBER_ALT);
        return TextUtils.isEmpty(number) ? null : lookupName(host, number);
    }

    private static Activity hostActivity(Context c) {
        for (int i = 0; i < 6 && c != null; i++) {
            if (c instanceof Activity) return (Activity) c;
            if (!(c instanceof ContextWrapper)) return null;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    // ==========================================================================================
    // Stored names (Contacts process)
    // ==========================================================================================

    private static final ConcurrentHashMap<String, String> NAMES = new ConcurrentHashMap<String, String>();
    private static volatile long lastNamesLoad;
    private static final long NAMES_REFRESH_MS = 5L * 1000; // at most one reload per 5 s, and only on a miss
    private static final int NAMES_MAX_ROWS = 500;

    /** Newest name stored in call_log.name for this number (matched on its last 10 digits). */
    private static String lookupName(Context ctx, String number) {
        String key = tail(number);
        if (ctx == null || key.isEmpty()) return null;

        String name = NAMES.get(key);
        long now = System.currentTimeMillis();
        if (name == null && now - lastNamesLoad > NAMES_REFRESH_MS) {
            loadNames(ctx, now);
            name = NAMES.get(key);
        }
        return name == null || sameNumber(name, number) ? null : name;
    }

    /** One batched query fills the whole map; scrolling itself does no IPC. */
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
                String name = sanitize(c.getString(1));
                if (!key.isEmpty() && name != null && !fresh.containsKey(key)) fresh.put(key, name); // newest wins
            }
        } catch (Throwable t) {
            logOnce("call_log names load failed: " + t);
            return;
        } finally {
            if (c != null) c.close();
        }

        NAMES.clear();
        NAMES.putAll(fresh);
    }

    // ==========================================================================================
    // Helpers
    // ==========================================================================================

    private static String sanitize(String name) {
        if (name == null) return null;
        name = name.trim();
        if (name.isEmpty()) return null;
        return name.length() > 256 ? name.substring(0, 256) : name;
    }

    private static boolean allDigits(String s, int from, int to) {
        if (from >= to) return false;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** "+91 88188 11111", "08818811111", "(0881) 881-1111": digits and + space - ( ) . only, 5+ digits. */
    private static boolean looksLikeNumber(String s) {
        int digits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') digits++;
            else if (c != '+' && c != ' ' && c != '-' && c != '(' && c != ')' && c != '.') return false;
        }
        return digits >= 5;
    }

    /** Last 10 digits, so +91..., 0... and bare formats of one number share a key. No regex, no garbage. */
    private static String tail(String number) {
        if (number == null) return "";
        char[] out = new char[10];
        int n = 0;
        for (int i = number.length() - 1; i >= 0 && n < 10; i--) {
            char c = number.charAt(i);
            if (c >= '0' && c <= '9') out[9 - n++] = c;
        }
        return n == 0 ? "" : new String(out, 10 - n, n);
    }

    /** Same digits (and +) in the same order, ignoring spaces, dashes and brackets. False if none at all. */
    private static boolean sameNumber(CharSequence a, CharSequence b) {
        if (a == null || b == null) return false;
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

    private static void log(String message) {
        XposedBridge.log(TAG + message);
    }

    /** Each distinct error is logged once per process, so a persistent failure cannot flood the log. */
    private static final Set<String> LOGGED = Collections.synchronizedSet(new HashSet<String>());

    private static void logOnce(String message) {
        if (LOGGED.size() < 20 && LOGGED.add(message)) log(message);
    }
}
