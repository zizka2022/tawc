#!/bin/bash
# Build only in reviewed CI. Never install or execute the Android artifacts.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
[[ "${GITHUB_ACTIONS:-}" == true && "${GITHUB_WORKSPACE:-}" == "$ROOT" ]] || exit 2
[[ "$(uname -m)" == x86_64 ]] || exit 2
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
OUT="$ROOT/build/pocket-tawcroot-diagnostics/companion"
ASSETS="$ROOT/sigsys-probe/build/generated/probeAssets"
JNI="$ROOT/sigsys-probe/build/generated/probeJni/arm64-v8a"
mkdir -p "$OUT" "$ASSETS" "$JNI"
MODE="${1:-}"
[[ "$MODE" == prepare || "$MODE" == package ]] || exit 2
ulimit -c 0
ulimit -t 600
if [[ "$MODE" == prepare ]]; then
python3 - "$ROOT" "$ASSETS" "$JNI" <<'PY'
from pathlib import Path
import hashlib,sys
root,assets,jni=map(Path,sys.argv[1:])
inputs=(('libpocket_baseline.so',root/'app/src/main/jniLibs/arm64-v8a/libtawcroot.so','343db96e9ac6eab32224a58e440bfea92524d6dac7c3c201897d70b982b0d112'),
        ('libpocket_trace.so',root/'build/pocket-tawcroot-diagnostics/arm64/libtawcroot-trace.so','5a65fb93d44677310c48f3130a1c3fb002e0afa0b7a5863435e473c63f08012f'))
for name,path,expected in inputs:
    data=path.read_bytes()
    if hashlib.sha256(data).hexdigest()!=expected: raise SystemExit('Known runtime hash mismatch; refuse packaging')
    (jni/name).write_bytes(data)
(assets/'LICENSE').write_bytes((root/'LICENSE').read_bytes())
PY
NDK="${ANDROID_HOME:?}/ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin"
FLAGS=(-O2 -ffreestanding -fno-builtin -fno-stack-protector -fno-asynchronous-unwind-tables -Wall -Wextra -Werror -Wframe-larger-than=1024 -static -nostdlib -nostartfiles -fno-pie -Wl,-no-pie -Wl,--build-id=none -Wl,-z,noexecstack)
timeout --signal=TERM --kill-after=15s 2m "$NDK/aarch64-linux-android29-clang" "${FLAGS[@]}" \
    -I"$ROOT/tawcroot/include" "$ROOT/sigsys-probe/native/runner.c" \
    "$ROOT/sigsys-probe/native/start_aarch64.S" "$ROOT/sigsys-probe/native/raw_aarch64.S" \
    -o "$JNI/libpocket_runner.so"
timeout --signal=TERM --kill-after=15s 2m "$NDK/aarch64-linux-android29-clang" \
    -static -nostdlib -nostartfiles -fno-pie -Wl,-no-pie -Wl,--build-id=none -Wl,-z,noexecstack \
    "$ROOT/sigsys-probe/native/fixture_aarch64.S" -o "$ASSETS/probe-fixture"
[[ -z "$("$NDK/llvm-nm" -u "$JNI/libpocket_runner.so")" ]] || exit 2
python3 - "$ROOT" "$ASSETS" "$JNI" <<'PY'
from pathlib import Path
import hashlib,json,os,subprocess,sys
root,assets,jni=map(Path,sys.argv[1:])
sha=subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'],text=True).strip()
data={'source_commit':sha,'runtime_source_commit':'5bbafd8490d4687d5f845bdab4212c99a5150cb4',
      'baseline_sha256':hashlib.sha256((jni/'libpocket_baseline.so').read_bytes()).hexdigest(),
      'trace_sha256':hashlib.sha256((jni/'libpocket_trace.so').read_bytes()).hexdigest(),
      'runner_sha256':hashlib.sha256((jni/'libpocket_runner.so').read_bytes()).hexdigest(),
      'fixture_sha256':hashlib.sha256((assets/'probe-fixture').read_bytes()).hexdigest(),
      'source_url':os.environ['GITHUB_SERVER_URL']+'/'+os.environ['GITHUB_REPOSITORY']+'/tree/'+sha,
      'runtime_source_url':os.environ['GITHUB_SERVER_URL']+'/'+os.environ['GITHUB_REPOSITORY']+'/tree/5bbafd8490d4687d5f845bdab4212c99a5150cb4',
      'installed_or_executed':False,'purpose':'Fixed signed marker fixture only; no Firefox or current TAWC equivalence claim.'}
(assets/'provenance.json').write_text(json.dumps(data,indent=2)+'\n')
PY
# Host supervisor tests exercise cancellation/descendant cleanup and guard limits.
timeout --signal=TERM --kill-after=15s 2m python3 "$ROOT/scripts/ci/test-sigsys-probe.py" \
    > "$OUT/native-tests.log" 2>&1
COMMON=(-PtawcAbis=arm64-v8a -PtawcXwayland=true -PtawcGraphics=libhybris,cpu -PtawcMethods=tawcroot -PtawcBootstrapPackages=false)
timeout --signal=TERM --kill-after=15s 5m "$ROOT/gradlew" --no-daemon :sigsys-probe:testReleaseUnitTest \
    "${COMMON[@]}" > "$OUT/unit-tests.log" 2>&1
python3 - "$ROOT" >> "$OUT/unit-tests.log" <<'PYTEST'
from pathlib import Path
import sys,xml.etree.ElementTree as ET,json
reports=list((Path(sys.argv[1])/'sigsys-probe/build/test-results/testReleaseUnitTest').glob('TEST-*.xml'))
assert len(reports)==3, 'Expected all companion test suites'
counts={key:sum(int(ET.parse(p).getroot().get(key,'0')) for p in reports) for key in ('tests','failures','errors','skipped')}
assert counts=={'tests':18,'failures':0,'errors':0,'skipped':0}, 'Companion test counts differ'
print(json.dumps({'companion_java_test_results':counts}))
PYTEST
timeout --signal=TERM --kill-after=15s 5m "$ROOT/gradlew" --no-daemon :sigsys-probe:lintRelease \
    "${COMMON[@]}" > "$OUT/lint.log" 2>&1
exit 0
fi
COMMON=(-PtawcAbis=arm64-v8a -PtawcXwayland=true -PtawcGraphics=libhybris,cpu -PtawcMethods=tawcroot -PtawcBootstrapPackages=false)
timeout --signal=TERM --kill-after=15s 10m "$ROOT/gradlew" --no-daemon :sigsys-probe:assembleRelease \
    "${COMMON[@]}" > "$OUT/build.log" 2>&1
CHECKED="$ROOT/sigsys-probe/build/checked"
mkdir -p "$CHECKED"
APK="$CHECKED/pocket-sigsys-probe-${GITHUB_SHA:0:7}.apk"
cp "$ROOT/sigsys-probe/build/outputs/apk/release/sigsys-probe-release.apk" "$APK"
set +e
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs "$APK" \
    > "$OUT/signing-check.log" 2>&1
sign_exit=$?
set -e
[[ "$sign_exit" == 0 ]] || exit 2
signer="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$OUT/signing-check.log")"
[[ "$signer" == "${DEBUG_CERT_SHA256:?}" ]] || exit 2
python3 "$ROOT/scripts/ci/check-sigsys-probe-apk.py" "$APK"
cp "$APK" "$OUT/"
cp "$CHECKED/manifest.json" "$OUT/"
sha256sum "$OUT/$(basename "$APK")" | sed "s|$OUT/||" > "$OUT/SHA256SUMS"
