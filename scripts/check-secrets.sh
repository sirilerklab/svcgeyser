#!/usr/bin/env bash
# Project-specific secret checks for SVCGeyser release pipeline.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

failures=0

fail() {
  echo "SECRET CHECK FAILED: $1" >&2
  failures=$((failures + 1))
}

echo "Running SVCGeyser secret checks..."

# Hardcoded OAuth client UUIDs in source (must use BuildConfig / gradle.properties instead).
while IFS= read -r file; do
  [ -z "$file" ] && continue
  if grep -E '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' "$file" \
    | grep -v 'your-azure-app-client-id' >/dev/null 2>&1; then
    fail "Hardcoded UUID (likely OAuth client ID) found in $file"
  fi
done < <(git ls-files '*.kt' '*.xml')

while IFS= read -r file; do
  [ -z "$file" ] && continue
  if grep -E 'CLIENT_ID\s*=\s*"' "$file" >/dev/null 2>&1; then
    fail "Hardcoded CLIENT_ID constant found in $file"
  fi
done < <(git ls-files '*.kt')

# Non-placeholder jwt-secret committed to the repo.
while IFS= read -r file; do
  [ -z "$file" ] && continue
  if grep 'jwt-secret:' "$file" | grep -E 'jwt-secret:\s*"[^"]+"' | grep -v 'change-me' >/dev/null 2>&1; then
    fail "Non-placeholder jwt-secret in $file"
  fi
done < <(git ls-files '*.yml' '*.yaml')

# Keystore files must never be committed.
if git ls-files '*.keystore' '*.jks' | grep -q .; then
  fail "Keystore file tracked in git: $(git ls-files '*.keystore' '*.jks' | tr '\n' ' ')"
fi

# local.properties must stay untracked.
if git ls-files 'local.properties' 'app/local.properties' | grep -q .; then
  fail "local.properties is tracked in git"
fi

# gradle.local.properties with secrets must stay untracked.
if git ls-files 'app/gradle.local.properties' | grep -q .; then
  fail "app/gradle.local.properties is tracked in git (keep OAuth client ID local only)"
fi

# Tracked gradle.properties must not contain a real OAuth client ID.
if git ls-files 'app/gradle.properties' | grep -q .; then
  if grep -E 'liveOAuthClientId=[0-9a-f]{8}-' app/gradle.properties >/dev/null 2>&1; then
    fail "liveOAuthClientId must not be committed in app/gradle.properties (use gradle.local.properties)"
  fi
fi

if [ "$failures" -gt 0 ]; then
  echo "$failures secret check(s) failed." >&2
  exit 1
fi

echo "All SVCGeyser secret checks passed."
