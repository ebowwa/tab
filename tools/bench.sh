#!/usr/bin/env bash
#
# bench.sh — host-side benchmark harness for com.ebowwa.tab on-device LLM inference.
#
# Drives the installed app over adb, one prompt per cold start, and reads the single
# "metrics" line the app logs to logcat (tag: Tab). No app source, Gradle file or
# device setting is touched: adb is used read-only (logcat -d, getprop, dumpsys) plus
# force-stop/launch, which is what the app's own intent path is for.
#
# Contract this harness depends on (emitted by TabAgent.logMetrics):
#   Tab : metrics prompt="<prompt>" engine="<label>" ok=<true|false> \
#         promptTokens=<int> outputTokens=<int> totalTokens=<int> \
#         ttftMs=<long> totalMs=<long> tokPerSec=<double>
#
# The engine label contains spaces and a middle dot, and the prompt contains spaces
# and commas, so both are parsed as *quoted fields*, never by splitting on whitespace.
#
set -euo pipefail

BENCH_VERSION="1.0.0"

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SELF="$SELF_DIR/$(basename "${BASH_SOURCE[0]}")"

PACKAGE="com.ebowwa.tab"
ACTIVITY=".MainActivity"
LOG_TAG="${BENCH_LOG_TAG:-Tab}"   # overridable only to test the timeout path

# ---------------------------------------------------------------- defaults ---
REPEATS=3
SERIAL="${ANDROID_SERIAL:-}"   # -d, $ANDROID_SERIAL, or the single connected device
OUT_CSV="$SELF_DIR/bench-results.csv"
TIMEOUT_S=60

DEFAULT_PROMPTS=(
  "I paid 84 dollars for dinner, split it with Ana and Ben"
  "Add a 12.50 coffee, I paid, split three ways with Ana and Cy"
  "Who owes me money?"
  "Delete the coffee expense"
  "What's the total so far?"
)

# ---------------------------------------------------------------- helpers ----
usage() {
  cat <<EOF
bench.sh $BENCH_VERSION — benchmark on-device LLM inference in $PACKAGE over adb.

USAGE
  $(basename "$SELF") [-n repeats] [-d serial] [-o csv] [-t timeout] [-- prompt ...]
  $(basename "$SELF") --help

OPTIONS
  -n <repeats>    Repetitions per prompt (default: $REPEATS)
  -d <serial>     adb device serial (default: $ANDROID_SERIAL, else auto-detect)
  -o <csv path>   CSV output path (default: $OUT_CSV); truncated at start
  -t <seconds>    Per-run timeout waiting for the metrics line (default: $TIMEOUT_S)
  -h, --help      Print this usage and exit (does not touch the device)
  --              End of options; every following argument is a prompt.
                  With no prompts supplied, the built-in 5-prompt suite is used.

BUILT-IN SUITE
$(printf '  - %s\n' "${DEFAULT_PROMPTS[@]}")

WHAT IT DOES (per prompt, per repeat)
  1. adb -s <serial> logcat -c            (clear the log buffer)
  2. adb -s <serial> shell am force-stop $PACKAGE
  3. adb -s <serial> shell 'am start -n $PACKAGE/$ACTIVITY --es prompt "<PROMPT>"'
  4. poll logcat -d for the "metrics" line, up to -t seconds (no fixed sleeps)
  5. parse it as quoted fields and append one row to the CSV

OUTPUT
  Aligned table (one row per run), per-prompt aggregates (mean tok/s, mean TTFT,
  mean total) and a grand mean. tok/s is the app's decode throughput:
      tokPerSec = outputTokens / ((totalMs - ttftMs) / 1000)
  with decodeMs = totalMs when ttftMs <= 0, and 0.0 when outputTokens <= 0.

EXIT CODES
  0 success · 2 usage error · 3 device/package problem · 4 no metrics line for a run

ENV
  BENCH_REPLAY_FILE=<path>  Offline/replay mode: read a captured logcat dump from
                            this file instead of talking to a device. Used to verify
                            the parser against a fixture. Never touches adb.
  BENCH_LOG_TAG=<tag>       logcat tag to read (default: Tab). Point it at a tag that
                            never logs to exercise the timeout/failure path.

EXAMPLES
  $(basename "$SELF")                              # 3 repeats x 5 built-in prompts
  $(basename "$SELF") -n 10 -o /tmp/run.csv
  $(basename "$SELF") -n 2 -- "Who owes me money?" "Delete the coffee expense"
EOF
}

die() {
  local code="${2:-1}"
  printf 'bench.sh: error: %s\n' "$1" >&2
  exit "$code"
}

warn() {
  printf 'bench.sh: warning: %s\n' "$*" >&2
}

is_pos_int() {
  case "$1" in
    ''|*[!0-9]*) return 1 ;;
    *) (( $1 > 0 )) ;;
  esac
}

# Escape a string for use inside a *device-shell* double-quoted context.
# The whole `am start` line is handed to `adb shell` as one argument, so the device
# shell (not the host shell) does the word splitting; the prompt must therefore stay
# inside double quotes with backslash, quote, dollar and backtick escaped.
dq_escape() {
  # shellcheck disable=SC2016  # the backtick is literal: it is what we escape
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e 's/\$/\\$/g' -e 's/`/\\`/g'
}

# ---------------------------------------------------------------- options ----
PROMPTS=()
while (( $# )); do
  case "$1" in
    -n)
      [[ $# -ge 2 ]] || die "-n requires a value" 2
      is_pos_int "$2" || die "-n expects a positive integer, got: $2" 2
      REPEATS="$2"; shift 2 ;;
    -d)
      [[ $# -ge 2 ]] || die "-d requires a value" 2
      SERIAL="$2"; shift 2 ;;
    -o)
      [[ $# -ge 2 ]] || die "-o requires a value" 2
      OUT_CSV="$2"; shift 2 ;;
    -t)
      [[ $# -ge 2 ]] || die "-t requires a value" 2
      is_pos_int "$2" || die "-t expects a positive integer (seconds), got: $2" 2
      TIMEOUT_S="$2"; shift 2 ;;
    -h|--help)
      usage; exit 0 ;;
    --)
      shift
      if (( $# )); then PROMPTS=("$@"); else PROMPTS=(); fi
      break ;;
    *)
      die "unknown option: $1 (try --help)" 2 ;;
  esac
done

if (( ${#PROMPTS[@]} == 0 )); then
  PROMPTS=("${DEFAULT_PROMPTS[@]}")
fi

NPROMPTS=${#PROMPTS[@]}
TOTAL_RUNS=$(( NPROMPTS * REPEATS ))

REPLAY_FILE="${BENCH_REPLAY_FILE:-}"

# ------------------------------------------------------------- python core ---
# All log parsing and numeric formatting lives here so that quoted fields, CSV
# quoting and float aggregation are handled by one implementation. Invoked as
# `python3 -c "$PY_SRC" <mode> ...` so stdin stays free for the logcat dump.
PY_SRC=$(cat <<'PYEOF'
import csv
import re
import sys

W_PROMPT, W_ENGINE = 34, 26
ROW_FMT = "{:>7} {:<34} {:<26} {:<5} {:>6} {:>7} {:>7} {:>7} {:>9}"
NUM_RE = re.compile(r"(\w+)=(-?\d+(?:\.\d+)?|true|false)")
TS_RE = re.compile(r"^(\d\d-\d\d)\s+(\d\d:\d\d:\d\d)(?:\.\d+)?")


def trunc(text, width):
    text = text.replace("\n", " ").replace("\r", "")
    return text if len(text) <= width else text[: width - 2] + ".."


def sanitize_prompt(text):
    # mirrors TabAgent.logMetrics: '"' -> "'" , newline -> space
    return text.replace('"', "'").replace("\n", " ")


def normalize(text):
    """Whitespace-insensitive comparison key for prompt matching."""
    return " ".join(sanitize_prompt(text).split())


def quoted(line, key):
    m = re.search(re.escape(key) + r'="([^"]*)"', line)
    return m.group(1) if m else None


def parse_line(line):
    """Parse one metrics line. Quoted fields first, then bare key=value pairs."""
    if "metrics" not in line or 'prompt="' not in line or 'engine="' not in line:
        return None
    prompt = quoted(line, "prompt")
    engine = quoted(line, "engine")
    if prompt is None or engine is None:
        return None
    rest = re.sub(r'(?:prompt|engine)="[^"]*"', " ", line)
    kv = dict(NUM_RE.findall(rest))
    need = ("promptTokens", "outputTokens", "totalTokens", "ttftMs", "totalMs", "tokPerSec")
    if any(k not in kv for k in need):
        return None
    return {
        "prompt": prompt,
        "engine": engine,
        "ok": kv.get("ok") == "true",
        "promptTokens": int(float(kv["promptTokens"])),
        "outputTokens": int(float(kv["outputTokens"])),
        "totalTokens": int(float(kv["totalTokens"])),
        "ttftMs": int(float(kv["ttftMs"])),
        "totalMs": int(float(kv["totalMs"])),
        "tokPerSec": float(kv["tokPerSec"]),
    }


def derive_tps(row):
    """The app's own formula: output tokens over decode time (total minus prefill)."""
    out, ttft, total = row["outputTokens"], row["ttftMs"], row["totalMs"]
    decode_ms = (total - ttft) if ttft > 0 else total
    if out <= 0 or decode_ms <= 0:
        return 0.0
    return out * 1000.0 / decode_ms


def mean(values):
    return sum(values) / len(values) if values else None


def fmt(value, spec=".1f"):
    return "n/a" if value is None else format(value, spec)


def mode_header(argv):
    total = argv[0] if argv else "?"
    header = ROW_FMT.format(
        "#/" + total, "PROMPT", "ENGINE", "OK", "pTok", "oTok", "TTFTms", "totalMs", "tok/s"
    )
    print(header)
    print("-" * len(header))


def mode_row(argv):
    label = argv[0]
    expected, csv_path, stamp = argv[1], argv[2], argv[3]
    since = argv[4] if len(argv) > 4 else ""
    dump = sys.stdin.read()

    parsed, malformed = [], []
    for raw in dump.splitlines():
        line = raw.replace("\r", "").strip()
        if "metrics prompt=" not in line:
            continue
        row = parse_line(line)
        if row is None:
            malformed.append(line)
            continue
        m = TS_RE.match(line)
        row["_ts"] = m.group(2) if m else ""
        parsed.append(row)

    if since:
        # Guard against a stale line surviving a failed `logcat -c`: only accept
        # lines logged at or after the moment we launched the app.
        parsed = [r for r in parsed if not r["_ts"] or r["_ts"] >= since]

    want = normalize(expected)
    exact = [r for r in parsed if r["prompt"] == sanitize_prompt(expected)]
    if not exact:
        # Tolerate whitespace/sanitisation drift, but never adopt a *different*
        # prompt's metrics: that would write another run's numbers into this row.
        def similar(p):
            pn = normalize(p)
            return pn == want or (len(want) >= 8 and (want in pn or pn in want))

        near = [r for r in parsed if similar(r["prompt"])]
        if near:
            exact = near
            print(
                "bench.sh: warning: metrics prompt did not match verbatim; using the "
                "closest line (logged prompt: %r)" % exact[-1]["prompt"],
                file=sys.stderr,
            )

    if not exact:
        if parsed:
            print(
                "bench.sh: error: %d metrics line(s) in the dump, none for this prompt"
                % len(parsed),
                file=sys.stderr,
            )
        else:
            print("bench.sh: error: no parseable metrics line in the logcat dump", file=sys.stderr)
            if malformed:
                print("  first unparseable line: %s" % malformed[0], file=sys.stderr)
        return 3
    row = exact[-1]

    if not row["ok"]:
        print(
            "bench.sh: note: this run logged ok=false (engine error); recorded, "
            "excluded from aggregates",
            file=sys.stderr,
        )

    derived = derive_tps(row)
    if derived > 0 and abs(derived - row["tokPerSec"]) > max(0.15, 0.01 * derived):
        print(
            "bench.sh: warning: logged tokPerSec=%.1f disagrees with recomputed %.1f "
            "(outputTokens*1000/decodeMs); keeping the logged value"
            % (row["tokPerSec"], derived),
            file=sys.stderr,
        )

    print(
        ROW_FMT.format(
            label,
            trunc(row["prompt"], W_PROMPT),
            trunc(row["engine"], W_ENGINE),
            "true" if row["ok"] else "false",
            row["promptTokens"],
            row["outputTokens"],
            row["ttftMs"],
            row["totalMs"],
            "%.1f" % row["tokPerSec"],
        )
    )

    with open(csv_path, "a", newline="", encoding="utf-8") as fh:
        csv.writer(fh).writerow(
            [
                stamp,
                row["prompt"],
                row["engine"],
                "true" if row["ok"] else "false",
                row["promptTokens"],
                row["outputTokens"],
                row["totalTokens"],
                row["ttftMs"],
                row["totalMs"],
                "%.1f" % row["tokPerSec"],
            ]
        )
    return 0


def mode_agg(argv):
    path = argv[0]
    with open(path, newline="", encoding="utf-8") as fh:
        rows = list(csv.DictReader(fh))
    if not rows:
        print("  (no rows recorded)")
        return 0

    order, groups = [], {}
    for r in rows:
        p = r["prompt"]
        if p not in groups:
            groups[p] = []
            order.append(p)
        groups[p].append(r)

    def pick(rs, key):
        vals = []
        for r in rs:
            if r["ok"] != "true":
                continue
            try:
                v = float(r[key])
            except (TypeError, ValueError):
                continue
            if v > 0:
                vals.append(v)
        return vals

    agg_fmt = "{:<34} {:>4} {:>4} {:>12} {:>12} {:>13}"
    header = agg_fmt.format("PROMPT", "runs", "ok", "meanTok/s", "meanTTFTms", "meanTotalMs")
    print(header)
    print("-" * len(header))

    all_tps, all_ttft, all_total, n_ok = [], [], [], 0
    for p in order:
        rs = groups[p]
        ok_rs = [r for r in rs if r["ok"] == "true"]
        n_ok += len(ok_rs)
        tps = pick(ok_rs, "tokPerSec")
        ttft = pick(ok_rs, "ttftMs")
        total = pick(ok_rs, "totalMs")
        all_tps += tps
        all_ttft += ttft
        all_total += total
        print(
            agg_fmt.format(
                trunc(p, W_PROMPT),
                len(rs),
                len(ok_rs),
                fmt(mean(tps)),
                fmt(mean(ttft), ".0f"),
                fmt(mean(total), ".0f"),
            )
        )

    print("-" * len(header))
    print(
        "GRAND MEAN  runs=%d  ok=%d  meanTok/s=%s  meanTTFTms=%s  meanTotalMs=%s"
        % (len(rows), n_ok, fmt(mean(all_tps)), fmt(mean(all_ttft), ".0f"), fmt(mean(all_total), ".0f"))
    )
    return 0


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    args = sys.argv[2:]
    if mode == "header":
        mode_header(args)
        return 0
    if mode == "row":
        return mode_row(args)
    if mode == "agg":
        return mode_agg(args)
    print("bench.sh: internal error: unknown python mode %r" % mode, file=sys.stderr)
    return 2


sys.exit(main())
PYEOF
)

py() {
  python3 -c "$PY_SRC" "$@"
}

# ------------------------------------------------------------------ header ---
REPRO="$SELF -n $(printf '%q' "$REPEATS") -d $(printf '%q' "$SERIAL") -o $(printf '%q' "$OUT_CSV") -t $(printf '%q' "$TIMEOUT_S")"
if (( ${#PROMPTS[@]} )); then
  REPRO="$REPRO --"
  for _p in "${PROMPTS[@]}"; do
    REPRO="$REPRO $(printf '%q' "$_p")"
  done
fi

printf 'bench.sh %s — %s on-device inference benchmark\n' "$BENCH_VERSION" "$PACKAGE"
printf 'Reproduce: %s\n' "$REPRO"
printf 'Config:    device=%s  repeats=%s  prompts=%s  timeout=%ss  csv=%s\n' \
  "$SERIAL" "$REPEATS" "$NPROMPTS" "$TIMEOUT_S" "$OUT_CSV"
printf 'Metric:    tok/s = outputTokens / ((totalMs - ttftMs) / 1000)   [app decode throughput]\n'

# ------------------------------------------------------- device / package ----
if [[ -n "$REPLAY_FILE" ]]; then
  [[ -r "$REPLAY_FILE" ]] || die "BENCH_REPLAY_FILE is not readable: $REPLAY_FILE" 3
  printf 'Mode:      REPLAY (no device) from %s\n' "$REPLAY_FILE"
  printf '           ^ synthetic dump: parses and writes CSV, proves nothing about the device\n\n'
else
  command -v adb >/dev/null 2>&1 || die "adb not found in PATH (install platform-tools)" 3

  # NB: no `exit` inside awk — closing the pipe early raises SIGPIPE, which
  # `set -o pipefail` reports as a failure and `set -e` would turn into a silent exit.
  # No serial given: fall back to the only authorized device, and refuse to guess
  # if there is more than one. A hardcoded serial would be a personal identifier.
  if [[ -z "$SERIAL" ]]; then
    local devs count
    devs="$(adb devices | awk 'NR>1 && $2 == "device" { print $1 }')"
    count="$(printf '%s' "$devs" | grep -c . || true)"
    if [[ "$count" == "1" ]]; then
      SERIAL="$devs"
      printf 'bench.sh: using the only connected device: %s\n' "$SERIAL" >&2
    elif [[ "$count" == "0" ]]; then
      die "no authorized device found — connect one, or pass -d <serial>" 3
    else
      die "multiple devices connected — pick one with -d <serial>" 3
    fi
  fi

  state="$(adb devices | awk -v s="$SERIAL" '$1 == s && !found { print $2; found = 1 }')"
  if [[ -z "$state" ]]; then
    printf 'bench.sh: error: device %s is not connected.\n' "$SERIAL" >&2
    printf '  adb devices reports:\n' >&2
    adb devices | sed 's/^/    /' >&2
    # shellcheck disable=SC2016  # backticks are prose here, not a subshell
    printf '  Connect the device over USB (or `adb connect <ip>:5555`) and retry.\n' >&2
    exit 3
  fi
  case "$state" in
    device) ;;
    unauthorized)
      die "device $SERIAL is unauthorized — accept the 'Allow USB debugging' prompt on the device, then retry" 3 ;;
    offline)
      die "device $SERIAL is offline — replug the cable or \`adb reconnect\`, then retry" 3 ;;
    *)
      die "device $SERIAL is in state '$state' (not ready) — check \`adb devices\`" 3 ;;
  esac

  installed="$(adb -s "$SERIAL" shell pm list packages "$PACKAGE" 2>/dev/null | tr -d '\r')"
  case "$installed" in
    *"package:$PACKAGE"*) ;;
    *) die "package $PACKAGE is not installed on $SERIAL (this harness never installs it)" 3 ;;
  esac

  MODEL="$(adb -s "$SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
  ANDROID="$(adb -s "$SERIAL" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')"
  APPVER="$(adb -s "$SERIAL" shell dumpsys package "$PACKAGE" 2>/dev/null | tr -d '\r' \
    | awk -F= '/versionName=/ && !seen { print $2; seen = 1 }')"
  printf 'Device:    %s  Android %s  %s %s\n\n' "${MODEL:-unknown}" "${ANDROID:-?}" "$PACKAGE" "${APPVER:-?}"
fi

# -------------------------------------------------------------------- csv ----
CSV_DIR="$(dirname "$OUT_CSV")"
[[ -d "$CSV_DIR" ]] || mkdir -p "$CSV_DIR"
printf 'timestamp,prompt,engine,ok,promptTokens,outputTokens,totalTokens,ttftMs,totalMs,tokPerSec\n' > "$OUT_CSV"

# ------------------------------------------------------------------- runs ----
SECONDS=0
run_no=0
prompt_no=0

py header "$TOTAL_RUNS"

for prompt in "${PROMPTS[@]}"; do
  prompt_no=$(( prompt_no + 1 ))
  printf '\n-- prompt %d/%d: "%s"\n' "$prompt_no" "$NPROMPTS" "$prompt"

  for (( rep = 1; rep <= REPEATS; rep++ )); do
    run_no=$(( run_no + 1 ))

    if [[ -n "$REPLAY_FILE" ]]; then
      dump="$(cat "$REPLAY_FILE")"
      since=""
    else
      adb -s "$SERIAL" logcat -c >/dev/null 2>&1 \
        || warn "logcat -c failed on $SERIAL; a stale metrics line could contaminate this run"
      adb -s "$SERIAL" shell am force-stop "$PACKAGE" >/dev/null 2>&1 || true

      pre="$(adb -s "$SERIAL" shell logcat -d -v threadtime -s "$LOG_TAG":V 2>/dev/null || true)"
      case "$pre" in
        *'metrics prompt='*)
          warn "logcat buffer still holds a metrics line after logcat -c (stale line risk)" ;;
      esac

      # Single host-quoted device command: the device shell must keep the inner
      # double quotes, otherwise the prompt is split into separate argv entries
      # and --es only stores the first word.
      launch_out=''
      launch_cmd='am start -n '"$PACKAGE"'/'"$ACTIVITY"' --es prompt "'"$(dq_escape "$prompt")"'"'
      since="$(adb -s "$SERIAL" shell date +%H:%M:%S 2>/dev/null | tr -d '\r')"
      launch_out="$(adb -s "$SERIAL" shell "$launch_cmd" 2>&1 | tr -d '\r')"
      case "$launch_out" in
        *Error*|*error*)
          printf '\n' >&2
          printf 'bench.sh: error: am start failed for prompt: %s\n' "$prompt" >&2
          printf '  device said: %s\n' "$launch_out" >&2
          exit 3 ;;
      esac

      # Poll, never sleep-and-hope. Each iteration re-dumps the (cleared) buffer.
      deadline=$(( SECONDS + TIMEOUT_S ))
      dump=''
      adb_failures=0
      while (( SECONDS < deadline )); do
        if dump="$(adb -s "$SERIAL" shell logcat -d -v threadtime -s "$LOG_TAG":V 2>/dev/null)"; then
          adb_failures=0
          case "$dump" in
            *'metrics prompt='*) break ;;
          esac
        else
          adb_failures=$(( adb_failures + 1 ))
          (( adb_failures < 3 )) || die "lost adb access to $SERIAL while waiting for prompt: $prompt" 3
        fi
        sleep 0.4
      done

      case "$dump" in
        *'metrics prompt='*) ;;
        *)
          printf '\n' >&2
          printf 'bench.sh: error: no metrics line appeared within %ss for prompt:\n' "$TIMEOUT_S" >&2
          printf '  %s\n' "$prompt" >&2
          printf '  (run %d/%d; %d row(s) already written to %s)\n' \
            "$run_no" "$TOTAL_RUNS" "$(( run_no - 1 ))" "$OUT_CSV" >&2
          printf '  Diagnose with:\n' >&2
          printf '    adb -s %s logcat -d -s %s:* AndroidRuntime:E | tail -50\n' "$SERIAL" "$LOG_TAG" >&2
          exit 4 ;;
      esac
    fi

    if ! py row "$run_no/$TOTAL_RUNS" "$prompt" "$OUT_CSV" \
         "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$since" <<<"$dump"; then
      printf '\n' >&2
      printf 'bench.sh: error: no usable metrics line for prompt (run %d/%d):\n' \
        "$run_no" "$TOTAL_RUNS" >&2
      printf '  %s\n' "$prompt" >&2
      printf '  (csv so far: %s)\n' "$OUT_CSV" >&2
      exit 4
    fi
  done
done

# -------------------------------------------------------------- aggregates ---
printf '\nPer-prompt summary (means over ok=true runs with a positive value)\n'
py agg "$OUT_CSV"

printf '\nCSV:   %s (%d data row(s))\n' "$OUT_CSV" "$run_no"
printf 'Total: %ds wall clock across %d run(s)\n' "$SECONDS" "$run_no"
