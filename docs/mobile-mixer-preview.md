# Android microphone mixer preview

This preview is based on MicYou 2.1.1. The Android debug app installs as
`com.lanrhyme.micyou.ptt` with the launcher name **MicYou Mixer**, alongside the
official app. The desktop 2.1.1 wire protocol is unchanged.

## Controls

- Connect opens the selected USB or Wi-Fi connection in a muted state.
- Choose **Push to talk** or **Normal** in the bottom bar.
- Push to talk sends microphone audio only while held. Release, a cancelled
  gesture, opening settings, or leaving the activity mutes that mode.
- Normal uses a single tap to turn the microphone on and another tap to mute it.
  It stays on when the activity goes into the background while connected.
- Changing modes always mutes the microphone. The mode choice is saved, but
  reopening a connection starts muted in either mode.
- Microphone volume ranges from 0% to 600%. Zero also mutes the echo tail.
- Music (PC) independently adjusts supported Windows music/browser sessions
  from 0% to 100%, over the optional USB companion bridge below.
- Echo level ranges from 0% to 50%. Zero is dry, without an added echo.
- Echo delay ranges from 50 to 450 milliseconds.
- The four controls use vertical console faders. Dragging up increases the
  value; dragging down decreases it. The displayed number updates immediately.
- Mixer settings are saved on the phone. Connecting again always starts muted.

The recorder stays warm while connected for quick response in either mode. Muted
audio is discarded locally instead of being transmitted. Disconnect releases
the capture session. This is transmission gating, not permission revocation.

The mixer processes PCM before Opus encoding. The echo has bounded feedback and
is cleared when muted or when a push-to-talk hold is released. Muted PCM and
pending FEC groups are discarded.

A stereo-linked peak limiter caps boosted PCM at approximately 95% of full
scale, with immediate attenuation and an 80 ms recovery envelope. It adds no
look-ahead buffer. High gain can still amplify noise or cause room feedback.

## Optional Windows music control

Start `python tools/windows_music_bridge.py` on Windows and run
`adb -s DEVICE_SERIAL reverse tcp:8556 tcp:8556`. Keep the existing audio
transport on port 8554. The music fader needs this companion process and USB;
it is disabled when the bridge cannot find a supported media session.

The bridge listens only on `127.0.0.1:8556`. Each request is one JSON line,
limited to 256 bytes. An empty object reads status; `{"music":0.6}` sets 60%.
Values must be finite numbers from zero to one. Only an explicit list of media
apps and browsers is adjustable. Master output, system sounds, communication
apps, and MicYou's voice session are not changed. The control covers all audio
from a matching browser process, including its other tabs. It does not restart
automatically after a PC reboot.

Added echo and acoustic feedback are different. The echo slider cannot remove
sound that travels from a loudspeaker back into the microphone. Keep the desktop
echo canceller enabled and begin with added echo at zero. Noise reduction and
level gating do not measure the distance to a speaker.

## Validation

Build with JDK 21 and the Android SDK required by the repository:

```sh
./gradlew :composeApp:testDebugUnitTest :composeApp:assembleDebug
git diff --check
```

The six mixer tests cover dry processing, zero volume including an existing
echo tail, delay timing and reset, sixfold boost, peak limiting, and stereo
balance. Six transmission
gate tests cover connected presses, release, normal mode latching, explicit
mute, mode changes, and connection reset.

Manual preview checks on a physical SM-F9710 with the official Windows 2.1.1
server, USB transport, and HDMI output:

- Initial connection reports muted on the server.
- Holding and releasing produces the matching unmuted and muted server states.
- Pressing Home during a hold mutes the server before the hold completes.
- Returning to the activity leaves it muted.
- In Normal mode a single tap stays unmuted after release and after pressing
  Home; a second tap mutes it. Changing from an active Normal mode to Push to
  talk mutes it.
- A saved Normal choice survives process restart and reconnects muted.
- All four faders are visible together while connected, with a compact layout
  when the connection settings occupy part of the phone screen.
- Dragging each fader in both directions changes its corresponding saved value.
- Fader changes reach the PCM mixer, and saved values survive process restart
  and an APK update.
- The phone music fader changed Chrome's Windows session to 60% while MicYou's
  voice session and the master output retained their previous levels.
- An out-of-range bridge request was rejected without changing the music level.

The user confirmed sound only while holding the talk button, without duplicated
voice at zero added echo, before the fader-only UI update. These checks do not
establish end-to-end acoustic latency or complete acoustic echo cancellation in
other room configurations or at higher levels.
