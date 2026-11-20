# Contributing to JellyWrist

JellyWrist is preparing an early beta. Discuss substantial product or architecture changes
before implementation and follow the repository contract in `AGENTS.md`.

## Public-data rules

Every contribution must:

- use only synthetic identities and RFC-reserved example domains;
- exclude credentials, tokens, signing files, private endpoints, real account names,
  production logs, device dumps, and unredacted screenshots;
- exclude local assistant/session output and generated build packages;
- keep dependency versions in `gradle/libs.versions.toml`;
- update `CHANGELOG.md` for substantive changes; and
- pass `scripts/check-public-hygiene.sh` before it is committed.

Treat commit messages, branch names, pull-request text, issue text, and review comments as
public. Redaction must happen before material enters Git, not in a cleanup commit.

## Verification

Run:

```bash
./scripts/check-public-hygiene.sh
shellcheck scripts/check-public-hygiene.sh   # required when the checker itself changes
./gradlew lintDebug
./gradlew testDebugUnitTest
./gradlew assembleDebug assembleRelease
```

`scripts/check-public-hygiene.sh` scans both the index and the working tree, so it is
meaningful as a pre-commit gate: a secret that is staged and then redacted only in the
working tree still fails. It reports offending paths, never the matched line, so a hit
does not copy the secret into the terminal or a CI log.

Instrumented tests additionally require paired Android/Wear OS devices or emulators.
