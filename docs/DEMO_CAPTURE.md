# Vision demo capture notes

This is a scripted demonstration of the real app on a fresh Android 9/API 28 x86_64 emulator. Commands are filled and submitted by Android instrumentation; outcomes come from unchanged production code. It is not a human-typing, real-phone, live-cloud or free-form autonomy benchmark.

The flow checks device time, the bounded multi-action refusal, and Back cancellation of a calendar confirmation. Calendar-write permission is denied throughout, and the confirmation never reaches its permission/write branch. A reply, message, call or calendar event was not sent/created by this demonstration.

The video adds explanatory captions outside the app display. Original captures and test outcome evidence are preserved separately. No app screen text is replaced. The product still shows the hardcoded "Good evening, Yash" greeting during morning device time and "Tony" in the confirmation title; those are existing UI details, not capture defects fixed here.

No cloud key or paid API was used. No app source was changed. The temporary capture branch contains workflow and instrumentation changes; those are not part of this documentation update. The reviewed recording and screenshots are included in this repository, not a new app release.

## Evidence

- Successful final capture: https://github.com/Yash1bajpai/vision_app/actions/runs/40297383437
- Capture commit: 617134f8ae8113e52b4aa60de3381c028bb5f661. Production code identical to main baseline 0dad586.
- Raw MP4 is 69.599 seconds, unchanged sequence. Captioned MP4 adds only outer captions and frame-rate conversion, no spliced outcomes. Silent, captioned version.
- Five original PNGs and evidence.txt are preserved in docs/demo. The capture run reports OK (1 test). The review archive also preserves raw MP4, runner output, Android version and capture commit.
- Earlier attempts fixed capture assertions, Gradle post-test removal of screenshots and emulator framebuffer sizing. Do not use those earlier recordings as the final demo.
- The capture workflow/artifact is on the approved public repository branch; artifact downloads require GitHub access and are retained three days. This is not a secret-storage claim. No private account data was loaded into the emulator.
- The temporary capture branch remains for inspection. No capture workflow or test-side driver is merged into main by this documentation update.
