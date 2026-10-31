# Samsung Q70T wireless microphone research

Checked on 2026-10-31. The target is the HW-Q70T soundbar, not the Q70T TV.

## Implemented transport

The Android Bluetooth option captures the built-in microphone and plays PCM
directly to a connected A2DP output. It does not use the desktop server, Opus,
TCP, or a cloud service. The selected Bluetooth route must be confirmed before
the talk control becomes available. Losing that route stops the microphone.

The engine requests the device-native 44.1/48 kHz rate, 10 ms input blocks,
low-latency output where Android supports it, and an initial 40 ms application
output buffer. Blocking reads and complete writes preserve every sample across
partial output writes. Sustained underruns can increase the buffer within its
allocated capacity. Muting flushes and primes the application playback buffer;
audio already buffered inside a Bluetooth receiver cannot be recalled.

The initial 5 ms/nonblocking implementation dropped unwritten samples and the
user reported chopped/robotic audio. That implementation failed the listening
test. The replacement logs input/output frame counts and underruns so transport
continuity can be distinguished from acoustic feedback and receiver latency.

The existing microphone gain, echo and limiter run on the phone. Noise
suppression and acoustic echo cancellation use optional Android effects when
available. Their presence is not proof of effective echo cancellation on this
external speaker path. The desktop AEC7/RNNoise processing is not part of this
direct transport.

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
