# Xiaomi CNAP Fix

An LSPosed module that makes the Xiaomi (MIUI/HyperOS) dialer show the **caller name sent by the network (CNAP)** for numbers that are not in your contacts, the same name Google Dialer shows.

On stock MIUI the network name is received by the phone but never displayed. The call screen, the notification, the call history and the detail page all fall back to the bare number. This module fixes that.

> **CNAP** (Calling Name Presentation) is a carrier service that sends the caller's registered name together with the incoming call.

---

## What it fixes

| Where | Before | After |
|---|---|---|
| Incoming call screen (full screen) | `+91 88188 11111` | `Caller Name` |
| Heads-up / notification | number | name |
| Call history (Recents) | number | name, with the number on the second line |
| "Unknown contact" detail page | `Unknown contact` | name |

Names are stored in the system call log's own name column, so they stay in your history after the call ends.

## Requirements

- Rooted device with **LSPosed** (or a compatible fork) and Xposed API 82 support
- A carrier that actually sends CNAP. **If Google Dialer shows the name on your SIM, this module can too.**

## Tested on

| Component | Version |
|---|---|
| Device | Xiaomi `ruby` |
| ROM | MIUI 14, based on Android 13 |
| Contacts and dialer (`com.android.contacts`) | Multiple versions |
| Phone / InCallUI (`com.android.incallui`) | Multiple versions |
| Telecom (`com.android.server.telecom`) | 13 |
| System Framework | 13 |

> **Important:** Xiaomi's Contacts app is obfuscated and its internals change between versions. The module avoids depending on renamed names, but it is **only confirmed on the versions above**. On a version where a target can't be found, the module logs a `FAILED to hook ...` or `(Contacts app changed?)` line and leaves that screen unchanged. See [Reporting issues](#reporting-issues).

## Installation

1. Download the latest APK from the [Releases](../../releases) page and install it.
2. Open **LSPosed → Modules → Xiaomi CNAP Fix** and enable it.
3. In the module's scope, tick **only** these:

   | Scope | Why |
   |---|---|
   | **System Framework** | Telecom runs in the system process; this is where the name is written to the call log |
   | **Phone** (`com.android.incallui`) | Incoming call screen and notification |
   | **Contacts and dialer** (`com.android.contacts`) | Call history and the unknown-contact page |

   
4. **Reboot.** A reboot is required for the System Framework scope.
5. Test with a call from a number that is not in your contacts.

Names appear only for **new** calls received after installing. Calls that are already in your history are not renamed.

## How it works

1. **Incoming call** (`com.android.incallui`): the CNAP name is read from the call and filled into the contact cache entry, so the call screen and notification use it. The call card also gets a fallback so the UI stops treating the name as a bare number.
2. **Call ends** (`com.android.server.telecom`, runs as the system user): right after Telecom writes the call-log row, the CNAP name is stored in that row's name column, only if the row has no name yet. Contacts always win.
3. **History and detail page** (`com.android.contacts`): the Recents row and the "Unknown contact" header read that stored name and show it when there is no matching contact. This app is obfuscated, so these hooks find their targets by class name, parameter types and the shape of the row layout instead of by renamed method names. That makes them far more tolerant of app updates, but not immune.

Everything is event-driven. There is no service, no background thread, no receiver, no timer and no network access.

## Performance and privacy

- No background work, so no wakeups or battery drain of its own.
- The only code that runs while scrolling is the Recents row hook. It exits early for contact rows, resolves its reflection handles once, and reads names from an in-memory map filled by a single query (refreshed at most every 10 seconds).
- Logging is minimal: one line per app at startup (`<package>: N/N hooks active`), plus a line for any hook that could not be installed. A repeating error is logged once, so the log cannot grow while you use the phone. Phone numbers and names are never logged.
- No internet permission. Nothing leaves the device; the only thing written is the caller name into the system call log.

## Known limitations

- Works only for **incoming** calls where the network sends a name. Outgoing calls and calls without CNAP are unchanged.
- Old call-log entries are not back-filled.
- If you later save the number as a contact, the contact name takes over.
- The call-history rows on the detail page still show time and number, as in stock MIUI. Only the header changes.
- Tested on one device and set of app versions, see [Tested on](#tested-on). Other versions may work but are unconfirmed.

## Troubleshooting

Collect the module's log lines:

```
su -c 'logcat -d | grep -F "XiaomiCNAPFix"'
```

After a reboot, and after opening the Phone/Contacts app and receiving a call, you should see one line per scope:

| Log line | Means |
|---|---|
| `com.android.server.telecom: 1/1 hooks active` | Telecom hook is active (needs the **System Framework** scope and a reboot) |
| `com.android.incallui: 2/2 hooks active` | Call screen and notification hooks are active |
| `com.android.contacts: 3/3 hooks active` | History and unknown-contact hooks are active |

A lower count, or a line that starts with `FAILED to hook ...` or mentions `(Contacts app changed?)`, means the installed app version differs from the tested one. That screen is left unchanged. Please open an issue.

If a line is missing entirely, check that the scope is ticked and that you rebooted.

## Reporting issues

Please [open an issue](../../issues/new) and include:

1. **Device and ROM:** model, MIUI/HyperOS version, Android version
2. **App versions:**
   ```
   su -c 'for p in com.android.contacts com.android.incallui com.android.server.telecom; do echo $p; dumpsys package $p | grep -m1 versionName; done'
   ```
3. **LSPosed version** and which scopes you ticked
4. **What you expected and what happened**, with a screenshot if it's a display problem
5. **The log**, from the command in [Troubleshooting](#troubleshooting). The module does not log numbers or names, but please check for anything private before posting.
6. Whether **Google Dialer** shows the name on your SIM. If it does not, the carrier is not sending CNAP and this module can't help.

If a hook fails on a different version, a class dump of the affected app helps. Open the issue first and I'll say which one.

## Building

- JDK 17, Android Gradle build
- The Xposed API is `compileOnly`: `de.robv.android.xposed:api:82`, so it is not packaged into the APK
- Entry point (`assets/xposed_init`): `com.dhangofa.xiaomicnapfix.CnapFixHook`
- Main logic: `app/src/main/java/com/dhangofa/xiaomicnapfix/CnapFixHook.java`

```
gradle assembleRelease
```

## Credits

- **AI assistance:** this module was developed with the help of multiple Free AI assistants. It was used to analyse the app dumps, work out where the name gets lost in Xiaomi's apps, and write and review the hooks. The module was tested by the author on a real device, and the design and decisions are the author's.
- Built on the [LSPosed](https://github.com/LSPosed/LSPosed) / Xposed framework.

## License

MIT, see [LICENSE](LICENSE).

## Trademark notice

Xiaomi, MI, MIUI, HyperOS, Redmi, and their associated names and logos are
trademarks of Xiaomi Corporation and/or its affiliates.

Xiaomi CNAP Fix is an independent, unofficial, open-source compatibility
module. This project is not affiliated with, endorsed by, sponsored by, or
approved by Xiaomi Corporation.

The Xiaomi logo shown in project artwork is used only to identify the platform
with which this module is designed to work. No ownership of Xiaomi trademarks
or brand assets is claimed.
