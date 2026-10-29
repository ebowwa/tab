# On-device notes: Gemini Nano via ML Kit GenAI

Hard-won findings from getting this app running on real hardware. Most of these cost a
debugging cycle each, and several are not obvious from the API surface.

## 1. Device support is a short, explicit list — and the "a" models aren't on it

Gemini Nano is not a general Android feature. ML Kit GenAI publishes a per-model-tier
allowlist ([supported devices](https://developers.google.com/ml-kit/genai)):

| tier | Google devices |
| --- | --- |
| nano-v2 | — |
| nano-v3 | Pixel 10, 10 Pro, 10 Pro XL, 10 Pro Fold, Pixel 9, 9 Pro, 9 Pro XL, 9 Pro Fold |
| nano-v4 | Pixel 11, 11 Pro, 11 Pro XL, 11 Pro Fold, Galaxy Z Flip8 / Fold8 / Fold8 Ultra |

`Pixel 10a` and `Pixel 11a` do not appear at any tier. A **Pixel 10a** reports the model
as unavailable; a **Pixel 11 Pro** reports `baseModel=nano-v4-full` and works.

Check the list before assuming a device will work — the marketing family name is not the
compatibility unit.

## 2. `checkStatus()` throws instead of returning `UNAVAILABLE`

The documented mitigation is "if the feature is unavailable, call `download()`". On an
unsupported device the status call itself throws:

```
GenAiException: [ErrorCode 606] AICore failed with error type 3-PREPARATION_ERROR
                and error code 606-FEATURE_NOT_FOUND: Feature 636 is not available.
```

So `checkStatus()` must be wrapped — a thrown exception is the "not supported here"
signal, not an error to surface:

```kotlin
suspend fun status(): ModelStatus = runCatching {
    when (model.checkStatus()) {
        0 -> ModelStatus.UNAVAILABLE
        1 -> ModelStatus.DOWNLOADABLE
        2 -> ModelStatus.DOWNLOADING
        3 -> ModelStatus.AVAILABLE
        else -> ModelStatus.UNKNOWN
    }
}.getOrElse { ModelStatus.UNAVAILABLE }
```

Useful diagnostics that distinguish "unsupported" from "not downloaded yet":

```kotlin
model.getAvailableFeatures()   // [] on unsupported; [1,2,3,4,5] on a supported device
model.getBaseModelName()       // throws when no model is provisioned; "nano-v4-full" when ready
model.getTokenLimit()          // 32768 on nano-v4
```

On the Pixel 10a: `status=0 features=[] baseModel=err`.
On the Pixel 11 Pro: `status=1 features=[1,2,3,4,5] baseModel=nano-v4-full tokenLimit=32768`.

`Feature` constants: `SYSTEM_PROMPT`, `THINKING_MODE`, `PREFIX_CACHING`,
`STRUCTURED_OUTPUT`, `FUNCTION_CALLING`.

## 3. Foreground only — `ErrorCode 30`

> `[ErrorCode 30] Background usage is blocked. Please use the API when your app is in the
> foreground instead.`

This is the one that bit hardest, because it looks like a code bug and is not.

**ML Kit GenAI refuses to run unless the calling app has a visible foreground activity.**
Consequences:

- No inference from a `Service`, `WorkManager` job, broadcast receiver, or `Application`
  startup. Those all fail with ErrorCode 30 regardless of how correct the call is.
- The device must also be **awake and unlocked**. A dozing/locked phone fails the same way
  even though the activity is technically resumed — from the app's point of view it is not
  in the foreground.
- A first call that succeeds and a second identical call that fails usually means the
  screen slept between them, not that the model broke.

For automated runs over `adb`, do all of this first:

```bash
adb -s "$DEVICE" shell input keyevent KEYCODE_WAKEUP   # 224
adb -s "$DEVICE" shell wm dismiss-keyguard             # only works if there is no PIN
adb -s "$DEVICE" shell svc power stayon true           # keep the screen on while USB-powered
```

`svc power stayon true` is what makes a benchmark loop viable — without it the screen
times out mid-suite and every later run fails with ErrorCode 30.

A locked device with a PIN cannot be unlocked over adb; that is a human step. Verify state
before a run:

```bash
adb -s "$DEVICE" shell dumpsys power   | grep mWakefulness     # want: Awake
adb -s "$DEVICE" shell dumpsys window  | grep mCurrentFocus    # want: your activity
```

## 4. Provisioning: watch the manifest fetch, not the error code

When AICore *does* support the device, the first `download()` pulls a model manifest:

```
ProtectedDownloadGrpcBindableService: Starting GetManifestConfig request
ProtectedDownloadProcessorImpl: downloading manifest with public key hash ...
ProtectedDownloadGrpcBindableService: Successfully handled GetManifestConfig
→ status flips 1 (DOWNLOADABLE) → 3 (AVAILABLE)
```

On an unsupported device the same path fails at the fetch and repeats:

```
lxj: Failed to fetch manifest config from source for com.google.android.aicore:...
```

That single line distinguishes "no manifest exists for this device" from a network or
account problem. Verify network and accounts separately before blaming them — in our case
LTE was validated and two Google accounts were signed in, and the fetch still failed,
because there was no manifest to fetch.

## 5. Function calling is available on-device

`GenerateContentRequest.Builder.setTools(List<FunctionDeclaration>)` works with Gemini
Nano, and `Candidate.getFunctionCalls()` returns the calls. `Feature.FUNCTION_CALLING`
reports availability. This app does not use it yet (see roadmap) — it uses a
constrained-JSON planning contract instead, which needs no schema compiler.

## 6. Measuring throughput honestly

Two numbers matter and they are not interchangeable:

- **TTFT** (time to first token) — dominated by prompt prefill. Grows with prompt size.
- **Decode tok/s** — `outputTokens / ((totalMs - ttftMs) / 1000)`.

Dividing output tokens by *total* wall-clock silently folds prefill into the rate and
understates throughput, badly so for long prompts. This app streams specifically so it can
timestamp the first callback and separate the two phases.

Observed on a Pixel 11 Pro, `nano-v4-full`, 437-token prompt, 61-token JSON answer:

```
promptTokens=437 outputTokens=61 ttftMs=226 totalMs=756 tokPerSec=115.1
```
