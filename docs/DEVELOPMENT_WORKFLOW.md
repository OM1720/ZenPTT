# Development and publication

Status: current

Purpose: keep test-host iterations, local commits, and GitHub publication separate.

Audience: maintainers and development assistants.

Authority: this document owns the user-controlled development and publication cycle.

Code anchors: `.githooks`, `scripts/check-publication.ps1`,
`scripts/check-publication.py`, and `scripts/setup-publication.ps1`.

Update when: commit/push authorization, publication checks, or local configuration changes.

## Prepare the public checkout

Use a clone of the public repository, whose initial commit is
`232b520916009327249dfbfda1f6e3a75cb0a04d`. Keep the previous private repository
as a local archive without a GitHub remote. Do not merge its history into this clone.

1. Create `.venv` and install `server[dev]` and `headless[dev]` as described in
   [TESTING.md](TESTING.md). Keep the Android SDK and Docker available locally.
2. Create ignored `server.env` from `server.env.example`, using the intended test
   host's DNS. If migrating an existing workstation, retain its reviewed settings.
3. Create ignored `android/signing.properties` using the operator's existing
   signing key. An absolute `storeFile` path can reference that key outside this
   clone. Never replace or regenerate a key used by installed clients.
4. Preserve the existing published APK and its `server/releases/release.json`
   when migrating workstations. Verify the APK size and SHA-256 against the
   metadata. This preserves the local version-code collision check.
5. Run `./scripts/setup-publication.ps1`. On Windows x64 it installs Gitleaks
   8.30.1 into ignored `private/tools`, verifies the archive checksum, and enables
   repository hooks through local Git configuration. It does not commit or push.

For other systems, install Gitleaks 8.30.1 and PowerShell on PATH, then configure
`core.hooksPath=.githooks`, `zenptt.publicRoot` to the initial commit above, and
`zenptt.publicRemote` to the intended exact push URL using `git config --local`.
For a fork or an SSH remote, update only the local `zenptt.publicRemote` value.
After cloning on Unix, ensure the files in `.githooks` are executable.

Keep operator-specific transfer commands and host details in ignored `private/`.
Keep deliveries in `dist/hosting` and reports in `support/` or
`acceptance/artifacts/`. Generic build/install scripts and placeholder examples
belong in source control. Do not put real host settings in those public files.

## 1. Change, test, deploy, and repeat

Change code and run the relevant local tests. Build the delivery with
`scripts/build-hosting-package.ps1`; it runs the full local gate and validates
the four-file package. For a release, first run `scripts/test-full.ps1` as required
by the project instructions. Never use `-SkipChecks` as acceptance evidence.

Transfer all four delivery files together, run `sudo bash install-update.sh` on
the test host, then follow [DEPLOYMENT.md](DEPLOYMENT.md) and the applicable
physical checks in [MANUAL_TESTING.md](MANUAL_TESTING.md). Record the tested
behavior, APK version and hash, and package hashes in ignored acceptance evidence.
Fix failures and repeat this step. Passing tests do not authorize a commit or push.

Increment Android `versionCode` before every distinct APK, including test-host
builds. Changing `ZENPTT_DOMAIN` changes the default address compiled into the APK.
Use `-ReusePublishedApk` only when intentionally retaining the exact published
APK for a server or browser-only update. GitHub source releases use project-wide
`vX.Y.Z` tags starting at `v0.9.0`, independently of Android `versionName`.
Neither a source tag nor `versionName` changes for every development iteration;
source release tags are separate explicit actions.

## 2. Commit locally only when requested

After the user's explicit commit command, review the diff and test evidence.
Keep the tested source and build configuration unchanged; after a change, repeat
the affected checks. Stage only the intended source files and review
`git diff --cached` before creating the commit.

When first adding the hooks on Windows, stage their executable Git mode with
`git update-index --chmod=+x .githooks/pre-commit .githooks/commit-msg .githooks/pre-push`
so subsequent Unix clones can run them directly.

Use an English Conventional Commits subject of at most 72 characters, for example
`fix(android): restore audio after reconnect`. Types include `feat`, `fix`, `docs`,
`refactor`, `test`, `build`, `ci`, and `chore`. GitHub itself does not require this
format. The hook checks the structure and ASCII subject; the author reviews the
actual English wording.

The `pre-commit` hook checks the complete Git index, including staged content
that differs from the working copy. It rejects ignored and private paths, detects
the local deployment domain, and scans for secrets. `commit-msg` checks the
message format and scans the message. Neither hook creates a commit or a push.

Use `./scripts/check-publication.ps1 -Mode worktree` to inspect current source
without staging it, or `-Mode staged` to inspect the current index.

## 3. Push only when separately requested

A commit command does not authorize a push. When the user explicitly requests
a push, verify `gh api user --jq .login`, the destination, the outgoing commits,
and their acceptance evidence. For the owner's repository the account is `OM1720`.
Push only the intended branch; do not use `--all`, `--mirror`, or force options.

Before pushing, compare the outgoing commits with the latest GitHub release.
Recommend whether to retain its version or use a new one, and give the exact
proposed version and reason. State separately whether the Android APK changes
and whether `versionCode` or `versionName` must change. The user decides for
these outgoing commits; if that decision is not already explicit, ask before
pushing. Do not change version fields or create, move, or publish tags or
releases based on a push request alone. If the decision requires a source or
build change, repeat the affected checks and acceptance before publication.

The `pre-push` hook checks the actual refs Git is about to send. It rejects an
unexpected remote or a different initial Git root. It scans every reachable
commit, including files later deleted, and annotated tag metadata. It checks
private paths and commit subjects as well as secrets. Scanning the entire reachable
history also covers new branches and tags without relying on a stale remote ref.

Wait for GitHub CI after a successful push. CI runs after upload and therefore
does not replace the local checks. The hooks can be bypassed by Git options or
configuration, so do not bypass them. Secret scanners cannot recognize every
possible credential; staged and outgoing diff review remains required.

## Verify the publication controls

Run `python scripts/test-publication.py` and
`./scripts/check-publication.ps1 -Mode worktree`. The tests use isolated temporary
repositories with synthetic commits and local remotes; they do not commit or push
the project. Application builds and physical acceptance belong to step 1 when
application code or delivery behavior changes.
