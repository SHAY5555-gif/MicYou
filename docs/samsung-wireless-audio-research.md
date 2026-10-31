# Samsung Q70T wireless microphone research

Checked on 2026-10-31. The target is the HW-Q70T soundbar, not the Q70T TV.

## Implemented transport

The Android Bluetooth option captures the built-in microphone and plays PCM
directly to a connected A2DP output. It does not use the desktop server, Opus,
TCP, or a cloud service. The selected Bluetooth route must be confirmed before
the talk control becomes available. Losing that route stops the microphone.

The default engine uses 48 kHz mono audio and 10 ms input blocks,
low-latency output where Android supports it, and an initial 40 ms application
output buffer. Blocking reads and complete writes preserve every sample across
partial output writes. Sustained underruns can increase the buffer within its
allocated capacity. Muting flushes and primes the application playback buffer;
audio already buffered inside a Bluetooth receiver cannot be recalled.

The initial 5 ms/nonblocking implementation dropped unwritten samples and the
user reported chopped/robotic audio. That implementation failed the listening
test. The replacement logs input/output frame counts and underruns so transport
continuity can be distinguished from acoustic feedback and receiver latency.

The existing microphone gain, echo and limiter run on the phone. A sensitivity
gate acts before output gain to reject quiet room pickup and close quickly
after speech. It is a level threshold, not a distance sensor.

An optional path uses the pinned
`cn.enaium.webrtc.aec3:webrtc-aec3-kmp:1.0.3` native WebRTC AEC3 binding, with
the actual post-mixer output as its render reference.
A short history aligns that reference with the delayed speaker path. Capture
uses `VOICE_RECOGNITION`; the previous Android AEC/NS pair is not layered over
the software canceller. The desktop AEC7/RNNoise processing is not used.

The dependency's published 48 kHz binary failed an on-device synthetic echo
test (0 dB reduction). Its 16 kHz path passed, including preserving near-end
voice without a render signal. The integrated 16 kHz path reduced a known
delayed synthetic echo by 63.46 dB. These tests do not establish cancellation
quality in the user's room. The voice bandwidth is limited to 8 kHz; Android
resamples it for the Bluetooth output. Dependency license texts are included
under `composeApp/src/main/assets/licenses/`.

The optional AEC experiment did not pass the user's real-room listening test.
The connection panel exposes an independent **Echo cancellation** switch, off
by default. **Echo level** still controls the optional artistic echo, not this
acoustic feedback filter.

The Speaker fader adjusts Android media output in direct mode. Push to talk and
Normal retain their existing behavior. Normal can stay active in the background;
the foreground notification, app dismissal and disconnect still stop capture.

## Official interfaces

- [SmartThings Core SDK](https://github.com/SmartThingsCommunity/smartthings-core-sdk)
  wraps the SmartThings REST API. It exposes device control and capabilities,
  rather than a documented low-latency PCM transport to this soundbar.
- [Samsung Smart View SDK](https://developer.samsung.com/smarttv/develop/extension-libraries/smart-view-sdk/introduction.html)
  discovers Smart TVs, launches their applications and communicates with them.
  Its documented receiver architecture is a TV application. This does not
  establish a compatible audio receiver on the Q70T soundbar.
- [Samsung Q70T specifications](https://www.samsung.com/uk/business/audio-devices/soundbar/soundbar-q70t-hw-q70t-xu/)
  document Wi-Fi streaming through music services and SBC Bluetooth. The model
  does not advertise a low-latency Bluetooth codec.
- [Samsung Q70T detailed specifications](https://www.samsung.com/au/audio-devices/soundbar/3-1-2-ch-soundbar-black-hw-q70t-xy/)
  list Spotify Connect, but no Chromecast or AirPlay.
- [Android audio latency guidance](https://developer.android.com/ndk/guides/audio/audio-latency)
  distinguishes application, capture, output and warmup delays. No numeric
  end-to-end Bluetooth latency is inferred from the local buffer size.

## Community projects

- [Enaium/webrtc-aec3-kmp](https://github.com/Enaium/webrtc-aec3-kmp)
  supplies the Android AEC3 binding used here, not a Samsung transport. Version
  1.0.3 is pinned from Maven Central. Its published Android AAR SHA-256 is
  `2550dda1d0e33bc17ee81baaedde840ccab36978575d6b96032515168d6ef1f6`.
  The local microphone/speaker signal path and native tests are specific to
  this app. The package is not used as evidence of Q70T Wi-Fi support.
- [Strixx76/samsungwam](https://github.com/Strixx76/samsungwam) can hand a media
  URL to supported legacy Wireless Audio-Multiroom speakers. Its author limits
  compatibility to devices supported by the native Multiroom app. Q70T support
  and low-latency live microphone playback have not been established.
- [c-kick/ha-smartthings-soundbar-extras](https://github.com/c-kick/ha-smartthings-soundbar-extras)
  offers advanced settings and an optional local IP Control API. Its local API
  support table targets 2024 and newer soundbars and excludes 2023 and older
  models. The Q70T is a 2020 model. Its local player controls do not carry PCM.
- [ThierryBourbon/Home-Assistant-Samsung-Soundbar](https://github.com/ThierryBourbon/Home-Assistant-Samsung-Soundbar)
  provides power, volume, mute, source and sound-mode controls through
  SmartThings. Its published features do not include a live microphone stream.

There are useful building blocks for optional wireless remote control. There
is no verified SDK or community transport here that turns the Q70T into a
low-latency Wi-Fi microphone receiver. URL playback would need a separate
compatibility and buffering experiment before it could be offered as an app
mode. Firmware modification and undocumented commands are not required for the
implemented Bluetooth mode.
