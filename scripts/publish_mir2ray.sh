#!/usr/bin/env bash
set -euo pipefail

OWNER="desmondep"
REPO="Mir2Ray"
BRANCH="v1_10_32"
TAG="mir2ray-v1.10.32"
TITLE="Mir2Ray v1.10.32"
NOTES="Initial Mir2Ray release"
APK_PATH="V2rayNG/app/build/outputs/apk/release/app-release.apk"

cd "$(dirname "$0")/.."

if ! command -v gh >/dev/null 2>&1; then
  echo "gh CLI is required. Install and login first: gh auth login"
  exit 1
fi

if ! command -v git >/dev/null 2>&1; then
  echo "git is required"
  exit 1
fi

# Create repo if missing
if ! gh repo view "$OWNER/$REPO" >/dev/null 2>&1; then
  gh repo create "$OWNER/$REPO" --public --source=. --remote=origin --push=false
fi

# Ensure remote points to Mir2Ray
if git remote get-url origin >/dev/null 2>&1; then
  git remote set-url origin "https://github.com/$OWNER/$REPO.git"
else
  git remote add origin "https://github.com/$OWNER/$REPO.git"
fi

# Push code
git push -u origin "$BRANCH"

# Build APK (requires Android SDK configured)
(
  cd V2rayNG
  ./gradlew :app:assembleRelease
)

if [[ -f "$APK_PATH" ]]; then
  # Create release if tag not exists
  if ! gh release view "$TAG" --repo "$OWNER/$REPO" >/dev/null 2>&1; then
    gh release create "$TAG" "$APK_PATH" --repo "$OWNER/$REPO" --title "$TITLE" --notes "$NOTES" --target "$BRANCH"
  else
    gh release upload "$TAG" "$APK_PATH" --repo "$OWNER/$REPO" --clobber
  fi
else
  echo "APK not found at $APK_PATH"
  echo "Build failed or output path differs."
  exit 2
fi

echo "Done: https://github.com/$OWNER/$REPO"
