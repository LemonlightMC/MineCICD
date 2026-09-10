# MineCICD 3.0 — Architecture

Technical architecture document for the MineCICD plugin.

---

## Table of Contents

- [System Overview](#system-overview)
- [Module Breakdown](#module-breakdown)
- [Threading Model](#threading-model)
- [Git Operations Flow](#git-operations-flow)
- [Control API Flow](#control-api-flow)
- [Security Model](#security-model)
- [Secrets System](#secrets-system)
- [Config Model](#config-model)
- [Data Flow Diagrams](#data-flow-diagrams)
- [Persistence Model](#persistence-model)

---

## System Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Minecraft Server                             │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                      MineCICD Plugin                          │   │
│  │                                                              │   │
│  │  ┌──────────┐  ┌────────────┐  ┌─────────────┐             │   │
│  │  │ Command   │  │ Control    │  │ GitService  │             │   │
│  │  │ Handler   │  │ Server     │  │ (JGit)      │             │   │
│  │  └────┬─────┘  └─────┬──────┘  └──────┬──────┘             │   │
│  │       │               │                │                     │   │
│  │       └───────────────┼────────────────┘                     │   │
│  │                       │                                      │   │
│  │                ┌──────▼──────┐                               │   │
│  │                │ CicdService │                               │   │
│  │                │ (Orchestr.) │                               │   │
│  │                └──────┬──────┘                               │   │
│  │                       │                                      │   │
│  │            ┌──────────┼──────────┐                           │   │
│  │            │          │          │                            │   │
│  │     ┌──────▼───┐ ┌───▼────┐ ┌───▼──────┐                   │   │
│  │     │CommitAct.│ │Pending │ │Script    │                    │   │
│  │     │          │ │Store   │ │Runner    │                    │   │
│  │     └──────────┘ └────────┘ └──────────┘                    │   │
│  │                                                              │   │
│  │  ┌──────────┐  ┌────────────┐  ┌─────────────┐             │   │
│  │  │Messages  │  │BossBars    │  │SecretManager│             │   │
│  │  │(MiniMsg) │  │(Adventure)│  │(smudge/clean)│            │   │
│  │  └──────────┘  └────────────┘  └─────────────┘             │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                   Bukkit / Paper / Spigot API                 │   │
│  └──────────────────────────────────────────────────────────────┘   │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ HTTPS / SSH
                    ┌──────────▼──────────┐
                    │  Remote Repository  │
                    │  (GitHub, etc.)      │
                    └─────────────────────┘

                    ┌─────────────────────┐
                    │  GitHub Actions      │
                    │  (HMAC-signed POST)  │
                    └──────────┬──────────┘
                               │
                    ┌──────────▼──────────┐
                    │  Control API        │
                    │  (com.sun HTTP)      │
                    └─────────────────────┘
```

---

## Module Breakdown

### MineCICD (Main)

The top-level plugin class. Handles Bukkit lifecycle (`onEnable` / `onDisable`), wires all services together, and owns the reload path. Registers the command tree and starts/stops the Control API listener.

### MineCICDConfig

Typed configuration using Java records. Reads `config.yml` into immutable config objects at startup, with default-value backfill so missing keys are handled gracefully. Supports hot-reload via `/minecicd reload`.

### messaging/Messages

MiniMessage (Adventure) loader. Reads message templates from config, resolves placeholders (`<player>`, `<world>`, etc.), and performs legacy color-code migration (`§` → MiniMessage tags). Stateless — called by `Msg` helpers.

### messaging/Msg

Thin sender helpers. Marshals messages to the Bukkit main thread (required for player/chat API calls), sends boss bar updates, and dispatches notifications to players with `minecicd.notify` permission.

### bossbar/BossBars

Adventure-based boss bar manager. Creates, updates, and removes boss bars with configurable duration (in ticks). Used for visual feedback on long-running operations (pull, push, reset).

### git/GitService

Central Git module. All JGit operations live here — clone, fetch, merge, commit, push, reset, revert, log, diff. Runs exclusively on the single worker thread (never on the main thread). Returns typed `Results` records to callers.

### git/GitIgnoreEditor

Manages the plugin-managed section in `.gitignore`, delimited by `# >>> MineCICD >>>` and `# <<< MineCICD <<<` markers. The `add` and `remove` commands edit this section, then commit and push.

### git/CommitActions

Parses `CICD ...` directives from commit messages. Extracts action type (`restart`, `reload`, `run`, `script`) and parameters. Returns an ordered list of actions for the orchestrator to execute.

### git/Results

Typed records returned by `GitService` methods. Each result carries a success/failure flag, a human-readable message, and optional data (commit hash, log entries, diff output). Used by `CicdService` for feedback.

### http/ControlServer

HTTP listener built on `com.sun.net.httpserver.HttpServer`. Exposes a single `POST` endpoint for deploy requests. Runs on a dedicated thread pool. Acks immediately, persists the request, then enqueues it to the worker thread.

### http/ControlSecurity

HMAC-SHA256 verification. Validates `timestamp|nonce|requestId|body` canonical bytes against the signature header. Enforces constant-time compare, timestamp freshness, and nonce uniqueness (replay guard). Checks per-action enable flags and exact command/script allowlists.

### http/ControlTls

Loads a JKS or PKCS12 keystore and configures the HTTP server for HTTPS. Used when native TLS is enabled (`control.tls.enabled`). Otherwise, TLS is expected to be terminated at a reverse proxy.

### http/ControlRequest

Typed request body model. Deserializes the JSON POST body into a record containing the action list, parameters, requestId, timestamp, and nonce. Persists the unit to disk for crash recovery.

### http/ControlAuth

HMAC-authenticated SSE (`/stream`) and status (`/status`) GET endpoints. Clients connect with the same HMAC signature to receive live progress events or poll terminal status for a given `requestId`.

### http/ProgressStream

SSE (Server-Sent Events) manager. Streams real-time progress per `requestId` — operation start, step completion, errors, final status. Cleans up connections on timeout or client disconnect.

### http/ControlStatus

Persisted per-request terminal status. Stores the final outcome (success/failure) of each control request so it can be queried via the status endpoint after the SSE stream ends.

### pending/PendingStore

On-disk store for pending (queued) actions. Persists control requests that haven't completed yet. On server restart, pending actions are replayed after the full server start event (not during `onEnable`, to ensure all plugins are loaded).

### scripts/ScriptRunner

Executes scripts from `plugins/MineCICD/scripts/`. Supports Minecraft console commands and shell commands. Runs on the worker thread with output captured for feedback.

### secrets/SecretManager

Manages `secrets.yml`. At pull time, smudges secret values into the working tree (replacing placeholders). At commit time, runs the clean filter to strip secrets before committing. Never exposes secrets in logs, error messages, or SSE streams.

### secrets/ReplaceFilter

Pure-Java replacement engine. Performs key→value substitution on file contents. Used by the smudge (pull) and clean (commit) passes. Falls back to bundled `windows-replace.exe` / `linux-replace.exe` if the Java implementation isn't available.

### command/MineCICDCommand

Brigadier command tree. Registers `/minecicd` with all subcommands, per-subcommand permission checks, and argument parsing. Delegates to `CicdService` for execution.

### CicdService

Orchestrator. Receives command or control-API requests, queues them to the worker thread, coordinates `GitService` calls, `CommitActions` parsing, `ScriptRunner` execution, boss bar feedback, and notification dispatch. Ensures only one control request is in flight at a time (409 on concurrent). Emits deploy lifecycle events, honors the approval gate, and runs post-deploy health checks with auto-rollback.

### events/DeploymentEvents

Small in-process event bus. Emitters publish `DEPLOY_STARTED / DEPLOY_COMPLETED / DEPLOY_FAILED / ROLLBACK_EXECUTED` events; the audit logger, analytics, and Discord notifier subscribe. Listeners run on the emitting thread and never throw.

### audit/AuditLogger

Append-only JSONL log under `plugins/MineCICD/audit/YYYY-MM-DD.jsonl`. One JSON object per line (actor, source, action, outcome, message, requestId, branch). Secret values (`git.pass`, webhook/control secrets) are redacted before write. Supports paged reads for `/minecicd audit` and age-based trimming.

### analytics/Analytics

Per-day JSON counters under `plugins/MineCICD/analytics/YYYY-MM-DD.jsonl` (deploy count, successes/failures, rollbacks, total duration, per-source breakdown). Aggregated view fed to `/minecicd analytics`; `minecicd.analytics reset` clears the data.

### notify/DiscordNotifier

Outbound-only Discord webhook sender. Subscribes to the event bus, builds an embed payload, and POSTs it on its own single-thread executor so the worker queue is never blocked. Only the configured subset of events is sent.

### schedule/AutoPullScheduler

Opt-in interval auto-pull (`auto-pull` config). Simple interval only (min 1 min), respects quiet hours, skips when a control request is in flight, and suspends itself after `max-consecutive-failures` consecutive failures. State is persisted to `auto-pull-state.json` so a restart does not silently re-arm a broken schedule; `/minecicd reload` re-arms it.

### approval/ApprovalStore

Change-preview gate. When the approval config is enabled for a trigger source (`manual`, `scheduled`, `webhook`), a pull first fetches + diffs (`GitService.pullPreview`) and pends as an approval instead of applying. Operators confirm/cancel with `/minecicd confirm|cancel <id>`. Approvals are persisted under `plugins/MineCICD/approvals/` and rebuilt after a restart via the registered `RunFactory`; a periodic sweep expires them after `timeout-seconds`.

### health/HealthCheck

Runs after a pull applies changes and again when a pending control request resumes post-restart. Checks a configured script, an optional console command, and required plugins (must be loaded/enabled). Runs on its own executor with a timeout; a failure emits `DEPLOY_FAILED` and triggers auto-rollback through `GitService.rollbackDeploy`.

### http/GitHubWebhook

Pure signature verification (`X-Hub-Signature-256` HMAC) and branch-ref parsing for the optional passive webhook route `POST /<path>/webhook/github`. The webhook is HMAC-verified, branch-filtered, and enqueues the configured action list through the same single-in-flight path as the job-driven API.

### errors/ErrorCatalog

Pure mapping from failure signature (root-cause message) to an actionable `{suggestion}`. `CicdService.fail(...)` appends the suggestion to failure messages when a known signature matches; unknown failures keep the generic error text.

---

## Threading Model

```
┌─────────────────────────────────────────────────────────┐
│                   Bukkit Main Thread                     │
│                                                         │
│  Commands ──► CicdService ──► enqueue ──┐              │
│  (Brigadier)                            │              │
└─────────────────────────────────────────┼──────────────┘
                                          │
┌─────────────────────────────────────────▼──────────────┐
│                   Worker Thread                         │
│                                                         │
│  Operation Queue (FIFO)                                 │
│  ┌─────────────────────────────────────────────────┐   │
│  │ Git ops │ Script exec │ Pending resume │ Control │   │
│  └─────────────────────────────────────────────────┘   │
│                                                         │
│  Results ──► marshal back to main thread ──► Msg/BossBar│
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│              Control API HTTP Thread Pool                │
│                                                         │
│  POST /minecicd ──► HMAC verify ──► persist request     │
│                      ──► enqueue to worker ──► 202 ack  │
│                                                         │
│  GET  /minecicd/stream ──► SSE (same HMAC auth)        │
│  GET  /minecicd/status ──► terminal status (same auth)  │
└─────────────────────────────────────────────────────────┘
```

**Key rules:**

- All Git, disk, and network operations run on the single worker thread — never on the main thread.
- Bukkit API calls (player messages, boss bars) are marshaled back to the main thread.
- Control API requests are acked immediately on the HTTP thread, then the action is persisted and enqueued.
- Only one control request is in flight at a time. A second concurrent request receives `409 Conflict`.
- Pending-store resume runs after the full server start event, not during `onEnable`.

---

## Git Operations Flow

### Init

```
/minecicd init
    │
    ├─ Already a repo? ──► notify "already initialized"
    │
    └─ git init (configured branch)
         ├─ git.repo set? ──► add/update origin remote (local config only)
         └─ no fetch, pull, push, or commit
```

### Deinit

```
/minecicd deinit
    │
    ├─ Not a repo? ──► notify "not initialized"
    │
    ├─ .git outside server root? ──► refuse (monorepo, managed by parent repo)
    │
    └─ delete .git metadata recursively
         └─ working-tree files and remote untouched
```

### Pull

```
/minecicd pull
    │
    ├─ First run? ──► git clone ──► .gitignore setup ──► done
    │
    └─ Existing repo?
         │
         ├─ git fetch --all
         ├─ git merge origin/<branch>  (strategy: remote overwrites local)
         │      │
         │      ├─ Clean merge ──► smudge secrets ──► parse CICD actions
         │      │                                       ──► execute actions
         │      │                                       ──► boss bar + notify
         │      │
         │      └─ Conflict ──► abort merge ──► notify user
         │                          ──► suggest /minecicd resolve
         │
         └─ [force] flag? ──► git reset --hard origin/<branch>
```

### Push

```
/minecicd push "message"
    │
    ├─ git add -A
    ├─ clean filter (strip secrets)
    ├─ git commit -m "message"
    ├─ git push origin <branch>
    │      │
    │      ├─ Success ──► boss bar + notify
    │      └─ Failure ──► notify error
```

### Add / Remove

```
/minecicd add <path>
    │
    ├─ GitIgnoreEditor: add path to managed section
    ├─ git add <path>
    ├─ git commit -m "MineCICD: track <path>"
    └─ git push

/minecicd remove <path>
    │
    ├─ GitIgnoreEditor: remove path from managed section
    ├─ git rm --cached <path>
    ├─ git commit -m "MineCICD: untrack <path>"
    └─ git push
```

---

## Control API Flow

### Request Lifecycle

```
GitHub Action (or client)
    │
    ├─ Sign body: HMAC-SHA256(secret, "timestamp|nonce|requestId|body")
    ├─ POST https://server:8080/minecicd
    │     Headers:
    │       X-MineCICD-Timestamp: <unix seconds>
    │       X-MineCICD-Nonce: <uuid>
    │       X-MineCICD-Request: <uuid>
    │       X-MineCICD-Signature: <hex hmac>
    │     Body: {"actions":["pull","restart"], ...}
    │
    ▼
Control API (HTTP thread)
    │
    ├─ Extract headers + body
    ├─ HMAC verify (constant-time compare)
    ├─ Timestamp freshness check (replay-window-seconds)
    ├─ Nonce uniqueness check (replay guard)
    ├─ Per-action enable check (config.control.actions.*)
    ├─ Command/script allowlist check (if applicable)
    ├─ Persist request to PendingStore
    ├─ Enqueue to worker thread
    └─ HTTP 202 Accepted (body: {"requestId": "...", "status": "pending"})
    │
    ▼
Worker Thread
    │
    ├─ Load persisted request
    ├─ For each action in order:
    │     ├─ pull  ──► GitService.fetch + merge
    │     ├─ push  ──► GitService.add + commit + push
    │     ├─ restart ──► dispatch CICD restart
    │     ├─ reload-plugins ──► PlugManX reload
    │     ├─ commands ──► dispatch each command (allowlist check)
    │     └─ scripts ──► ScriptRunner (allowlist check)
    │
    ├─ Fail-stop: first failed action aborts remaining actions
    ├─ Write terminal status to ControlStatus
    ├─ Stream progress via SSE (ProgressStream)
    └─ Done
    │
    ▼
Client polls:
    ├─ GET /minecicd/stream  ──► SSE events (live progress)
    └─ GET /minecicd/status?requestId=<id>  ──► terminal status
```

---

## Security Model

### HMAC-SHA256 Authentication

Every control API request is authenticated with an HMAC-SHA256 signature over the canonical byte string:

```
timestamp|nonce|requestId|body
```

- **timestamp**: Unix seconds when the request was created.
- **nonce**: UUID, unique per request.
- **requestId**: UUID, identifies the request.
- **body**: Raw JSON request body.

The signature is sent in the `X-MineCICD-Signature` header as a hex string.

### Replay Guard

- **Timestamp freshness**: Requests older than `control.replay-window-seconds` (default 300s) are rejected.
- **Nonce uniqueness**: Each nonce is recorded. Reused nonpires within the window are rejected.
- Combined, these prevent replay attacks.

### Per-Action Enable Flags

Each action type has an enable flag in `control.actions`:

| Flag | Default | Effect |
|---|---|---|
| `pull` | `true` | Allow git pull |
| `push` | `true` | Allow git push |
| `restart` | `true` | Allow server restart |
| `global-reload` | `false` | Allow server reload |
| `reload-plugins` | `true` | Allow plugin reload via PlugManX |
| `commands.enabled` | `false` | Allow running commands |
| `scripts.enabled` | `false` | Allow running scripts |

### Exact Allowlists

When `commands.enabled` or `scripts.enabled` is `true`, only commands/scripts listed in the `allow` array are permitted. No wildcards, no substring matching — exact name match only.

### Trust Boundaries

- **No client-supplied repo**: The remote repository is always read from config. The client cannot override it.
- **Secrets never in logs/SSE/error bodies**: The `SecretManager` ensures secret values are never leaked.
- **Fail-stop**: The first failed action in a request aborts all remaining actions.
- **Request body size cap**: `control.max-body-bytes` (default 64KB) limits request size.
- **Single in-flight**: Only one control request executes at a time. Concurrent requests get `409`.

### TLS

- **Native TLS**: Enable `control.tls.enabled` with a JKS or PKCS12 keystore.
- **Reverse proxy** (recommended): Keep the listener on `127.0.0.1` and terminate TLS at nginx, Caddy, or similar. The HMAC secret is never sent in cleartext.

---

## Secrets System

The secrets system uses a Git clean/smudge filter pattern to keep sensitive values out of the repository.

### Flow

```
secrets.yml                    Working Tree                Repository
    │                              │                          │
    │  ┌───────────────────────────┼──────────────────────────┤
    │  │  On Pull (smudge)         │                          │
    │  │                           │                          │
    │  │  secrets.yml ──► ReplaceFilter ──► file with real    │
    │  │  (key→value)              │          values           │
    │  └───────────────────────────┼──────────────────────────┤
    │                              │                          │
    │  ┌───────────────────────────┼──────────────────────────┤
    │  │  On Commit (clean)        │                          │
    │  │                           │                          │
    │  │  file with real ──► ReplaceFilter ──► file with      │
    │  │  values                  │          placeholders     │
    │  └───────────────────────────┼──────────────────────────┘
```

### How It Works

1. **secrets.yml** defines key-value pairs per file.
2. On **pull** (smudge pass): MineCICD reads `secrets.yml` and replaces every key in the target file with its value.
3. On **commit** (clean pass): MineCICD reverses the substitution — real values are replaced back with placeholder keys before staging.
4. The repository only ever contains placeholder keys, never the actual secrets.
5. The `.gitattributes` file (managed by MineCICD) registers the clean/smudge filters.

### Implementation

- `SecretManager` owns `secrets.yml` parsing and filter registration.
- `ReplaceFilter` is a pure-Java engine that performs the key→value and value→key substitution.
- No external dependencies (no `sed`, no shell scripts).
- Bundled fallback binaries (`windows-replace.exe`, `linux-replace.exe`) exist for edge cases but are not used in normal operation.

---

## Config Model

Configuration is loaded at startup into typed Java records:

```
MineCICDConfig
├── GitConfig          (user, pass, repo, branch)
├── ExperimentalConfig (jarLoading)
├── BossBarConfig      (enabled, duration)
└── ControlConfig
    ├── host, port, path, secret
    ├── tls             (enabled, keystore, password)
    ├── pushMessage, branches
    ├── maxBodyBytes, replayWindowSeconds
    └── actions
        ├── pull, push, restart, globalReload, reloadPlugins
        ├── commands    (enabled, allow[])
        └── scripts     (enabled, allow[])
```

- Records are immutable. Changes require creating new instances (reload path).
- Default values are backfilled for any missing keys in `config.yml`.
- Reload (`/minecicd reload`) re-reads the file and reconstructs all config objects.

---

## Data Flow Diagrams

### Command → Action → Feedback

```
Player types /minecicd push "msg"
    │
    ▼
MineCICDCommand (Brigadier)
    │  parse args, check permission
    ▼
CicdService.push("msg")
    │  enqueue to worker thread
    ▼
Worker Thread
    │  GitService.add()
    │  GitService.commit("msg")
    │  GitService.push()
    ▼
Results (success/fail + message)
    │
    ▼
CicdService
    │  marshal to main thread
    ▼
Msg.sendActionBar(player, "<green>Pushed!")
BossBars.show(player, "Push complete", duration)
```

### Control API → Deploy → Feedback

```
GitHub Action POST (HMAC-signed)
    │
    ▼
ControlServer (HTTP thread)
    │  ControlSecurity.verify()
    │  ControlRequest.persist()
    ▼
CicdService.enqueue(request)
    │
    ▼
Worker Thread
    │  GitService.fetch()
    │  GitService.merge()
    │  CommitActions.parse(message)
    │  ScriptRunner.execute("deploy")
    ▼
ProgressStream.push(requestId, "Pull complete")
ControlStatus.write(requestId, "SUCCESS")
```

---

## Persistence Model

### PendingStore

Stores queued actions that haven't completed yet (e.g., a control request received during shutdown).

- **On disk**: `plugins/MineCICD/pending/` — one file per pending request.
- **On boot**: After the full server start event, pending requests are loaded and re-enqueued to the worker thread.
- **On completion**: The pending file is deleted.

### ControlStatus

Stores terminal status of completed control requests.

- **On disk**: `plugins/MineCICD/status/` — one file per request.
- **Lifecycle**: Created when a request completes, available for polling via the status endpoint, cleaned up after `replay-window-seconds`.

### ControlRequest

Persists the full request body and metadata for crash recovery.

- **On disk**: Written to `PendingStore` before enqueue.
- **On recovery**: Loaded and re-enqueued if the server crashed mid-request.
- **On completion**: Moved to `ControlStatus` or deleted.
