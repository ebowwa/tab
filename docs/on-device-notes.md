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

Observed on a Pixel 11 Pro, `nano-v4-full`, 15 runs via `tools/bench.sh` (3 per prompt):

| prompt | out tokens | TTFT | total | decode tok/s |
| --- | --- | --- | --- | --- |
| "I paid 84 dollars for dinner, split it with Ana and Ben" | 61 | 232 ms | 753 ms | 117.0 |
| "Add a 12.50 coffee, I paid, split three ways with Ana and Cy" | 66 | 225 ms | 830 ms | 109.1 |
| "Who owes me money?" | 24 | 196 ms | 287 ms | 264.8 |
| "Delete the coffee expense" | 28 | 225 ms | 412 ms | 149.6 |
| "What's the total so far?" | 29 | 196 ms | 293 ms | 298.1 |

Two things to read off that table carefully:

- **tok/s is not flat.** Short answers (24–29 tokens) report ~265–300 tok/s, long ones
  (61–66) report ~109–117. So quote the range for a given output length, not a single
  headline number — a grand mean of ~188 across this suite is a property of the suite, not
  of the model.
- **TTFT is remarkably stable** (196–232 ms) while prompt length barely moves it across
  these sizes (~426–442 tokens). Prefill is not the bottleneck here; generation length is.

`countTokens` is called twice per run (once for the request, once for the answer text), so
these numbers include that bookkeeping — the wall-clock timings do not.

## 7. AppFunctions: what the KSP processor generates

`androidx.appfunctions:appfunctions:1.0.0-beta01` plus `appfunctions-compiler` as a **KSP**
processor. With AGP 9's built-in Kotlin there is no `org.jetbrains.kotlin.android` plugin to
apply, and KSP's old `<kotlin>-<ksp>` version scheme no longer applies — **KSP 2.3.12** is
what works (KSP ≥ 2.3.1 is the floor for AGP 9 built-in Kotlin).

You hand-write only three things:

1. an **abstract** class extending `androidx.appfunctions.AppFunctionService`, annotated
   `@AppFunctionServiceEntryPoint(serviceName = ..., appFunctionXmlFileName = ...)`, with
   `@AppFunctionDeclaration(isDescribedByKDoc = true)` on each function. Do **not** implement
   `onExecuteFunction` — the processor rejects that.
2. the `<service>` entry in the manifest.
3. the Gradle wiring (`implementation` for the runtime, `ksp(...)` for the compiler — never
   `implementation` for the compiler).

The processor generates the concrete service, the inventory, and the metadata XML, and
packages that XML into `assets/` automatically. Function ids are
`<fully.qualified.ClassName>#<methodName>`. Your KDoc becomes the function description that
the agent reads — so the KDoc *is* the prompt.

Signatures accept `Int/Long/Float/Double/Boolean/String/Unit`, the primitive arrays,
`ByteArray`, `java.time.*`, `android.net.Uri`, `List<String>`, and any
`@AppFunctionSerializable` class. Nullable parameters are rejected at compile time, as is
`List<Int>` (only `List<String>`).

Two traps:

- **No constructor injection.** The generated subclass calls `: Base()`, so the base needs a
  no-arg constructor — reach dependencies lazily through `applicationContext`.
- **The service runs on `Dispatchers.Main`**, so wrap disk/database work in
  `withContext(Dispatchers.IO)`.

Because the service and the Activity are separate components, give the data layer a
process-wide singleton — otherwise a write made through AppFunctions is invisible to the
running UI.

### Writes should stage, not commit

The tempting shape is `add_expense(...)` that writes. Resist it: that hands the system agent
a commit path your own UI never got. Here every AppFunction only *stages* into the shared
queue, so the AppFunctions surface is exactly as untrusted as the in-app model — Gemini can
propose, only the human can apply.

## 8. Driving AppFunctions from adb: the quoting trap

`cmd app_function execute-app-function` takes parameters as **plain JSON scalars**, one per
parameter — `{"id":"abc"}`, not `{"id":{"value":"abc"}}`. Object-wrapping is rejected:

```
Parameter id should be the type of AppFunctionStringTypeMetadata(isNullable=false, ...) (code 1001)
```

But the failure that actually wastes an afternoon looks like a *type* problem and is not:

```
org.json.JSONException: Value description of type java.lang.String cannot be converted to JSONObject
```

That is **adb shell splitting your JSON on the commas.** `adb shell "..."` runs the payload
through the device shell, so an unescaped `'{"a":"b","c":8400}'` arrives as seven separate
argv fragments and the service receives only the first one.

The tell — and the reason this fools people — is that a **single-key** object works. With
one key there are no commas to split, and `org.json`'s lenient parser turns the mangled
`{id:abc}` back into `{"id":"abc"}`. So it looks like "strings work, numbers don't", and you
go hunting for a numeric-parameter bug that does not exist.

The working form, verified on a Pixel 11 Pro / API 37:

```bash
adb -s "$DEVICE" shell "cmd app_function execute-app-function \
  --package com.ebowwa.tab \
  --function 'com.ebowwa.tab.appfunctions.TabAppFunctions#add_expense' \
  --parameters '{\"description\":\"Dinner\",\"amountCents\":8400,\"paidBy\":\"You\",\"participants\":[\"You\",\"Ana\",\"Ben\"]}'"
```

Outer double quotes, single-quoted `--function` and `--parameters`, and `\"` for every quote
inside the JSON. Rules worth knowing once it works:

- `Long` needs a JSON integer: `8400`. `"8400"` and `8400.0` are rejected.
- `Double` needs a real float: `12.5`. The integer `12` is rejected.
- `List<String>` is lenient — a bare `"You"` is accepted.
- Unknown keys are silently ignored.

The general lesson outranks the specifics: before concluding "the platform is broken", print
the argv the device actually received. Every failed diagnostic in this file started with
trusting a tool's *reported* behaviour instead of its inputs.
