# Bounded optional battery speech

Root technical review approved this D1 slice. Default mode OFF; connection and low-battery switches
also default false. Mode ON or SCREEN_OFF (“屏幕关闭时”, !PowerManager.isInteractive) is an explicit
choice. Utterance-volume presets25/50/75/100% default50 and never change stream volume.

AndPods4.3.8 sources: entity/TTSConfig.java9–15 supplies disabled defaults and50% multiplier;
entity/TTSSwitchEnum.java declares OPEN/LOCK_ONLY/CLOSE. service/BackgroundService.java441–450
implements optional connection greeting,549 resets lowBatteryHint,1193–1209 tests0<level<20 on
left/right. util/TTSSpeaker.java77 onward selects overall vs left/right for explicit battery readout.
We correct the first-high-sample latch and honor typed0 as a valid low reading; case-only and unknown
never trigger. No app-notification reading or custom greeting in this slice; no copied code/assets.

The existing non-UI NativePresentationRuntime receives authenticated reports. New coordinator
tracks bounded per-physical-connection history. At most one connection announcement within10s and
one first low(0–19%) alert per connection. Only changed battery projection can trigger the latter;
charging components excluded. System aggregate provenance suppresses duplicated buds; private
component values preserved. Cold replay establishes silent baseline, low replay consumes low alert.
Motion/revision-only updates never speak. Preference changes cancel and baseline existing state.
Connection and low alert in one event merge. No pending utterance queue; blocked events are consumed.

Require MODULE/HYPEREARS ownership, live producer, same session, enabled adapter, connected profile,
normal audio mode, exact MAC match for BOTH media and navigation-guidance output routes. Respect
media stream mute/zero, non-normal ringer and any DND mode other than ALL; unknown access fails closed.
Apple conversation start cancels. Settings disable, replay replacement, disconnect/unpair, owner,
producer, route, call, screen-mode, mute, DND and focus changes stop ongoing speech.

Lazy TextToSpeech instance per utterance, no persistent service/engine/retry. Simplified Chinese
must be available. NAVIGATION_GUIDANCE/SPEECH attributes are used for TTS and transient MAY_DUCK
focus; delayed focus disabled. No focus grant means no speech. SDK35 AudioAttributes.java1766–1768
maps navigation guidance to STREAM_MUSIC; TextToSpeech.java68–74 requires shutdown and engine
visibility. Missing host engine visibility/language is skipped, with no permission/scope workaround.
One10s deadline covers creation/init/speech. Revalidate after async init and immediately before speak.
Every callback owns a unique generation/engine; stale callbacks never close a newer engine. Init may
fire synchronously, and focus may be lost reentrantly; tests cover both. Done/error/expiry/cancel
stop+shutdown owned engine, release granted focus and all observers. speakSUCCESS only means queued.

Files: speech settings enum/store and two UI views; BatterySpeechRuntime, BatterySpeechUtterance,
AndroidBatterySpeechHost; minimal NativePresentationRuntime update/cancel calls; tests and user docs.
Root owns Gradle/lint/release, commits and source freezes. No phone operation or audible validation.
