# MineCICD 3.0 — Design

> This document is the working plan for the MineCICD 3.0 rewrite. It is the single
> source of truth for scope and decisions; iterate here before changing code.
>
> Status legend: `[x] done` · `[~] in progress` · `[ ] todo`

## Goal

A modern **Paper 1.21+** plugin that turns a Minecraft server root into a Git
repository: server changes are pushed to a remote, and remote changes are applied
to the server (pull + defined actions) **driven by GitHub Actions** through an
active, HMAC-authenticated control API (with an optional passive GitHub push
webhook) — plus in-game observability (audit log, analytics, Discord
notifications), scheduled/approved auto-pull, post-deploy health checks with
auto-rollback, and rich error suggestions.

The previous implementation (`srcOld/`) is the **feature baseline**. It is kept
for reference but its implementation is ignored — everything under `src/` is a
fresh, friendlier, and safer rewrite.

## Decisions (locked)

| Topic          | Decision                                                        |
| -------------- | --------------------------------------------------------------- |
| Scope          | Full feature parity with the old plugin + improvements           |
| Command system | Paper Brigadier commands (registered via `LifecycleEvents.COMMANDS`) |
| Threading      | Paper only (Bukkit async + sync scheduler), single-operation queue |
| Identity       | Package `com.lemonlightmc.minecicd`, plugin name `MineCICD`       |
| Messages       | MiniMessage (Adventure) with automatic migration of legacy `&` messages |
| Java           | Target 21, built with JDK 21+, shadow jar bundles JGit + org.json |
| Secrets        | Pure-Java clean/smudge filter executed via `java -jar` (no sed, no bundled .exe) |

## Feature checklist (parity + improvements)

- [x] Repo root == server root; tracking controlled via plugin-managed `.gitignore` section
- [x] `/minecicd init` — initialize the local repo (configured branch, local `origin` remote) with no fetch/pull/push
- [x] `/minecicd deinit` — remove the local repo's `.git` metadata only; refuses monorepo checkouts and never touches the remote
- [x] `/minecicd pull [force]` — init repo on first run, then fetch + merge (THEIRS) + apply
- [x] `/minecicd push <message>` — stage all, commit, push (adds author/committer identity)
- [x] `/minecicd add <path>` / `/minecicd remove <path>` — edits the plugin `.gitignore` section, commits, pushes
- [x] `/minecicd reset <commit>` / `revert <commit>` / `rollback <date>`
- [x] `/minecicd log <page|commit>` / `status` / `diff <local|remote>`
- [x] `/minecicd script <name>` and `/minecicd resolve <mode>`
- [x] `/minecicd reload`
- [x] Control API (HTTP): GH Actions job drives actions in order
- [x] Optional passive GitHub push webhook (`POST /<path>/webhook/github`, HMAC-verified, branch-filtered) as a second pull trigger
- [x] Scheduled auto-pull (opt-in interval + quiet hours + failure suspension)
- [x] In-game change preview: pending pulls gated by `/minecicd confirm|cancel`
- [x] Audit log (JSONL), deployment analytics (per-day summaries), Discord webhook notifications
- [x] Post-deploy health checks (script/command/required plugins) + automatic rollback
- [x] Rich error suggestions (`{suggestion}` placeholders on common failures)
- [x] Official reusable GitHub Action (`lemonlightmc/minecicd-deploy`, separate repo) that signs + calls the API, streams plugin messages, and **polls terminal status** past the SSE stream
- [x] Pending-action persistence: queued actions survive a server restart and **resume after full server start**
- [x] **Fail-stop** action sequences: the first failed action aborts the rest of the request
- [x] Per-action enable flags, with `commands`/`scripts` gated by explicit command/script allowlists, unsafe actions off by default
- [x] Commit-message actions: `CICD restart|global-reload|reload <p>|run <cmd>|script <s>`
- [x] Secrets (`secrets.yml`): masked in repo, smudged into working tree, cross-platform
- [x] Scripts (`plugins/MineCICD/scripts/*.sh`): console commands + `! shell` lines
- [x] Bossbar feedback (Adventure boss bars) to `minecicd.notify`
- [x] Permissions `minecicd.*` per subcommand
- [ ] Experimental jar reload on pull (PlugManX) — ported, remains experimental

### Concrete improvements over the baseline

1. **Modern command stack** — Brigadier trees, typed arguments, native tab
   completion (file paths, scripts, commit hashes, log pages). No manual
   `TabCompleter`/string-splitting.
2. **Control API security (defense in depth, fail-closed)** — mandatory HMAC-SHA256
   (`control.secret`; API refuses to start when unset), constant-time compare, **timestamp+
   nonce replay guard**, **authenticated SSE/status endpoints**, **native TLS/HTTPS**,
   **configurable bind address (loopback default)**, **no client-supplied repo**,
   per-action enable flags + **exact command-name/script allowlists**, optional branch
   allowlist, single in-flight request, request-body size cap, and strict header/content-type
   handling. No unsigned or unauthenticated path.
3. **No busy-wait loops** — the old code polled a boolean; the new one serializes
   all operations through a single-thread operation queue backed by
   `CompletableFuture`.
4. **No external IP call in `/status`** — displays local bind address instead of
   hitting `checkip.amazonaws.com` on every status request.
5. **Secrets without OS-specific tools** — a pure-Java `replace` filter is bundled
   in the plugin jar and invoked as `java -jar` from git config; works on Windows,
   Linux/macOS, with both JGit and git CLI, and tolerates special characters (base64).
6. **Legacy file migration** — `&`-coded `messages.yml` files are converted to
   MiniMessage at load; missing config keys are backfilled from the bundled defaults.
7. **Config patching** — new `version` + missing-key backfill so upgrades never
   break on an old `config.yml`.
8. **Fixes** — `Script.run` no longer drops the first character of `!` shell
   commands; `DiffFormatter` no longer closed before scanning; errors are logged
   through SLF4J/plugin logger without `printStackTrace`.

## Layout (new `src/`)

```
com.lemonlightmc.minecicd
├── MineCICD                  main plugin (lifecycle, wiring, reload)
├── MineCICDConfig            typed config (records) + default backfill
├── messaging/Messages        MiniMessage loader, placeholders, legacy migration
├── messaging/Msg             tiny sender helpers (main-thread marshaling)
├── bossbar/BossBars          Adventure boss bar manager
├── git/GitService            all git ops (synchronous, worker-thread only)
├── git/GitIgnoreEditor       plugin-managed `.gitignore` section
├── git/CommitActions         parses `CICD ...` lines from commit messages
├── git/Results               records returned to callers (PullResult, …)
├── http/ControlServer        com.sun.net.httpserver listener (native TLS optional, single POST /<path> control API, MAX_BODY, headers)
├── http/ControlSecurity      HMAC verify (timestamp|nonce|requestId|body), constant-time, replay guard, per-action enable + exact command/script allowlist + script-name validation, branch check, single-in-flight
├── http/ControlTls           keystore load + HTTPS context (or reverse-proxy TLS)
├── http/ControlRequest       typed request body + persisted unit records
├── http/ControlAuth          authenticated SSE + status GET (same HMAC)
├── http/ProgressStream       SSE live progress per requestId (to Actions log)
├── http/ControlStatus        persisted per-request terminal status (completed/failed/interrupted) for polling after SSE drop
├── http/GitHubWebhook        X-Hub-Signature-256 verification + branch ref parsing (pure, testable)
├── events/DeploymentEvents   deploy lifecycle event bus (DEPLOY_STARTED/COMPLETED/FAILED/ROLLBACK_EXECUTED)
├── audit/AuditLogger         JSONL audit log (per-day files, secrets redacted, ) + page reads
├── analytics/Analytics       per-day deploy counters (successes/failures/rollbacks/duration/sources)
├── notify/DiscordNotifier    outbound Discord webhook sender (subscribes to the event bus)
├── schedule/AutoPullScheduler  opt-in interval auto-pull + quiet hours + failure suspension (state persisted)
├── approval/ApprovalStore    change-preview approvals (persisted, timeout sweep, RunFactory rebuild)
├── health/HealthCheck        post-deploy checks (script + command + required plugins, timeout)
├── errors/ErrorCatalog       failure-signature → {suggestion} mapping
├── pending/PendingStore      on-disk pending-actions store; resume scan after full server start
├── scripts/ScriptRunner      runs scripts (console + `! shell`)
├── secrets/SecretManager     secrets.yml → .gitattributes + .git/config filters
├── secrets/ReplaceFilter     pure-java clean/smudge engine (also CLI Main class)
├── command/MineCICDCommand   Brigadier tree + per-subcommand permissions
├── CicdService               orchestrates GitService + feedback + queue (the “verbs”)
└── util/Ids, util/Threads    small helpers
```

## Threading model

- All git/disk/network work runs on a single worker thread (operation queue).
  Only one repo operation ever executes at a time → no `busyLock` polling.
- Bukkit API touches (messages to senders, boss bars, dispatch commands, plugin
  unload/load) are marshaled to the server main thread via `getScheduler`.
- Control API requests only **ack** on the HTTP thread, then persist and enqueue work.
- Only **one control request may be in flight** at a time; a second is rejected with `409`
  (the single-operation queue serializes git work, but two concurrent requests with a
  `restart` would interleave dangerously).
- **Pending resume runs after the server is fully loaded** (post-tick-start), not in
  `onEnable`, because resumed `command:`/`script:` actions need the running scheduler.

## Git operations (behavioral notes)

- `git pull` uses merge strategy **THEIRS** with content-merge THEIRS exactly like
  before — the server wins, remote files overwrite local ones without conflicts.
- `add`/`remove` edit the managed section of `.gitignore` between the
  `# MineCICD GITIGNORE PART BEGIN MARKER` / `END MARKER` lines and always push.
- Commits created by the plugin use the configured `git.user` identity; push uses
  `git.user`/`git.pass` as username/password credentials.
- A `secrets.yml` in the server root is never tracked (`.gitignore` covers
  `/secrets.ym**`).

## Control API flow (job-driven deploy)

The GitHub Action (a client, never a passive push listener) calls the control API:

```
POST /<path>         # TLS (native HTTPS); body { branch?, actions: [...], requestId } — NO client repo
  |- read body up to MAX_BODY (400 / 413 if too large); enforce Content-Type: application/json
  |- verify headers + HMAC over canonical bytes (timestamp|nonce|requestId|body), constant-time   401
  |- replay guard: reject |now - timestamp| > window (e.g. 300s); fail if nonce already seen       409
  |- validate action names + per-action enable + exact command/script allowlist + branch allowlist 400 / 403
  |- reject if another request is in flight                                                        409
  |- persist request to pending store                     (survives restart)
  |- enqueue the ordered action list                       (single-operation queue)
  |- respond 202 immediately
  |- SSE progress stream (authenticated, same HMAC) + Cache-Control:no-store -> Action prints to log
  |- fail-stop: first failed action aborts the rest, request -> failed
  |- persist terminal status (completed/failed/interrupted); Action polls it via authenticated GET
  |- on restart, PendingStore resumes remaining actions after the server is fully loaded
  |- optional GitHub webhook: HMAC-verify + branch-filter, then enqueue the configured actions
  |- optional scheduled auto-pull (no boot pull): interval + quiet hours + failure suspension re-arms via /minecicd reload
```

The server **never accepts a `repo` from the client**: it always uses the configured `git.repo`.
This closes the RCE vector where a malicious remote URL would pull attacker-controlled files
(plugins, configs, scripts → shell execution) into the server root. The remote is a hard trust
boundary: only HMAC-authorized pullers may trigger pulls, and only from the pinned `git.repo`.

Action vocabulary (in order, any mix): `pull`, `push` (uses `control.push-message` default,
or `push:<message>` to override), `restart`, `reload` (global), `reload:<plugin>`,
`command:<cmd>`, `script:<name>`. Each maps to the same execution path as the commit-message
`CICD ...` actions, so both entry points share one runner.

**Fail-stop**: actions run in order; the **first failure aborts the rest of the request**.
E.g. a failed `pull` (conflict/fetch error) leaves the working tree in an uncertain state, so
a later `restart`/`script:` must NOT run against it — the request is marked `failed` and the
Action's step fails (unless `fail-on-error: false`).

Persistence semantics: each accepted request is written to `plugins/MineCICD/pending/`
before any action runs; the runner advances a pointer as actions complete; non-terminated
requests **resume after the server is fully loaded** on boot. `requestId` makes retries
idempotent (no double-run).

Progress is **live-streamed only** (SSE); however the **terminal status** (`completed` /
`failed` / `interrupted`) is persisted per request. Because a mid-sequence `restart` drops
the SSE stream, the Action resolves final status by polling the persisted per-request status —
never by trusting the stream to stay open to the end.

## Config model

```yaml
git:
  user: ""        # username or token
  pass: ""        # password or token
  repo: ""        # remote URL
  branch: "master"
experimental-jar-loading: false   # unsafe/experimental, off by default
bossbar: { enabled: true, duration: 100 }
control:
  host: "127.0.0.1"               # bind address; loopback by default, expose via reverse proxy
  port: 8080
  path: "minecicd"
  secret: ""                      # REQUIRED; API refuses to start when empty (HMAC, >= 32 bytes recommended)
  tls:                            # native HTTPS; keystore path + password (or PKCS config)
    enabled: false
    keystore: ""
    password: ""
  push-message: "Auto-commit by MineCICD"   # default message for `push` (or override with push:<message>)
  branches: []                    # optional branch allowlist; empty = configured git.branch only
  max-body-bytes: 65536           # request body size cap (DoS guard)
  replay-window-seconds: 300      # reject requests with a timestamp older than this
  actions:
    pull: true
    push: true
    restart: true
    global-reload: false          # unsafe, off by default
    reload-plugins: true          # individual plugin reload
    commands: false               # unsafe, off by default
      allow:                      # exact command-name allowlist (first whitespace token)
        - say
        - save-all
    scripts: false                # unsafe, off by default
      allow:                      # exact script file-name allowlist
        - deploy
        - backup
  github-webhook:                 # optional passive push trigger
    enabled: false
    secret: ""                    # GitHub webhook "Secret"; verified via X-Hub-Signature-256
    actions: [pull]               # actions to enqueue on a push to the configured branch
  rate-limit: { enabled: true, failures-only: true, failure-limit: 5, max-entries: 1000, window-seconds: 15 }
version: 30001
audit: { enabled: true, max-files: 10, max-age-days: 90 }       # plain-text audit log under plugins/MineCICD/audit/
notifications:
  discord:
    enabled: false
    url: ""                        # full Discord webhook URL
    username: "MineCICD"
    avatar-url: ""
    ping-role-id: ""               # role to ping on failures/rollbacks
    events: [deploy-start, deploy-success, deploy-fail, rollback]
auto-pull: { enabled: false, interval-minutes: 60, quiet-hours: { enabled: false, from: "03:00", to: "07:00" }, max-consecutive-failures: 5 }
approval: { enabled: false, timeout-seconds: 120, require-on: [manual], skip-if-no-changes: true }
health-check:
  enabled: false
  run-inside-actions: true
  runs-after-restart: true
  timeout-seconds: 60
  command: ""
  script: ""
  require-plugins: []
  auto-rollback: { enabled: true, restart-after: false }
```

- Each action type has its own enable flag; `commands`/`scripts` are **off by default** and,
  when enabled, are further restricted to an explicit allowlist (matching the old
  conservative `allow-global-reload: false` default, but finer-grained).
- `commands.allow` matches the **exact first token** (command name) of `command:<cmd>`; allowing
  `ban` does NOT allow `ban-ip`. An unknown action type, or a command/script not on its
  allowlist, rejects the request `403` (fail-closed).
- `scripts.allow` matches the exact script **file name**; names are validated against a safe
  pattern that rejects `..`, absolute paths, and separators (no path traversal to arbitrary
  shell files).
- `control.secret` length is linted at startup: a warning is logged if it is weaker than
  32 bytes, and the API **refuses to start** when it is empty.

### Auth / hygiene rules (applied throughout)
- HMAC covers the canonical bytes `timestamp|nonce|requestId|body` (never just the body), so
  headers and payload are bound together. Reject beyond `replay-window-seconds`.
- SSE progress and status/`GET` endpoints require the **same HMAC** (authenticated); responses
  carry `Cache-Control: no-store`.
- Secrets (`git.pass`, `control.secret`) are **never** written to logs, SSE, error bodies, or
  status; error responses are minimal and reveal no internal paths.
- Native `control.tls` (HTTPS) is supported via a keystore; with a reverse proxy instead, TLS
  terminates there and the loopback listener stays HTTP.

Old `webhooks:*` keys are migrated to `control:*` on load (config patching, see above).
The old per-type booleans map onto `action.*` enable flags; there is no legacy equivalent
for the command/script allowlists (they default to empty = everything disabled).

## Risks / open questions

Resolved:
- `experimental-jar-loading` defaults **false** (safe).
- `push` accepts a message (`push:<message>`) or falls back to `control.push-message` (i.e. a default auto message).
- Official Action lives in a **separate repo** `lemonlightmc/minecicd-deploy`, referenced as `uses: lemonlightmc/minecicd-deploy@v1`.
- **Opt-in scheduled auto-pull** (reverses the older "no auto-pull" stance): `auto-pull.enabled`, simple interval only (min 1 min, max-1-min poll), quiet hours, and a persisted failure counter that suspends the schedule after `max-consecutive-failures` (a `/minecicd reload` re-arms). No pull ever runs at boot.
- Deploy jobs that exceed the Actions timeout: the Action **polls terminal status** (not just the SSE stream) to still resolve the final result; the server keeps running pending work regardless.
- `control.secret` is **loaded once at startup**; rotating it requires a restart (documented).

Remaining watch-list (non-blocking):
- A user deploy that tries `global-reload` plus many plugins on a big network may still be slow; watchlist only, no decision needed yet.
- Scripts that use `! shell` run with the server process's OS privileges: enabling `scripts` is a host-level trust grant, so the script allowlist must be strictly curated.

## Security threat model (design contract)

- **RCE via remote content** — handled: server pulls only the pinned `git.repo`; no client `repo`
  override; pulls/`push` gated behind HMAC auth.
- **RCE via command/script actions** — handled: `commands`/`scripts` off by default, exact-name
  allowlists, script-name path-traversal checks.
- **Replay of captured requests** — handled: timestamp+nonce within `replay-window-seconds`,
  idempotent `requestId`, single-in-flight -> `409`.
- **Eavesdropping / MITM on the wire** — handled: native `control.tls` (HTTPS) or reverse-proxy
  TLS; HMAC also binds body+headers so tampering is detected even if TLS is misconfigured.
- **Information disclosure via SSE/status** — handled: endpoints require the same HMAC;
  `Cache-Control: no-store`; no secrets in any output.
- **Brute-forcing the secret** — mitigated: API must have a strong `control.secret` (lint warns
  < 32 bytes), constant-time compare, request-body cap, single-in-flight.
- **Untrusted remote repo itself** — a trust boundary: anyone who can push to `git.repo` can, on
  the next authorized `pull`, affect the server. It must be a repository you control and review.

## Verification

- `gradlew build` (compile + unit tests).
- Manual server test checklist lives in `docs/` (not yet created).
```