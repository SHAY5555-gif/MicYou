# Windows singing launcher

`tools/windows-launcher/Install-Singing.ps1` installs a small native Windows
launcher named **MicYou Singing**. Opening its desktop or Start menu shortcut
opens a connection panel and automatically connects the configured Android
phone over USB to the selected Windows output. The existing MicYou app keeps
its microphone gain, echo and music mixer controls on the phone.

The panel provides output selection, Connect, Stop and Phone mixer. Selecting
a different output reconnects immediately, saves that choice and names the
active target in the status message. Closing
the panel stops its receiver and closes the configured phone app without
clearing its data. No Windows startup entry, firewall change or system-wide
audio default change is made.

While its panel is open, the launcher checks that the selected speaker is
still available every two seconds. It stops the connection if that endpoint
disappears, instead of leaving the user connected to a different output.

## Installation

Run the installer in PowerShell with `-AdbPath`, `-PythonPath`, `-DeviceSerial`
and `-OutputDevice`. It requires an existing MicYou desktop installation and
the mixer APK on the selected phone. `-InstallDirectory`, `-LauncherDirectory`
and `-PhonePackage` may override the defaults. Personal paths and the device
serial are saved only in `%APPDATA%\micyou\singing-launcher.json`.

The native entry point is compiled with the Windows .NET Framework compiler.
It opens the PowerShell panel without a console. The receiver and optional
music bridge also run without visible console windows.

## Connection behavior

- Validate the exact phone and speaker before stopping an existing receiver.
- Recreate only that phone's TCP 8554 and 8556 USB reverse mappings.
- Stop an existing CLI receiver only when its executable identity matches.
- Preserve stale mode-lock contents before replacing a dead process record.
- Launch the selected speaker explicitly, independently of Windows defaults.
- Reconnect the phone using controls discovered from a fresh UI tree, then
  confirm both the phone's unmuted state and the receiver handshake.
- Check executable identity and creation time before stopping saved PIDs.
- Retain logs and configuration backups under `%APPDATA%\micyou`.

This version automates the English mixer UI in the custom Android preview.
The phone must be unlocked, USB debugging already authorized, and MicYou's
microphone permission already granted. It reports an error and stops its
receiver when those prerequisites are missing. It does not grant permissions,
clear app data, or modify another connected device.

## Verification on 2026-11-01

Windows PowerShell 5.1 parsed both scripts, and the native launcher compiled.
On the connected physical Android phone, repeated Start and Stop cycles
confirmed the TCP handshake, unmuted stream, explicit Samsung output, and
receiver shutdown. Missing-device and missing-output cases failed before
interrupting the active receiver. The panel layout was inspected with
offscreen rendering; the desktop was not brought to the foreground.

These are transport and control checks. They do not establish end-to-end
acoustic latency or listening quality in the room. No new Android APK or
upstream desktop binary is built by this launcher.

The original working soundbar route was later cross-checked with its creator:
EP-HDMI-RX was the PC-to-soundbar route, while SAMSUNG was another display
output. An available device name is not proof of the intended physical route.
During the follow-up, the local launcher was corrected to EP-HDMI-RX and the
output-selector handler was fixed to apply changes immediately.
