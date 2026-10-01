#!/bin/bash
# Copies the monorepo sources into the two working copies and builds both.
#
# ~/i2w is the source of truth. The Xcode and Android Studio projects are where
# the work is actually done, so they must be updated together with it, never
# left behind.
set -uo pipefail

MONO="$HOME/i2w"
XCODE="$HOME/Documents/Iphone to watch"
ANDROID="$HOME/AndroidStudioProjects/watchtoios"

fail=0
note() { printf '%s\n' "$*"; }
step() { printf '\n=== %s ===\n' "$*"; }

step "Syncing watch sources"
rsync -a --delete \
  --exclude '.git' --exclude 'build' --exclude '.gradle' --exclude '.idea' \
  --exclude '.kotlin' --exclude 'local.properties' --exclude '.DS_Store' \
  "$MONO/watch/app/src/main/java/" "$ANDROID/app/src/main/java/"
rsync -a \
  --exclude '.git' --exclude 'build' --exclude '.gradle' --exclude '.idea' \
  --exclude '.kotlin' --exclude 'local.properties' --exclude '.DS_Store' \
  --exclude 'app/libs/*.aar' \
  "$MONO/watch/" "$ANDROID/"
note "watch synced"

step "Syncing iPhone sources"
rsync -a \
  --exclude '.git' --exclude 'build' --exclude 'DerivedData' \
  --exclude 'xcuserdata' --exclude '.DS_Store' \
  "$MONO/iphone/Iphone to watch/" "$XCODE/Iphone to watch/"
rsync -a \
  --exclude '.git' --exclude 'build' --exclude 'DerivedData' \
  --exclude 'xcuserdata' --exclude '.DS_Store' \
  "$MONO/iphone/" "$XCODE/"
note "iphone synced"

step "Building Android Studio project"
if ! (cd "$ANDROID" && ./gradlew testDebugUnitTest assembleDebug --console=plain 2>&1 \
      | grep -E "^e: |BUILD|FAILED" | head -20); then
  note "ANDROID BUILD FAILED"
  fail=1
fi

step "Building Xcode project"
if ! (cd "$XCODE" && xcodebuild -project "Iphone to watch.xcodeproj" \
      -scheme "Iphone to watch" -configuration Debug \
      -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
      -derivedDataPath /tmp/sync_dd build CODE_SIGNING_ALLOWED=NO 2>&1 \
      | grep -E "error:|BUILD" | sort -u | head -20); then
  note "XCODE BUILD FAILED"
  fail=1
fi

step "Checking for personal data"
# Assembled from parts so this script does not match itself while scanning the
# monorepo, which contains this file. Also covers accented forms of a first
# name, which plain ASCII patterns miss.
PATTERN="matuš|matús|matúš|matuš|michal""ec|xboxmatmi""ch|8C257VF24W"
for repo in "$MONO" "$XCODE" "$ANDROID"; do
  if (cd "$repo" && git grep -qIiE "$PATTERN" -- . ':!sync-to-working-copies.sh' 2>/dev/null); then
    note "PERSONAL DATA FOUND in $repo"
    (cd "$repo" && git grep -nIiE "$PATTERN" -- . ':!sync-to-working-copies.sh' | head -5)
    fail=1
  else
    note "clean: $repo"
  fi
done

step "Result"
if [ "$fail" -eq 0 ]; then
  note "All three locations in sync. Both projects build. No personal data."
  exit 0
fi
note "Something failed - see above."
exit 1
