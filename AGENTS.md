# Project Instructions

These rules guide AI assistants when working on this project. Prefer the existing project conventions over generic advice when there is a conflict.

## 1. Core Principles

1. KISS is the primary principle and takes priority: always use the simplest working solution that satisfies the task.
2. Do not add abstractions, layers, configuration, or future-proofing unless they solve a real current problem.
3. When a simple shortcut would violate an existing invariant or architectural boundary, choose the solution that preserves the invariant.
4. Before making changes, inspect the closest relevant existing code and follow its naming, structure, formatting, and patterns.
5. Change only what is related to the task. Avoid unrelated refactors, renames, formatting churn, or dependency changes.
6. Do not overwrite or revert user-made changes unless explicitly asked.

## 2. Priorities

- Reliability and maintainability are more important than micro-optimizations.
- Clear, boring code is preferred over clever code.
- Performance-sensitive paths should be optimized only with evidence.

When priorities conflict, call out the tradeoff briefly before choosing.

## 3. Architecture Boundaries

- Keep transport code thin: controllers, routes, and handlers should validate input, call business logic, and shape responses.
- Put business rules in the service/domain layer.
- Keep data access in the persistence layer used by the project.
- Use the existing write path for create, update, and delete operations. Do not introduce a second write path without a clear reason.
- Keep significant domain values, environment-dependent values, and repeated literals in configuration or named constants.
- Do not move obvious local values into global configuration without a real benefit.
- If initialization order matters, make it explicit in code or documentation.

## 4. Invariants And Safety

- Validate user input through shared, reviewed validation utilities where they exist.
- Do not create one-off security checks when a common validation path is available.
- Do not return tracebacks, stack traces, secrets, or internal error details to clients.
- Log internal error details through the project logging mechanism.
- Do not disable protective mechanisms such as authentication, authorization, rate limiting, CSRF protection, validation, or security middleware.
- Change middleware, interceptor, or hook ordering only after checking the consequences.
- Shared mutable resources must have explicit concurrency protection where needed: transactions, locks, queues, unique constraints, idempotency keys, optimistic locking, or another suitable mechanism.

## 5. Code Conventions

- Comments and docstrings should explain why something exists, not restate what the code says.
- Use the project's centralized logging approach. Logging must not crash the application.
- Preserve the existing API response format and error envelope.
- Keep cacheable state in the established location for this project.
- Use named constants for cache keys and other repeated identifiers.
- Keep one consistent language and tone for comments, logs, API messages, and user-facing text within the project.

## 6. Process And Verification

- Use the project's isolated development environment: virtual environment, container, lock files, or package manager conventions.
- Before finishing a task, run the relevant tests, linters, type checks, build, or import checks when feasible.
- If verification cannot be run, state what was not run and why.
- If verification requires an environment you cannot access (deployed server, external services), do not simulate it. Ask the user to verify, with a precise one-shot request: exactly what to run or open, the expected result, and what to send back (logs, responses, screenshots).
- Update documentation when architecture, public behavior, setup steps, or data flows change.
- Do not commit generated noise such as caches, logs, temporary files, build artifacts, or test artifacts unless the project explicitly tracks them.
- Keep clear which checks can run locally and which require a deployed or integrated environment.
- Do not run long-lived processes such as dev servers as foreground verification commands that are expected to exit. Start them in the background or detached, then verify readiness with short bounded probes.
- Verification must use commands that terminate, such as builds, tests, or HTTP probes. A long-running server process is not a test result.

## 7. AI Assistant Behavior

- If the task is ambiguous, clarify requirements before starting large changes.
- Answer concisely by default.
- In final responses, summarize what changed and mention the relevant files.
- Do not paste code fragments or listings unless asked. Exception: if the answer would be wrong or unclear without one, include a minimal fragment (1-3 lines).
- When explaining where to look, reference file paths, functions, classes, or commands directly.
- Provide detailed explanations or quoted code only when explicitly requested.

## 8. Current Windows Environment

- If patching fails because of Windows sandbox or ACL restrictions, retry with relative paths. If it remains blocked, use an allowed unified-diff fallback such as `git apply`, with scoped approval when required. Never rewrite whole files as a workaround.
- Before Android checks, locate the installed SDK and set `ANDROID_HOME` for the current process. Do not commit local SDK paths.
- On Gradle `AccessDeniedException`, stop Gradle daemons and clean only generated build or cache directories. If locking repeats, redirect temporary build caches to `C:\tmp`.
- Before Docker-dependent checks, verify Docker Engine readiness. If Docker Desktop is stopped, start it in the background and poll readiness before continuing.
- If no ADB device or emulator is available, run JVM tests, lint, APK builds, and build instrumentation tests. Mark Bluetooth, BM008, and SCO verification as pending and request one precise physical test with diagnostic codes.
- Generate and test Linux artifacts inside the pinned Linux container. Normalize PowerShell or Windows CRLF input to LF before passing scripts to `sh`.
- If a Windows network client fails because of its TLS stack, retry the same read-only probe using Python TLS or a containerized Linux client.

## 9. Project-Specific Invariants

- Keep all repository content in English, including documentation, code
  comments, docstrings, logs, API and UI messages, tests, and fixtures.
- Before changing files, inspect the Git status and read the closest relevant
  documentation in addition to the implementation.
- Never delete, replace, or regenerate `android/debug.keystore`. It is the
  stable signing identity for direct-distribution Android updates.
- That key is local and ignored by Git. A clean public checkout uses its own
  signing key through `android/signing.properties` for distributed APKs.
- Increment Android `versionCode` for every distinct APK. Never reuse a
  published version code for different bytes.
- Do not change the wire protocol, HTTP or WebSocket routes, release metadata
  format, or four-file hosting contract unless the task explicitly requires a
  contract change.
- For a release, run `scripts/test-full.ps1`, then create and validate the
  delivery only with `scripts/build-hosting-package.ps1`.

## 10. Development And Publication Workflow

- Follow `docs/DEVELOPMENT_WORKFLOW.md`: change code, run local checks, deliver
  the package to the test host, run acceptance checks, and repeat as needed.
- Create a local project commit only after an explicit user command to commit.
  Use an English Conventional Commits subject. A deployment or a passing test
  does not authorize a commit.
- Push only after a separate explicit user command to push. A command to commit
  does not authorize a push, tag, GitHub Release, or pull request.
- Review the staged diff before committing and all outgoing commits before
  pushing. Run the publication checks and never bypass the Git hooks.
- Keep test-host configuration, credentials, transfer helpers, deliveries, and
  acceptance evidence in ignored local paths. Public scripts and documentation
  must use placeholders and must not contain the operator's host details.
- Keep the tested source unchanged until the requested commit. If source or
  build configuration changes, repeat the affected build and acceptance checks.
