# Security Policy

## Supported versions

JellyWrist `0.0.1` is the first public source release. The default branch is the only
version considered for security fixes.

## Reporting a vulnerability

Do **not** disclose vulnerabilities, credentials, private server addresses, account
identifiers, certificate material, or exploit details in a public issue or pull request.
Use GitHub's private vulnerability reporting feature for this repository instead.

Include the affected component, reproduction steps, impact, and any suggested mitigation.
The maintainer will acknowledge a complete report when it is reviewed. No response-time
or bounty commitment is made during the early (0.0.x) period.

## Secrets and private infrastructure

This project must use synthetic fixtures and RFC-reserved example domains in source,
tests, documentation, screenshots, logs, and commit messages. If sensitive data is ever
committed, removing it in a later commit is insufficient: publication must stop, affected
credentials must be rotated, and reachable Git history must be sanitized or discarded.
