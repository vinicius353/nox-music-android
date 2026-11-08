#!/usr/bin/env bash
set -euo pipefail

# Reproducible upstream source retrieval. No upstream scripts are executed.
# GPL-3.0-only license applies to the integrated InnerTune code and derivatives.
UPSTREAM="https://github.com/z-huang/InnerTune.git"
REV="bfba5ecb16795cfc93ecce73d94ca583bcec917c"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET="$ROOT/third_party/innertube"
TEMP="$(mktemp -d)"
trap 'rm -rf "$TEMP"' EXIT

git -C "$TEMP" init --quiet repo
git -C "$TEMP/repo" remote add origin "$UPSTREAM"
git -C "$TEMP/repo" fetch --quiet --depth 1 origin "$REV"
git -C "$TEMP/repo" checkout --quiet --detach FETCH_HEAD
ACTUAL="$(git -C "$TEMP/repo" rev-parse HEAD)"
test "$ACTUAL" = "$REV"
test -f "$TEMP/repo/innertube/src/main/java/com/zionhuang/innertube/YouTube.kt"

mkdir -p "$TARGET/src/main"
rm -rf "$TARGET/src/main/java"
cp -R "$TEMP/repo/innertube/src/main/java" "$TARGET/src/main/java"
cp "$TEMP/repo/LICENSE" "$TARGET/LICENSE"
printf '%s\n' "$REV" > "$TARGET/UPSTREAM_REVISION"
echo "InnerTune innertube installed at $REV (GPL-3.0)"
