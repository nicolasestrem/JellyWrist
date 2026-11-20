#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

allowed_url_hosts_regex='^(developer\.android\.com|docs\.gradle\.org|github\.com|jellyfin\.example\.com|keepachangelog\.com|localhost|schemas\.android\.com|www\.apache\.org)$'
credential_regex='BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|gh[pousr]_[A-Za-z0-9_]{20,}|AIza[0-9A-Za-z_-]{35}|AKIA[0-9A-Z]{16}'
errors=0

report() {
  printf 'PUBLIC-HYGIENE: %s\n' "$*" >&2
  errors=1
}

# Files produced by local agents or assistants must never become source artifacts.
if git ls-files | grep -Eq '(^|/)(\.logs|\.zcode|\.claude|\.cursor|\.idea|\.remember)/|(^|/)(omp-session-|plan-sess_)'; then
  report 'tracked local agent/session artifact detected'
fi

# Private keys, signing stores, local configuration, and packaged apps are forbidden.
# `.env` must also match suffixed variants such as `.env.local` or `config/.env.production`.
if git ls-files | grep -Eiq '(^|/)(\.env(\.[^/]*)?|local\.properties|keystore\.properties|google-services\.json|service-account[^/]*\.json)$|\.(jks|keystore|p12|pem|key|apk|aab)$'; then
  report 'tracked credential, signing, local configuration, or binary package detected'
fi

# `git grep` reads the working tree, but a commit publishes the index. A secret that was
# staged and then redacted only in the working tree must still fail this check, so both
# snapshots are scanned.
scan_snapshot() {
  local label="$1"
  shift
  local -a snapshot_args=("$@")

  # Reject credential-shaped material. Synthetic tests should use visibly fake short fixtures.
  # Only paths are reported: printing the matched line would copy the secret into the log.
  local matches
  matches="$(git grep -lI -E ${snapshot_args[@]+"${snapshot_args[@]}"} "$credential_regex" \
    -- . ':!scripts/check-public-hygiene.sh' || true)"
  if [[ -n "$matches" ]]; then
    report "credential-shaped material detected in $label: $(tr '\n' ' ' <<< "$matches")"
  fi

  # Home-network addresses and developer home directories are private infrastructure.
  # RFC 5737 ranges (192.0.2.0/24, 198.51.100.0/24, 203.0.113.0/24) remain valid fixtures.
  local private_infrastructure_regex='(^|[^0-9])192\.168\.[0-9]{1,3}\.[0-9]{1,3}([^0-9]|$)|C:\\Users\\|/home/[A-Za-z0-9._-]+/'
  matches="$(git grep -lI -E ${snapshot_args[@]+"${snapshot_args[@]}"} "$private_infrastructure_regex" \
    -- . ':!scripts/check-public-hygiene.sh' || true)"
  if [[ -n "$matches" ]]; then
    report "private infrastructure detected in $label: $(tr '\n' ' ' <<< "$matches")"
  fi

  # Documentation and tests may link only to known public/reference hosts. Source code builds
  # runtime URLs and is reviewed separately to avoid mistaking user-provided server support.
  local url host
  while IFS= read -r url; do
    host="${url#*://}"
    host="${host%%/*}"
    host="${host%%:*}"
    # Empty/bare hosts occur in explanatory templates; RFC 5737 ranges are examples.
    [[ -z "$host" || "$host" != *.* ]] && continue
    case "$host" in
      *.example|*.example.com|*.example.net|*.example.org|*.lan|192.0.2.*|198.51.100.*|203.0.113.*) continue ;;
    esac
    if [[ ! "$host" =~ $allowed_url_hosts_regex ]]; then
      report "unreviewed URL host detected in $label: $host"
    fi
  done < <(git grep -IhoE ${snapshot_args[@]+"${snapshot_args[@]}"} 'https?://[A-Za-z0-9._:-]+' \
    -- '*.md' '*.yml' '*.yaml' '*.toml' '*.properties' '*.xml' '*.json' '*.kt' '*.kts' 2>/dev/null | sort -u)
}

scan_snapshot 'the index (staged snapshot)' --cached
scan_snapshot 'the working tree'

if (( errors != 0 )); then
  exit 1
fi
printf 'PUBLIC-HYGIENE: index and working tree passed\n'
