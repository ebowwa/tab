# Tab

A group-expense ledger whose agent **never writes to your data directly**.

Ask it something in plain language. It returns a *plan*. The plan is validated, shown to
you as a list of proposed changes, and nothing is committed until you press Apply. Every
commit snapshots the previous ledger — on disk, alongside the ledger itself — so Undo still
works after the process has been killed.

The point isn't the ledger. It's the pattern: an LLM agent that can act on real data
without being trusted with it.

## The three boundaries

1. **The model proposes, it does not execute.** Its output is data — a JSON plan — never a
   side effect.
2. **Every action is validated against real state.** A payer who isn't a member, an
   expense id that doesn't exist, a non-positive amount: each is rejected with a visible
   note instead of being applied. See `PlanParser`.
3. **Writes are transactional and reversible — including across a restart.** A plan applies
   as one batch, and the whole batch can be undone as one step. The undo history is
   persisted *with* the ledger, because keeping it in memory meant a commit survived process
   death while its undo snapshot did not. See `LedgerStore`.

## Architecture

```
:app                  thin — window, launch intent, and the AppFunctions service
:feature:ledger       Compose UI, view model, the confirm/apply/undo surface
:core:agent           engines, prompt, plan parsing, metrics
:core:data            persistence, undo stack, and the shared staging queue
:core:model           pure data + balance math, no Android, no I/O
```

The dependency rule is one-directional: `app → feature → core`, and `core:agent` and
`core:data` both depend on `core:model`, which depends on nothing.

Three deliberate seams:

- **`PlanEngine`** is an interface. `NanoEngine` runs Gemini Nano through ML Kit GenAI on
  the device; `GeminiRestEngine` calls the Gemini Developer API. Same system instruction,
  same JSON contract, same validation — only the token source changes.
- **ML Kit is an implementation detail of `:core:agent`.** It is declared `implementation`,
  not `api`, and its download states are remapped to `ModelDownload` so no module above it
  ever imports an ML Kit type.
- **One staging queue, two agents.** `LedgerStore.staged` is shared: the in-app agent and
  the system Gemini (via AppFunctions) both write there, and neither can commit. Only the
  UI's Apply drains it.

## On-device model support

Gemini Nano is not available on every device, and the marketing family name is not the
compatibility unit — a Pixel 11 Pro runs it, a Pixel 10a does not. The app detects this at
runtime and falls back to the REST engine if an API key is present.

Detection, the foreground-only rule (`ErrorCode 30`), the `FEATURE_NOT_FOUND` trap, and
the metrics methodology are all written up in **[docs/on-device-notes.md](docs/on-device-notes.md)**.
If you are getting `Feature 636 is not available` or `Background usage is blocked`, read
that first.

## Build

Requires JDK 21, Android SDK with platform 37, and Gradle 9.x.

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties   # gitignored

./gradlew :app:assembleDebug
./gradlew test                                     # parser + ledger math
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

First run on a supported device offers a one-time Gemini Nano download.

## Driving it without the UI

Useful for demos and benchmarks — the activity accepts an intent extra:

```bash
adb shell 'am start -n com.ebowwa.tab/.MainActivity \
  --es prompt "I paid 84 dollars for dinner, split it with Ana and Ben"'
```

The single quotes matter: without them `adb` splits the prompt into separate arguments and
the app receives only the first word.

**The device must be awake and unlocked.** ML Kit GenAI refuses to run without a visible
foreground activity and fails with `ErrorCode 30`. For repeated runs:

```bash
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell svc power stayon true
```

## Inference metrics

Every run logs one line:

```
Tab: metrics prompt="..." engine="Gemini Nano · on-device" ok=true \
     promptTokens=437 outputTokens=61 totalTokens=498 ttftMs=226 totalMs=756 tokPerSec=115.1
```

- `ttftMs` — time to first token, i.e. prefill cost.
- `tokPerSec` — **decode** throughput: `outputTokens / ((totalMs - ttftMs) / 1000)`.

Dividing output tokens by total wall-clock folds prefill into the rate and understates
throughput; this app streams specifically so the two phases can be separated. See
`tools/bench.sh` to run a suite and aggregate.

## Letting Gemini drive it

Studio also registers **AppFunctions**, so the system Gemini agent can drive it:

```
@Studio build me a snake game you steer by tapping left or right
→ build_game("a snake game you steer by tapping left or right")
```

Verified through the platform on a Pixel 11 Pro:

```
$ adb shell "cmd app_function execute-app-function --package com.ebowwa.tab \
    --function 'com.ebowwa.tab.appfunctions.StudioAppFunctions#build_game' \
    --parameters '{\"prompt\":\"a snake game you steer by tapping left or right\"}'"
"Opening Studio to build \"a snake game...\". It generates on-device with Gemini Nano."
```

One constraint shapes this design: **ML Kit GenAI is foreground-only** (see
[docs/on-device-notes.md](docs/on-device-notes.md) §3), and AppFunctions run in a service,
so a function cannot generate. `build_game` therefore queues the prompt and the Activity
consumes it on next open. Background activity starts are also blocked, so the reliable path
is: Gemini queues it, you tap Studio, and it is already building.

## Status

Working: on-device HTML game generation with validation, WebView sandboxing, undo across
generations, live tok/s metrics, and **AppFunctions** so the system Gemini agent can ask
Studio to build something.

Not yet done:

- Single-shot generation: a broken game needs a re-prompt, not a repair loop.
- The `core:data` / ledger modules are now unused by the feature surface and should be
  removed or repurposed.
- The ledger's staged-apply machinery still exists and is the better shape for any future
  surface that mutates stored data; the studio surface does not need it.

## License

MIT — see [LICENSE](LICENSE).
