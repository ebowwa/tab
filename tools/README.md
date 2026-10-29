# `bench.sh` — on-device LLM inference benchmark harness

Host-side harness for `com.ebowwa.tab` (expense-ledger agent, Gemini Nano via ML Kit GenAI)
on the Pixel 11 Pro. It drives the installed app over adb, one prompt per cold start, and
reads the one `metrics` line the app logs to logcat.

Nothing outside this directory is touched: no app source, no Gradle files, no device
settings. adb is used read-only (`logcat -d`, `getprop`, `dumpsys`, `pm list`) plus
`am force-stop` / `am start`, which is the app's own intent entry point. It never installs,
uninstalls, wipes or reconfigures anything.

## What it measures

Per run (one prompt, one cold start):

| field | meaning |
| --- | --- |
| `promptTokens` / `outputTokens` / `totalTokens` | token counts reported by the engine |
| `ttftMs` | time to first token (prefill) |
| `totalMs` | whole generation, prefill + decode |
| `tokPerSec` | decode throughput (see formula below) |

Per prompt it reports the mean `tok/s`, mean TTFT and mean total over the successful
runs; then a grand mean over every successful run. `ok=false` runs are recorded in the
CSV but excluded from the aggregates.

### The tok/s formula (exact)

`tok/s` is **decode throughput**, not output-tokens-over-wall-clock:

```
decodeMs  = (ttftMs > 0) ? (totalMs - ttftMs) : totalMs
tokPerSec = (outputTokens > 0 && decodeMs > 0) ? outputTokens * 1000.0 / decodeMs : 0.0
```

So it is output tokens over the time actually spent decoding, with prefill removed. It is
therefore *higher* than `outputTokens / (totalMs/1000)`, and it excludes app startup,
prompt construction and intent handling entirely. It is a throughput number for the decode
phase — not an end-to-end latency, and not a quality measure.

The harness reads the `tokPerSec` the app logged and independently recomputes it with the
formula above; if the two disagree by more than 1 % (or 0.15 absolute) it warns on stderr
and keeps the logged value. A mismatch means the app's instrumentation changed.

## Requirements

- `adb` on `PATH` with a device connected and **authorized** (`$ANDROID_SERIAL`, or pass `-d`)
  (`adb devices` shows `device`, not `unauthorized`)
- `python3` (used for parsing, CSV and float aggregation)
- the app installed and its `metrics` instrumentation present

## Run it

```bash
cd tools   # from the repo root

./bench.sh                       # 3 repeats x 5 built-in prompts
./bench.sh -n 10                 # more repeats
./bench.sh -o /tmp/run.csv       # different CSV
./bench.sh -n 2 -- "Who owes me money?" "Delete the coffee expense"
./bench.sh --help                # usage only, never touches the device
```

| option | default | meaning |
| --- | --- | --- |
| `-n <repeats>` | `3` | repetitions per prompt |
| `-d <serial>` | `$ANDROID_SERIAL`, else the only connected device | adb device serial |
| `-o <csv>` | `./bench-results.csv` | CSV path, **truncated** at start |
| `-t <seconds>` | `60` | per-run timeout waiting for the metrics line |
| `--` | — | everything after it is a prompt; none ⇒ built-in suite |

Built-in suite: the five prompts in `DEFAULT_PROMPTS` (dinner split, coffee split,
"Who owes me money?", "Delete the coffee expense", "What's the total so far?").

The script prints the exact reproduce command at the top of every run.

## The log contract

One line per inference, tag `Tab`:

```
Tab : metrics prompt="<prompt>" engine="<engine label>" ok=<true|false> promptTokens=<int> outputTokens=<int> totalTokens=<int> ttftMs=<long> totalMs=<long> tokPerSec=<double>
```

Real example from the device:

```
10-29 12:58:53.500  4786  4786 I Tab     : metrics prompt="I paid 84 dollars for dinner, split it with Ana and Ben" engine="Gemini Nano · on-device" ok=true promptTokens=437 outputTokens=61 totalTokens=498 ttftMs=232 totalMs=758 tokPerSec=116.0
```

Both `prompt` and `engine` are **quoted fields** and must be parsed as such — the engine
label contains spaces and a middle dot (`Gemini Nano · on-device`) and the prompt contains
spaces and commas, so splitting on whitespace breaks immediately. The app also sanitises
the prompt before logging it (`"` → `'`, newline → space), so the quoted field can never
contain an escaped quote; the harness applies the same transformation when matching a
logged line back to the prompt it sent, and refuses to adopt a *different* prompt's line.

## How a run works

1. `adb -s <serial> logcat -c` — clear the buffer (warns if a metrics line survives)
2. `adb -s <serial> shell am force-stop com.ebowwa.tab`
3. `adb -s <serial> shell 'am start -n com.ebowwa.tab/.MainActivity --es prompt "<PROMPT>"'`
4. **poll** `logcat -d` every 0.4 s for the `metrics` line, up to `-t` seconds — no fixed
   sleep, so a fast run is recorded immediately and a hung one fails cleanly
5. parse it, print one aligned table row, append one CSV row

Step 3 is the trap: the whole `am start` string must reach the **device** shell as one
argument so the device shell (not the host) does the word splitting. If the inner double
quotes are lost, `--es prompt` stores only the first word and the app answers a truncated
prompt. `bench.sh` builds one host-quoted string with the prompt escaped for a
double-quoted device-shell context (`\`, `"`, `$`, `` ` ``), which is equivalent to
single-quoting the line and is also safe for prompts containing `$` or backticks.

## CSV

Header, then one row per run (path from `-o`, truncated at start):

```
timestamp,prompt,engine,ok,promptTokens,outputTokens,totalTokens,ttftMs,totalMs,tokPerSec
```

`timestamp` is UTC ISO-8601 (`2026-10-29T20:02:25Z`); fields are RFC-4180 quoted by
python's `csv` writer (prompts contain commas). `ok` stays the literal `true`/`false`.

## Exit codes

| code | meaning |
| --- | --- |
| `0` | all runs recorded |
| `2` | usage error (bad option/value) |
| `3` | device not connected/authorized/offline, package not installed, `am start` failed, or adb lost mid-run |
| `4` | no metrics line for a run within `-t` seconds — the failing prompt is named, with a logcat command to diagnose |

## Offline verification

Two env knobs exist for testing the harness itself:

```bash
# parse a captured/synthetic logcat dump instead of talking to a device
BENCH_REPLAY_FILE=/tmp/fixture.logcat ./bench.sh -n 2 -o /tmp/replay.csv

# exercise the timeout/failure path without breaking a real run
BENCH_LOG_TAG=NoSuchTag ./bench.sh -n 1 -t 2
```

`BENCH_REPLAY_FILE` never calls adb and prints a loud `REPLAY` banner. Because the dump is
static, every repeat of the same prompt resolves to the newest matching line — it verifies
parsing, CSV and aggregation, not device timing.

## Traps found while building this (all handled)

- **`am start` quoting** — losing the inner double quotes truncates the prompt to its
  first word. Verified on device: `Starting: Intent { ... (has extras) }`.
- **Quoted fields** — `engine="Gemini Nano · on-device"` and comma-laden prompts break any
  whitespace split; both fields are extracted by regex as quoted strings first.
- **A prompt containing `metrics prompt=`** — a decoy inside the prompt text does not fool
  the parser, because the real field is matched first.
- **CRLF** — adb output can carry `\r`; lines are stripped before parsing, so `prompt` and
  `engine` never pick up a trailing carriage return.
- **SIGPIPE + `pipefail`** — `adb ... | grep -q pattern` or `awk '...; exit'` closes the
  pipe on first match, the producer dies of SIGPIPE (141) and `set -euo pipefail` reports a
  *false failure*. This made a working app look "not installed". Fixed by capturing output
  and matching with `case`, and by never `exit`-ing inside `awk`.
- **Stale buffer** — if `logcat -c` fails, a previous run's line could be read; the harness
  warns, and also drops lines logged before the launch second.

## Caveats

- One device, one app build, no thermal or CPU-state control: these are single-shot
  measurements, not a controlled experiment. Compare runs taken close together.
- The app is stateful (an expense ledger), so later prompts in the suite see the effects of
  earlier ones. That is the app's real behaviour, but it means prompt order matters.
- `tok/s` is decode throughput as defined above; do not compare it against
  output-tokens-over-total-time numbers from other tools.
