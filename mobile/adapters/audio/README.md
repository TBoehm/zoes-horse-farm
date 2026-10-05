# `:adapters:audio` – synthesised sound for the native app

Port of `src/adapters/audio/` of the web app. The web app builds every sound from WebAudio nodes
(oscillators, noise buffers, biquad filters, gain envelopes, a convolution reverb and a compressor) and
ships no audio files. On the phones there is no WebAudio, so this module contains a small real-time
synthesiser that reproduces that node graph as PCM, plus the same sound recipes on top of it.

Package `app.zoeshorsefarm.audio`, depends on `:core:shared` only (the module is self-contained, the
shared helpers are not needed so far).

## Layers

```
Audio (facade)            createAudio behaviour: unlock state machine, volumes, music, hidden, pause,
   |                      sfx counting, dispose            -> Audio.kt
AudioEngine               node graph as PCM: buses, master, reverb, compressor, melody sequencer,
   |                      voice scheduling on the audio clock   -> AudioEngine.kt
Sound recipes             sfx, music, dsp helpers (same numbers as the web)
   |                      -> SfxVoices.kt, Music.kt, Dsp.kt, Melody.kt, AudioLogic.kt
synth/                    voices, automation params, biquad, FFT, partitioned convolver, compressor
PcmOutput (port)          platform output: iosMain AVAudioEngine, jvmMain SourceDataLine
```

| Web file | Kotlin |
| --- | --- |
| `logic.js` | `AudioLogic.kt` (`volumeToGain`, `normalizeSettings`, `planSteps`, `Mulberry32`, `generateImpulse` ...) |
| `melody.js` | `Melody.kt` |
| `music.js` | `Music.kt` |
| `sfx.js` | `SfxVoices.kt` |
| `dsp.js` | `Dsp.kt` (recipes) and `synth/SynthVoice.kt` (the envelope/filter/oscillator chain) |
| `index.js` | `Audio.kt` (facade) and `AudioEngine.kt` (graph and rendering) |

All frequencies, durations, gains and the order in which the recipes draw random numbers are the same
as in the web app (`mulberry32(2024)` for jitter, `1337` for the noise buffer, `7` for the reverb).

## Synth (`synth/`)

* `AutomationParam`: `setValueAtTime`, `linearRampToValueAtTime`, `exponentialRampToValueAtTime`,
  `setTargetAtTime`, `cancelAndHoldAtTime` with the WebAudio semantics (event ordering, ramps from the
  previous event, exponential ramps from zero hold the start value). Generated sample by sample, ramps
  advance incrementally.
* `SynthVoice`/`Synth`: pool of 160 voices: oscillator (sine through a 2048-point table, triangle) or looped
  noise, optional biquad filter, gain envelope. A full pool drops new notes (the heaviest effect, railDown,
  needs about 30 voices).
* `Biquad`: lowpass/highpass/bandpass with the coefficients of the WebAudio specification (RBJ cookbook;
  `q` in dB for lowpass/highpass, linear for bandpass; edge cases at 0 and Nyquist as in the spec).
* `PartitionedConvolver` + `Fft`: the reverb (1.8 s synthetic room, stereo) as uniformly partitioned
  overlap-save convolution with the WebAudio `ConvolverNode` normalisation. Costs a few MFLOP/s, the output
  is one partition (512 frames, 11.6 ms) late, which is inaudible on a reverb tail. The reverb sleeps when the
  melody is not playing and its tail has died out.
* `DynamicsCompressor`: port of the WebKit/Blink `DynamicsCompressorKernel` (soft-knee curve, automatic makeup
  gain, 6 ms look-ahead, adaptive release, sine warp) with the web app's parameters (-10 dB, knee 8, ratio 10,
  attack 3 ms, release 250 ms). The makeup gain matters: quiet sounds come out about 3.7 dB louder.

The graph (same as `createAudio`):

```
effects -> session gain -> sfx channel -----------------------------+
melody  -> run gain -+-> music channel ------------------------------+-> master -> compressor -> out
                     +-> highpass 250 Hz -> send 0.3 -> reverb -> music channel
```

`AudioEngine` renders in quanta of 128 frames (like WebAudio), keeps its own clock (`currentTime`) and runs
the melody's look-ahead scheduler inside the render step, so no timer thread is needed. Rendering a block does
not allocate (checked by `JvmAudioTest.renderingABlockDoesNotAllocate`, which measures 0 bytes over 400 blocks).

## Using it

```kotlin
val audio = createAudio(AudioSettings(musicVolume = 0.5, sfxVolume = 0.5))   // platform output + timer
audio.installUnlock { needed -> /* ask the UI to call audio.onUserInteraction() on the next touch */ }
audio.setMusicWanted(true)         // screen flag `music`; remembered until the audio is unlocked
audio.setVolumes(musicVolume = 0.8, sfxMuted = false)   // fields that are null keep their value
audio.setHidden(true)              // app in the background: silent at once, output suspended after 120 ms
audio.setPaused(true)              // no effects while paused, running ones are cut
audio.sfx.hoof("canter"); audio.sfx.takeoff(); audio.sfx.landing()
audio.sfx.railDown(); audio.sfx.startSignal(); audio.sfx.finishSignal()
audio.getState()                   // unlocked, running, failed, hidden, paused, musicWanted, musicPlaying,
                                   // sfxCounts (same counting rules as the web: only effects that were heard)
audio.dispose()
```

**Unlock** keeps the web's state machine: nothing is played or counted before `unlock()` created and started
the output. On the web a user gesture is needed; on the phones the equivalent is "the audio session is
activated and the output runs". The app calls `audio.unlock()` on the first touch and when it returns to the
foreground; `onUserInteraction()` is the handler for the listener registered with `installUnlock`: the listener
is told `true` while an unlock is needed (before the first unlock, after an interruption such as a phone call,
after a refused resume while the app is visible) and `false` once the output runs again. The DOM gesture
filtering (touch pointerdown, pen pointerup, Escape ...) has no mobile counterpart and was dropped.

## Platform outputs

`PcmOutput` is a pull-based stereo float stream (`start(renderer)`, `resume`, `suspend`, `close`, a state
and a state listener). `Audio` only touches it through this port (`AudioPlatform(outputFactory, scheduler)`),
so tests inject `FakePcmOutput` and a manual timer.

* **iOS** (`IosPcmOutput`): `AVAudioEngine` + `AVAudioSourceNode` (AVFAudio), session category *ambient*
  (respects the silent switch, mixes with other apps, like Safari's web audio). Interruptions and route
  changes are reported through the state; the app resumes with `unlock()`. The render block copies the
  synth blocks into the `AudioBufferList` without allocating. Compiles for `iosArm64` and
  `iosSimulatorArm64`; it can only be run on a Mac (not verified on a device or simulator here).
* **JVM** (`JvmPcmOutput`): `javax.sound.sampled.SourceDataLine`, a daemon thread renders and writes 16-bit
  PCM. For desktop and development. Without an audio device `start` throws and `Audio` reports `failed`.
* **Android** comes later: an `androidMain` `PcmOutput` on `AudioTrack` (float PCM, `MODE_STREAM`, low
  latency performance mode, a writer thread that calls the renderer) plus a `Handler`-based `Scheduler`
  and `AudioFocus` handling for the interruptions. `createPlatformAudio()` is the only `expect` that needs
  an `actual` for the new target.

## Threading

The game thread calls the facade, the platform's audio thread calls `AudioEngine.render`. The facade has
its own lock for its state and calls platform output methods while holding it. The engine has no lock: the
facade never touches the audio state. State-like controls (volumes, hidden, music on/off) are
latest-value atomics that `render` reads once per 128-frame quantum, so they cannot be lost or overflow.
One-shot controls (play an effect, drop the effects session) go through a preallocated lock-free
single-producer/single-consumer `CommandQueue` that `render` drains at the start of the quantum. The audio
thread therefore never waits for the game thread (no priority inversion, however many voices an effect
starts) and never takes the facade lock, so `AVAudioEngine.pause()` waiting for the audio thread cannot
deadlock. A full queue (512 commands) drops the effect and counts it in `AudioEngine.droppedCommands`.
The iOS output holds its renderer in an
`AtomicReference`. On Kotlin/Native a garbage collection can still pause the audio thread; keep the
frame loops free of allocations as well.

## Deviations from the web app

* The filter formulas follow the WebAudio specification. Blink's lowpass resonance uses a slightly different
  internal formula; for the 0.7 dB resonance of the effects the difference is below 1 dB at the cutoff.
* The clock of the engine advances in quanta of 128 frames (2.9 ms at 44.1 kHz) like `currentTime` in
  WebAudio; effects start 4 ms after the call plus the 6 ms look-ahead of the compressor. The melody
  scheduler runs every quantum instead of every 30 ms (same 0.15 s look-ahead, same drift-free timing).
* Triangle oscillators are not band-limited (aliasing is below -50 dB for the notes that are used). Noise
  buffers are read without interpolation at their start offset.
* The compressor has no metering (`reduction`), which nothing reads.
* Several `AudioSettings` fields can be `null` in `setVolumes` (keep), the web keeps `undefined` as "set to
  default"; invalid values are typed away (no strings or NaN from outside).
* The unlock gesture listeners are replaced by the `UnlockListener`/`onUserInteraction` pair (see above).
* The suspend after 120 ms in the background needs a timer, which is injected (`Scheduler`); all other
  `setTimeout`s of the web (disconnecting nodes after fades) are frame counters of the engine.

## Tests

`./gradlew :adapters:audio:jvmTest` (and the common gates of `mobile/README.md`).

* Ported from the web tests: `logic.test.js` (`AudioLogicTest`), `melody.test.js` (`MelodyTest`),
  `sfx.test.js` (`SfxTest`, with a recording sink instead of the recording WebAudio fake) and `audio.test.js`
  (`AudioTest`, with `FakePcmOutput` and `ManualScheduler`). The tests of node counts became voice counts and
  rendered levels.
* New: parameter automation, biquad response against the cookbook, FFT and convolver against direct
  computation, compressor curve, voice envelopes and pitch, engine (levels, fades, determinism, block-size
  independence, melody, hidden, sessions), PCM conversion, concurrency and zero allocation (JVM).
