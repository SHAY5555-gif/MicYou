# Android microphone mixer preview

This preview is based on MicYou 2.1.1. The Android debug app installs as
`com.lanrhyme.micyou.ptt` with the launcher name **MicYou Mixer**, alongside the
official app. The desktop 2.1.1 wire protocol is unchanged.

## Controls

- Connect opens the selected USB or Wi-Fi connection in a muted state.
- Hold to talk sends microphone audio only while held. Release, a cancelled
  gesture, opening settings, or leaving the activity mutes the stream.
- Microphone volume ranges from 0% to 200%. Zero also mutes the echo tail.
- Echo level ranges from 0% to 50%. Zero is dry, without an added echo.
- Echo delay ranges from 50 to 450 milliseconds.
- The three controls use vertical console faders. Dragging up increases the
  value; dragging down decreases it. The displayed number updates immediately.
- Mixer settings are saved on the phone. Connecting again always starts muted.

The recorder stays warm while connected for quick push-to-talk response. Muted
audio is discarded locally instead of being transmitted. Disconnect releases
the capture session. This is transmission gating, not permission revocation.

The mixer processes PCM before Opus encoding. The echo has bounded feedback and
is cleared on release. Muted PCM and pending FEC groups are discarded.

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

The four mixer tests cover dry processing, zero volume including an existing
echo tail, delay timing and reset, and signed PCM saturation.

Manual preview checks on a physical SM-F9710 with the official Windows 2.1.1
server, USB transport, and HDMI output:

- Initial connection reports muted on the server.
- Holding and releasing produces the matching unmuted and muted server states.
- Pressing Home during a hold mutes the server before the hold completes.
- Returning to the activity leaves it muted.
- All three faders are visible together while connected, with a compact layout
  when the connection settings occupy part of the phone screen.
- Dragging each fader in both directions changes its corresponding saved value.
- Fader changes reach the PCM mixer, and saved values survive process restart
  and an APK update.

The user confirmed sound only while holding the talk button, without duplicated
voice at zero added echo, before the fader-only UI update. These checks do not
establish end-to-end acoustic latency or complete acoustic echo cancellation in
other room configurations or at higher levels.
