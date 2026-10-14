# DrGram camera, voice, UI and policy remediation plan

Branch: `fix/drgram-beauty-voice-blur-2026`

## Findings confirmed from source review

- The photo beautifier is a CPU bitmap post-processor with approximate face boxes; it is not a landmark-guided, real-time skin/face pipeline. This explains why it can look artificial or miss facial regions.
- The story recorder already owns the camera/recording pipeline. Replacing it wholesale with CameraX would be high risk; real-time filters should be integrated into the existing preview and encoded-frame path, with matching preview/output transforms.
- The voice processor operates on 16-bit PCM buffers and changes pitch through a delay-line shifter. That technique can introduce phase/metallic artifacts and is not equivalent to pitch + formant-preserving voice conversion. Calls and voice messages use different session lifecycles and must be tested independently.
- DrGram preferences backup converted every value to a string, then restored everything with `putString`. This breaks typed SharedPreferences and can cause ClassCastException in features expecting booleans, numbers or sets.
- Telegram's built-in blur for media spoilers is a separate feature from blurred chat screens. Chat-screen blur needs diagnosis at the theme/window/background layer; globally disabling Telegram's spoiler blur would be the wrong fix.
- The reviewed `DrGramSmartReply` module is a local, rule-based command responder (not an AI endpoint client): private-chat auto-replies and group commands are opt-in and disabled by default. Keep those controls explicit, restrict group administration commands to authorized admins, and avoid presenting this module as AI Smart Reply. If a separate AI-backed feature sends Telegram content to a provider, audit its consent and data minimization separately.

## Changes in this branch

- Preserve primitive types and string sets in DrGram backup files, while retaining restore compatibility with legacy string-only backups.
- Reject backup preference namespaces outside the `drgram_*` prefix so a crafted backup cannot overwrite Telegram's own preference files.
- Validate voice mode/echo values both when they are saved and when they are read from persisted preferences; clear stale recording/call DSP state when settings change.
- Increase contrast for settings row titles.
- Change live camera face weighting to return zero when face detection fails. This prevents the GPU skin-smoothing pass from treating the whole frame as a face and softening backgrounds, hair and clothes.
- Change still-photo beautification to leave the bitmap untouched when both face detectors fail, instead of globally smoothing skin-coloured regions.
- Make the master beauty toggle disable nose/teeth retouching as well as skin smoothing.
- Use restrained defaults for new installs (skin smoothing 0.38, nose shaping off, teeth whitening 0.18); existing saved user preferences are preserved.
- Remove an unconditional `|| true` from the attachment-sheet blur fallback so its existing setting and Lite Mode conditions can take effect.

## Build status

GitHub Actions is currently failing before any job steps start: the job has no assigned runner and reports an empty step list. This does not identify a source compilation error. The latest changed source has therefore **not** been confirmed by a successful build yet.

## Required next implementation stages

1. **Beauty engine:** replace ellipse-only face estimation with face landmarks; implement skin smoothing with edge preservation, under-eye tone control, natural teeth whitening, subtle lighting, and restrained face shaping. Add intensity presets and a live before/after toggle. Keep the result natural, with no extreme default reshaping.
2. **Stories and live video:** integrate a GPU shader/effect stage in the existing recorder pipeline so preview and encoded frames receive the same effect. Offer original-color LUTs, soft glow, skin smoothing, warm/cool looks, and AR overlays only where supported. Device-specific camera extensions can be a fallback, not the sole implementation.
3. **Video messages:** apply the same effect to the circular video-message recording path and its encoded frames; confirm no audio/video desynchronization, dropped-frame spikes, or memory leaks.
4. **Voice changer:** add deterministic PCM tests for silence, tone, speech-length buffers, odd byte lengths, 16/48 kHz, clipping and repeated start/stop. Separate pitch shifting from formant/EQ and echo. Test outgoing voice messages, calls and group voice chats separately; add bypass/fail-safe behavior.
5. **Chat blur:** reproduce on a clean install and inspect theme/background/blur overlay setup in ChatActivity and chat cells. Add a setting-level regression test and ensure media spoiler blur continues working.
6. **UI contrast:** use high-contrast foreground tokens, opaque-enough card surfaces, readable disabled states, and verify both light/dark themes and large-font accessibility.
7. **Smart Reply / Telegram terms:** keep it user-triggered; disclose the provider and endpoint; show exactly what text will be sent; never upload full chat history by default; provide a local/off switch; do not train or fine-tune models on Telegram content; never prevent self-destructing content from disappearing or alter read/online/typing semantics. Obtain and use the app's own Telegram api_id/api_hash and clearly disclose this is an unofficial client.

## Validation gate before calling a release ready

- CI release APK build succeeds on this branch.
- Install and launch on at least one current ARM64 Android device.
- Manual tests: camera permissions, photo capture, story capture/playback, live video, video message send/playback, front/rear camera switching, app background/foreground, call start/stop, Bluetooth route, interruption, low storage, and repeated toggling of every voice mode.
- Compare original and processed media; verify readable settings labels at all supported themes and display sizes.
- Review signing keys and credentials: no real release keystore/passwords or unrestricted API keys should be committed.
