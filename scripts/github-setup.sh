#!/usr/bin/env bash
# Einmalige Einrichtung für die Repo-Inhaberin bzw. den Repo-Inhaber (SRT-001):
#  1. GitHub Pages mit Quelle „GitHub Actions"
#  2. Branch-Schutz für main mit den Pflicht-Checks der CI
# Idempotent. Vorschau ohne Änderungen: ./scripts/github-setup.sh --dry-run
# Voraussetzung: gh CLI angemeldet (gh auth login) mit Admin-Rechten am Repo.
set -euo pipefail

DRY_RUN=0
[[ "${1:-}" == "--dry-run" ]] && DRY_RUN=1

REPO="${REPO:-$(gh repo view --json nameWithOwner -q .nameWithOwner)}"
CHECKS=("Lint" "Format check" "Unit tests" "Build" "Smoke (chromium)" "Smoke (firefox)" "Smoke (webkit)" "Smoke (msedge)")

run() {
  if [[ $DRY_RUN -eq 1 ]]; then
    echo "[dry-run] $*" >&2
  else
    "$@"
  fi
}

echo "Repo: $REPO"

echo "1) GitHub Pages: Quelle GitHub Actions"
if gh api "repos/$REPO/pages" >/dev/null 2>&1; then
  run gh api -X PUT "repos/$REPO/pages" -f build_type=workflow >/dev/null
else
  run gh api -X POST "repos/$REPO/pages" -f build_type=workflow >/dev/null
fi

echo "2) Branch-Schutz für main"
contexts_json=$(printf '%s\n' "${CHECKS[@]}" | python3 -c 'import json,sys; print(json.dumps([l.strip() for l in sys.stdin if l.strip()]))')
payload=$(mktemp)
trap 'rm -f "$payload"' EXIT
cat >"$payload" <<JSON
{
  "required_status_checks": { "strict": true, "contexts": $contexts_json },
  "enforce_admins": false,
  "required_pull_request_reviews": null,
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false
}
JSON
if [[ $DRY_RUN -eq 1 ]]; then
  echo "[dry-run] gh api -X PUT repos/$REPO/branches/main/protection --input <payload>"
  cat "$payload"
else
  gh api -X PUT "repos/$REPO/branches/main/protection" --input "$payload" >/dev/null
fi

echo "Fertig."
