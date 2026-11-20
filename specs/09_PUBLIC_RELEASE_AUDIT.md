# Public Release Audit

- Status: IMPLEMENTED — ready for publication
- Target: `0.0.1` (`versionCode` 1 in `:app` and `:mobile`)
- Date: 2026-11-20

## Objective

Prepare the first public source release of JellyWrist (`0.0.1`) from a deliberately clean
snapshot: no private infrastructure, identities, credentials, local automation debris,
unpublished Git history, or privileged comment-triggered automation. The version line restarts
at `0.0.1` / `versionCode` 1 in both modules, and the Android `applicationId` stays
`com.example.jellywatch` by design (DEC-012) so existing installations keep their data. The
release is published under the MIT License with `Copyright (c) 2026 nicolasestrem` (DEC-015).

## Findings and disposition

### Earlier hardening pass

| Severity | Finding | Current status |
|---|---|---|
| Critical | Production infrastructure and an account identifier appeared in documentation and tests | Replaced with RFC-reserved example hosts and synthetic fixtures in the current tree. The old history that contains them is not published. |
| Critical | Public comments could trigger an AI workflow with repository write permissions and privileged tokens | Removed all comment- and issue-triggered AI workflows. Only the Android build workflow remains. |
| High | A third-party AI action used a moving `latest` reference | Removed with the workflow. |
| High | Remaining CI actions used mutable version tags and implicit token permissions | Pinned to reviewed commit SHAs; workflow permissions are `contents: read`. |
| High | Local agent plans and execution logs were tracked | No longer tracked. `.gitignore` patterns and the hygiene scan reject assistant and session artifacts. |
| High | No automated public-data policy existed | Added `scripts/check-public-hygiene.sh` and mandatory rules in `AGENTS.md`; CI enforcement was added in the 0.0.1 pass. |
| Medium | No private vulnerability-reporting guidance existed | Added `SECURITY.md`. |
| Medium | No contributor hygiene guidance existed | Added `CONTRIBUTING.md`. |
| Low | Maintainer identity appears in durable decision records | Retained: repository ownership and Git attribution are intentional public metadata. |
| Low | Brand image metadata was not independently inspected | PNG assets show no adjacent secret material; provenance and metadata inspection remains a pre-announcement checklist item. |

### 0.0.1 publication pass

| Severity | Finding | Disposition |
|---|---|---|
| High | The hygiene scan was not enforced by CI | `.github/workflows/android.yml` runs it before lint, tests, and builds on every push to `main` and every pull request; a finding fails the workflow. |
| Medium | The owner's first name appeared in JVM unit-test fixtures | Replaced with a visibly synthetic identity (`Avery`) across the watch, `:core`, and phone test sources. |
| Medium | Dead references to the unpublished history remained in the specs (commit SHAs, pull request numbers, branch names, CI run URLs) | Removed or genericised to "prior internal" wording. The public history starts at one root commit, so those references cannot resolve and must not be reintroduced. |
| Medium | A local checkout directory name remained in the intake metadata | Removed. The metadata now records only the public repository, and the hygiene scan rejects developer home-directory shapes in both the staged index and the working tree. |
| Medium | The assistant run log `specs/08_CHANGELOG_AI.md` was tracked | Deleted. `specs/` now holds only durable project documents, and the scan fails if assistant or session artifacts reappear. |
| Medium | No project license had been selected | **Resolved:** the MIT License was selected and added as `LICENSE` with `Copyright (c) 2026 nicolasestrem`; it is part of the reviewed root commit, and `README.md` states the license (DEC-015). |
| Medium | No automated dependency-update policy existed | Added `.github/dependabot.yml`: weekly pull requests for Gradle dependencies and GitHub Actions, capped at five open pull requests each. |
| Medium | Repository-level security features were unconfigured | Part of the publication model below: the recreated public repository is to be created with private vulnerability reporting and secret scanning enabled. These are GitHub settings, not repository content; verify them once the repository exists at publication. |
| Low | `.gitignore` contained a literal assistant session filename | Replaced by the general `omp-session-*` pattern; the literal filename is gone. |
| Low | `app/proguard-rules.pro` comments were in French | Translated to English. The R8 keep rules themselves are unchanged. |

## Mandatory publication model

The old Git history is not published. It contains private LAN addresses and local agent
artifacts, and a cleanup commit cannot remove older blobs. The old private repository is
therefore deleted and recreated as a public repository whose only history is one reviewed root
commit of this sanitized tree.

1. The reviewed snapshot is committed as the single root commit and pushed as the initial
   state of the recreated public repository. Nothing else is pushed.
2. No ref, branch, tag, patch, bundle, reflog, CI or build cache, or archived `.git`
   directory from the old private repository is published, imported, mirrored, or forked.
3. The old private repository is deleted; it is not kept public, archived in place, or used as
   a source for the new repository's history.
4. The recreated public repository is to be created with private vulnerability reporting and
   secret scanning enabled. These are GitHub-side settings that cannot be verified from this
   tree, so verify both once the repository exists at publication.
5. Subsequent work uses normal branches and pull requests against the root commit.

## Verification checklist

1. Run `./scripts/check-public-hygiene.sh`; it must pass and it scans both the staged index and
   the working tree.
2. Confirm exactly one root commit with `git rev-list --all --count` printing `1`, and no other
   branches, tags, or imported refs (`git log --all --oneline` shows one commit).
3. Confirm both APKs report `versionName` `0.0.1` and `versionCode` `1` (`:app` watch and
   `:mobile` phone; for example with `aapt dump badging`).
4. Read `specs/05_BUILD_REVIEW.md` for the recorded lint, unit-test, and build evidence of the
   release verification build.
5. Run a full-history secret scanner against the newly initialized repository before the first
   push.
6. After publication, confirm in GitHub that private vulnerability reporting and secret
   scanning are enabled; the tree cannot prove either setting.

## Remaining blockers and risks

1. On-device and instrumented verification is incomplete. Open gaps cover the background
   foreground-service start (GAP-014), the watch download-to-offline-playback path (GAP-015),
   the success paths of companion discovery (GAP-016), and an unexplained lost session
   (GAP-017). This release does not claim those paths are verified on hardware; see
   `specs/07_KNOWN_GAPS.md`.
2. Branch protection and required CI status checks are a post-publication follow-up. If they
   are not configured when the repository is created, add them immediately after the first
   push; they are repository settings and are not verifiable from this tree.
3. Brand asset provenance and PNG metadata should be re-confirmed on the published tree before
   the release is announced.

## LLM and automation contract

All assistants must assume that every repository surface is public. They must never copy real
operational values from chat, local files, environment variables, external services, command
output, screenshots, or prior Git objects into tracked content. They must use reserved examples,
minimize captured output, inspect staged changes, and stop publication when uncertain. A later
redaction is not an acceptable control: if sensitive data enters Git, publication stops and the
repository is republished from a new reviewed root commit. Assistants must also not reintroduce
references to the unpublished history (commit SHAs, pull request numbers, branch names, CI run
URLs) or assistant session artifacts.

Privileged automation must not be triggerable by untrusted issue or pull-request content. Any
future bot workflow requires explicit maintainer authorization, least-privilege GitHub
permissions, immutable action SHAs, fork-safe secret handling, and a documented threat review.
