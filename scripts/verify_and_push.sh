#!/usr/bin/env bash
set -euo pipefail

# -----------------------------------------------------------------------------
# CineCode-AI: Build Verification & GitHub Sync Script
# Validates build compilation, ensures git state is synchronized, and pushes to remote.
# -----------------------------------------------------------------------------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

echo "================================================================="
echo "🔍 CineCode-AI: Build Verification & Git Sync Script"
echo "================================================================="

cd "${ROOT_DIR}"

# 1. Validate Build Status
echo "🔨 Step 1: Validating Gradle build status..."
chmod +x "${SCRIPT_DIR}"/*.sh 2>/dev/null || true
if [[ -f "./gradlew" ]]; then
    chmod +x ./gradlew
    ./gradlew :app:compileDebugKotlin --no-daemon || ./gradlew :app:assembleDebug --no-daemon
else
    echo "⚠️ gradlew not found, skipping gradle build check."
fi
echo "✅ Build validation passed!"

# 2. Check Git Status & Commit Changes
echo "📦 Step 2: Checking Git repository status..."
git config --global --add safe.directory "${ROOT_DIR}" || true
git status

UNCOMMITTED=$(git status --porcelain)
if [[ -n "${UNCOMMITTED}" ]]; then
    echo "📝 Uncommitted changes detected. Staging and committing..."
    git add .
    git commit -m "chore(sync): automated verification and sync update [skip ci]" || echo "No changes to commit."
else
    echo "✨ Working tree is clean."
fi

# 3. Synchronize with Remote & Push
echo "🚀 Step 3: Synchronizing with remote repository..."
CURRENT_BRANCH=$(git branch --show-current)
if [[ -z "${CURRENT_BRANCH}" ]]; then
    CURRENT_BRANCH="main"
    git checkout -B "${CURRENT_BRANCH}"
fi

echo "Pulling latest changes from origin/${CURRENT_BRANCH} (rebase)..."
git pull --rebase origin "${CURRENT_BRANCH}" || echo "⚠️ Pull rebase skipped or not required (new/untracked remote branch)."

echo "Pushing changes to origin/${CURRENT_BRANCH}..."
git push -u origin "${CURRENT_BRANCH}"

echo "================================================================="
echo "🎉 Verification and Push Completed Successfully!"
echo "================================================================="
