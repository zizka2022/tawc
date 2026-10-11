#!/bin/bash
# Opt-in CI only: canonical host regressions or an unpackaged ARM64 trace artifact.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MODE="${1:-}"
OUT="$ROOT/build/pocket-tawcroot-diagnostics"
case "$MODE" in
    --help|-h) echo 'Usage: scripts/ci/tawcroot-diagnostics.sh host-tests|arm64-artifact'; exit 0 ;;
    host-tests|arm64-artifact) ;;
    *) echo 'Expected host-tests or arm64-artifact' >&2; exit 2 ;;
esac
[[ "${GITHUB_ACTIONS:-}" == true && "${GITHUB_WORKSPACE:-}" == "$ROOT" ]] || { echo 'Run only in the recognized CI workspace' >&2; exit 2; }
[[ "$(uname -m)" == x86_64 ]] || { echo 'Use the recognized x86_64 CI build host' >&2; exit 2; }
grep -q TAWCROOT_TRACE_ENTER "$ROOT/tawcroot/src/handler.c" || {
    echo 'Apply the reviewed default-off trace patch first' >&2; exit 2;
}
mkdir -p "$OUT"
ulimit -c 0
ulimit -t 600
if [[ "$MODE" == host-tests ]]; then
    mkdir -p "$OUT/host" "$OUT/test-home"
    # Discard only this ephemeral runner's cached framework, then use its pin.
    rm -rf "$ROOT/deps/cleat"
    source "$ROOT/scripts/lib/deps.sh"
    dep_ensure cleat
    cleat_dir="$(dep_dir cleat)"
    git -C "$cleat_dir" diff --quiet
    git -C "$cleat_dir" diff --cached --quiet
    [[ -z "$(git -C "$cleat_dir" ls-files --others --exclude-standard)" ]] || {
        echo 'Unexpected framework files; refuse canonical comparison' >&2; exit 2;
    }
    for phase in baseline trace; do
        flags=''
        [[ "$phase" == baseline ]] || flags='-DTAWCROOT_TRACE_ENTER=1'
        phase_dir="$OUT/host/$phase"
        mkdir -p "$phase_dir"
        # No signing key has been restored at this point in the workflow.
        set +e
        timeout --signal=TERM --kill-after=15s 10m env EXTRA_CFLAGS="$flags" \
            TAWCROOT_BUILD_JOBS=2 "$ROOT/tawcroot/build.sh" --abi=host --clean \
            > "$phase_dir/build.log" 2>&1
        build_exit=$?
        test_exit=125
        if [[ "$build_exit" == 0 ]]; then
            # Fixtures receive only a fresh HOME and non-secret tool/runtime settings.
            timeout --signal=TERM --kill-after=15s 10m env -i \
                PATH="$PATH" HOME="$OUT/test-home" TMPDIR=/tmp LANG=C.UTF-8 \
                GITHUB_WORKSPACE="$ROOT" "$ROOT/tawcroot/test.sh" --host --no-build \
                > "$phase_dir/tests.log" 2>&1
            test_exit=$?
        fi
        set -e
        python3 - "$ROOT" "$phase_dir" "$phase" "$build_exit" "$test_exit" <<'PY'
from pathlib import Path
import json,subprocess,sys
root,out=map(Path,sys.argv[1:3])
handler=root/'tawcroot/src/handler.c'
objects={kind: root/'build/tawcroot-host/.obj'/kind/'tawcroot/src/handler.c.o' for kind in ('prod','testhost','tests')}
expected=sys.argv[3]=='trace'
trace_presence={kind: b'[te] ' in p.read_bytes() if p.is_file() else None for kind,p in objects.items()}
report={'source_commit':subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'],text=True).strip(),
        'handler_sha256':__import__('hashlib').sha256(handler.read_bytes()).hexdigest(),
        'dependency_manifest_sha256':__import__('hashlib').sha256((root/'deps/deps.list').read_bytes()).hexdigest(),
        'cleat_commit':subprocess.check_output(['git','-C',str(root/'deps/cleat'),'rev-parse','HEAD'],text=True).strip(),
        'phase':sys.argv[3],'trace_enabled':expected,'compiled_trace_marker_presence':trace_presence,'build_exit':int(sys.argv[4]),'test_exit':int(sys.argv[5]),
        'scope':'Canonical x86_64 host build/test.sh, including hosted ASan+UBSan and forked handler/integration tests; no phone connected.'}
(out/'result.json').write_text(json.dumps(report,indent=2)+'\n')
if report['build_exit']==0 and any(v is not expected for v in trace_presence.values()):
    raise SystemExit('Baseline/trace object fidelity check failed')
PY
        echo "$phase: build=$build_exit tests=$test_exit"
        if [[ "$build_exit" != 0 || "$test_exit" != 0 ]]; then
            echo "See uploaded host/$phase logs; diagnostic build is blocked" >&2
            exit 1
        fi
    done
else
    # Never overwrite app/src/main/jniLibs: this is not an installable APK.
    mkdir -p "$OUT/arm64"
    jni="$ROOT/app/src/main/jniLibs/arm64-v8a/libtawcroot.so"
    jni_before="$(if [[ -f "$jni" ]]; then sha256sum "$jni" | cut -d' ' -f1; else echo absent; fi)"
    timeout --signal=TERM --kill-after=15s 10m env EXTRA_CFLAGS=-DTAWCROOT_TRACE_ENTER=1 \
        "$ROOT/tawcroot/build.sh" --abi=aarch64 --clean --no-stage \
        > "$OUT/arm64/build.log" 2>&1
    jni_after="$(if [[ -f "$jni" ]]; then sha256sum "$jni" | cut -d' ' -f1; else echo absent; fi)"
    [[ "$jni_after" == "$jni_before" ]] || { echo '--no-stage changed APK JNI input' >&2; exit 1; }
    cp "$ROOT/build/tawcroot-aarch64/libtawcroot.so" "$OUT/arm64/libtawcroot-trace.so"
    python3 - "$ROOT" "$OUT/arm64" <<'PY'
from pathlib import Path
import hashlib,json,subprocess,sys
root,out=map(Path,sys.argv[1:3]);binary=out/'libtawcroot-trace.so'
data=binary.read_bytes()
if b'[te] ' not in data:
    raise SystemExit('ARM64 artifact lacks compiled trace marker')
report={'source_commit':subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'],text=True).strip(),
        'handler_sha256':hashlib.sha256((root/'tawcroot/src/handler.c').read_bytes()).hexdigest(),
        'binary_sha256':hashlib.sha256(binary.read_bytes()).hexdigest(),'binary_bytes':binary.stat().st_size,
        'trace_macro':'TAWCROOT_TRACE_ENTER=1','compiled_trace_marker_present':True,'ndk_version':'27.2.12479018','abi':'arm64-v8a',
        'packaged_in_apk':False,'installed_or_executed':False,
        'deployment':'Artifact only. Production-domain staging and phone approval remain separate.'}
(out/'manifest.json').write_text(json.dumps(report,indent=2)+'\n')
PY
fi
