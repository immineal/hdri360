#!/usr/bin/env bash
# The fast inner loop: the whole core suite on a bare JVM.
# No Android SDK, no emulator, no network after the first run.
#
#   ./verify.sh        21 suites, pass/fail only
#   ./verify.sh -v     plus every measured diagnostic
#   ./verify.sh --allow-missing   tolerate suites that are not written yet
set -euo pipefail
cd "$(dirname "$0")"
exec ./gradlew :core-tests:verify --console=plain -q -PsuiteArgs="$*"
