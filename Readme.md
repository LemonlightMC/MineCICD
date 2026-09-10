# MineCICD 3.0.0

**Continuous Integration & Continuous Delivery for Minecraft Servers.**

MineCICD turns your Minecraft server root into a Git repository, letting you track server configurations, plugins, and scripts in version control. Pull remote changes, push server edits, define commit-triggered actions (restart, reload, run commands, run scripts), and automate deploys from GitHub Actions - All without leaving the server.

## Table of Contents

- [Prerequisites](#prerequisites)
- [Installation](#installation)
- [Configuration](#configuration)
- [Basic Usage](#basic-usage)
- [Commit Actions](#commit-actions)
- [Secrets](#secrets)
- [Scripts](#scripts)
- [Commands Reference](#commands-reference)
- [Permissions Reference](#permissions-reference)
- [GitHub Actions Setup](#github-actions-setup)
- [Migrating from 1.x / 2.x](#migrating-from-1x--2x)
- [Troubleshooting](#troubleshooting)

---

## Prerequisites

| Requirement      | Details                                                                                                              |
| ---------------- | -------------------------------------------------------------------------------------------------------------------- |
| Minecraft server | Paper (recommended) or Spigot, version 1.8 – 1.21+                                                                   |
| Java             | 21 or newer                                                                                                          |
| Git repository   | A remote repository (GitHub, GitLab, Bitbucket, self-hosted)                                                         |
| Access token     | A GitHub Personal Access Token with **contents: read + write** scope on the repo (or equivalent for other providers) |

## Installation

1. Download the latest `MineCICD.jar` from the [Releases page](https://github.com/Konstanius/MineCICD/releases).
2. Drop the JAR into your server's `plugins/` folder.
3. Restart the server. MineCICD generates its default config and scripts on first boot.
4. Edit `plugins/MineCICD/config.yml` with your Git credentials and repository URL (see [Configuration](#configuration)).
5. Run `/minecicd reload` to apply the config, then `/minecicd init` to initialize the repo locally (or `/minecicd pull` to clone it from the remote).

---

## Configuration

All settings live in `plugins/MineCICD/config.yml`.

### Git credentials

```yaml
git:
  user: "" # Username or Personal Access Token
  pass: "" # Password or Personal Access Token
  repo: "" # Remote repository URL (https:// or ssh://)
  branch: "master"
  remote-server-root: "" # Optional: path of the server root within the repo (monorepo)
```

- **HTTPS remotes**: Set both `user` and `pass` to your Personal Access Token.
- **SSH remotes**: Use an SSH URL (`ssh://git@github.com/user/repo.git` or `git@github.com:user/repo.git`) with a deploy key. Leave `user` / `pass` empty.
- Plain `git://` URLs are rejected (no auth or encryption).
- **Monorepo (`remote-server-root`)**: `remote-server-root` is the path of the server root **within** the repository (on the remote). On the host, the server root is *always* the folder that contains `plugins/` (the parent of `plugins/MineCICD/`) — it is never derived from this setting. Leave it empty when the server root *is* the repository root. For a monorepo (e.g. the server's files live under `servers/lobby/` in the remote), set it to `servers/lobby` and make sure the host server folder physically sits at that path inside the repository checkout; the `.git` directory is then discovered by searching upward to the repository root.

### Control API

The Control API lets GitHub Actions (or any HMAC-signed client) trigger deploys on your server.

```yaml
control:
  host: "127.0.0.1" # Bind address — keep on loopback
  port: 8080
  path: "minecicd" # URL path suffix
  secret: "" # REQUIRED — at least 32 bytes, used for HMAC-SHA256
  tls:
    enabled: false # Enable native HTTPS (see below)
    keystore: ""
    password: ""
  push-message: "Auto-commit by MineCICD"
  branches: [] # Empty = all branches
  max-body-bytes: 65536
  replay-window-seconds: 300
  actions:
    pull: true
    push: true
    restart: true
    global-reload: false
    reload-plugins: true
    commands:
      enabled: false
      allow: [say, save-all] # Exact command names only
    scripts:
      enabled: false
      allow: [deploy, backup] # Exact script names only
```

> **Security**: Keep the listener on `127.0.0.1` and terminate TLS at a reverse proxy (nginx, Caddy, etc.). Never expose the raw HTTP endpoint to the internet. Alternatively, enable native HTTPS with `control.tls` using a JKS or PKCS12 keystore.

### Other options

```yaml
experimental-jar-loading: false # Hot-load plugin JARs (requires PlugManX)
bossbar:
  enabled: true
  duration: 100 # Ticks to show boss bar feedback
```

### Automatic deploys & observability

```yaml
# Passive GitHub push webhook (optional second pull trigger, alongside the job-driven API)
control:
  github-webhook:
    enabled: false
    secret: ""       # GitHub webhook "Secret"; verified via X-Hub-Signature-256
    actions: [pull]  # actions enqueued on a push to the configured branch
  rate-limit: { enabled: true, failures-only: true, failure-limit: 5, max-entries: 1000, window-seconds: 15 }

# Scheduled auto-pull (opt-in; simple interval, quiet hours, failure suspension)
auto-pull:
  enabled: false
  interval-minutes: 60
  quiet-hours: { enabled: false, from: "03:00", to: "07:00" }
  max-consecutive-failures: 5 # schedule suspends itself after this many failures (reload re-arms)

# Approval gate: pending pulls must be confirmed in-game with /minecicd confirm
approval:
  enabled: false
  timeout-seconds: 120
  require-on: [manual]   # manual | scheduled | webhook
  skip-if-no-changes: true

# Post-deploy health checks + automatic rollback
health-check:
  enabled: false
  run-inside-actions: true      # check after each pull/action sequence
  runs-after-restart: true      # check again when a pending request resumes after restart
  timeout-seconds: 60
  command: ""                   # optional console command to run
  script: ""                    # optional script name from scripts/
  require-plugins: []           # plugin names that must be loaded after the deploy
  auto-rollback: { enabled: true, restart-after: false }

# Audit log (JSONL) and deployment analytics (per-day summaries)
audit: { enabled: true, max-age-days: 90 }
notifications:
  discord:
    enabled: false
    url: ""                # full Discord webhook URL
    username: "MineCICD"
    avatar-url: ""
    ping-role-id: ""       # role pinged on failures/rollbacks
    events: [deploy-start, deploy-success, deploy-fail, rollback]
```

- The **GitHub webhook** route is `POST /<control.path>/webhook/github`. It is HMAC-verified with GitHub's `X-Hub-Signature-256`, ignores non-push events, and enqueues the configured actions only for pushes to the configured branch. Expose it with the **same** posture as the Control API (loopback + reverse-proxy TLS).
- With the approval gate enabled for a source, pulls are staged as previews and only applied after an operator runs `/minecicd confirm <id>` (or expire via `cancel`/timeout).
- A failed health check marks the deploy failed and triggers auto-rollback to the previous commit (`restart-after` optionally restarts the server again).

---

## Basic Usage

The typical workflow is: **init → add → commit → push → pull**.

```
# 0. Initialize the local repository (does not pull or push)
/minecicd init

# 1. Track files or directories
/minecicd add plugins/MyPlugin/
/minecicd add server.properties

# 2. Commit and push your changes
/minecicd push "Updated MyPlugin config"

# 3. On another server (or after a remote push), pull changes
/minecicd pull
```

### What to track

| Track                                              | Don't track                             |
| -------------------------------------------------- | --------------------------------------- |
| Plugin configs (`plugins/*/config.yml`)            | Player data (`world/playerdata/`)       |
| Server configs (`server.properties`, `bukkit.yml`) | World files (`world/`, `world_nether/`) |
| Scripts and tooling                                | Runtime-generated logs                  |
| Custom plugin JARs (with caution)                  | Dynamically updated databases           |

---

## Commit Actions

Append `CICD` directives to any commit message. MineCICD parses them on pull and executes the actions in order.

```yaml
Updated lobby signs
CICD restart
CICD run say Server updating in 5 seconds
CICD script deploy
```

| Directive              | Behavior                                                                                                        |
| ---------------------- | --------------------------------------------------------------------------------------------------------------- |
| `CICD restart`         | Stops the server (your restart script / host process handles restart)                                           |
| `CICD global-reload`   | Runs the server `reload` command                                                                                |
| `CICD reload <plugin>` | Unloads and reloads a specific plugin (requires [PlugManX](https://www.spigotmc.org/resources/plugmanx.88135/)) |
| `CICD run <command>`   | Executes a Minecraft command (e.g., `say Hello`)                                                                |
| `CICD script <name>`   | Runs a script from `plugins/MineCICD/scripts/`                                                                  |

Multiple `reload`, `run`, and `script` directives can appear on separate lines. Only one of `restart` / `global-reload` / `reload <plugin>` is performed per commit (first match wins).

---

## Secrets

Secrets let you store sensitive values (database passwords, API keys, license keys) that are substituted into tracked files at pull time but never committed to the repository.

### Setup

1. Edit `plugins/MineCICD/secrets.yml`:

```yaml
1:
  file: "plugins/MyPlugin/config.yml"
  database_password: "s3cret_p@ss"
  api_key: "abc-123-def"
2:
  file: "plugins/AnotherPlugin/config.yml"
  license_key: "XXXX-YYYY-ZZZZ"
```

2. Reload: `/minecicd reload`

When MineCICD pulls, it replaces every key in the working tree with its corresponding value. The clean filter reverses this before committing so secrets never enter the repository.

> **Windows note**: MineCICD ships a built-in replacement tool at `plugins/MineCICD/tools/windows-replace.exe`. Linux builds also include `linux-replace.exe` as a fallback if `sed` is unavailable.

---

## Scripts

Scripts are executable files in `plugins/MineCICD/scripts/`. They can contain Minecraft console commands, shell commands, or both.

1. Create a `.sh` file in the scripts directory (e.g., `deploy.sh`).
2. Reference it in a commit message: `CICD script deploy`
3. Or run it manually: `/minecicd script deploy`

See `plugins/MineCICD/scripts/example_script.sh` for syntax examples.

---

## Commands Reference

All commands are subcommands of `/minecicd` (alias: `/mcicd`).

| Command                                    | Description                                                               |
| ------------------------------------------ | ------------------------------------------------------------------------- |
| `/minecicd init`                           | Initialize the local repo without pulling or pushing.                     |
| `/minecicd deinit`                         | Remove the local `.git` metadata (no pull or push; remote untouched).     |
| `/minecicd pull [force]`                   | Fetch and merge remote changes. First run clones the repo.                |
| `/minecicd push <message>`                 | Stage all changes, commit, and push.                                      |
| `/minecicd add <path>`                     | Add a file or directory to the managed `.gitignore` and track it.         |
| `/minecicd remove <path>`                  | Remove a file or directory from tracking.                                 |
| `/minecicd reset <commit>`                 | Hard-reset the branch to a specific commit.                               |
| `/minecicd revert <commit>`                | Revert a commit's changes (creates a new commit).                         |
| `/minecicd rollback <dd.MM.yyyy HH:mm:ss>` | Hard-reset to the latest commit before the given date.                    |
| `/minecicd log [page\|commit]`             | View commit history, or details of a specific commit.                     |
| `/minecicd status`                         | Show plugin, repo, webhook, and change status.                            |
| `/minecicd diff <local\|remote>`           | Show uncommitted changes (local) or unpulled remote changes.              |
| `/minecicd script <name>`                  | Run a named script.                                                       |
| `/minecicd resolve <action>`               | Resolve conflicts: `merge-abort`, `repo-reset`, or `reset-local-changes`. |
| `/minecicd audit [page]`                   | View the audit log (latest page first).                                  |
| `/minecicd analytics [reset]`              | View deployment analytics summary, or reset the data.                    |
| `/minecicd confirm <id>`                   | Approve a pending pull (change-preview gate).                            |
| `/minecicd cancel <id>`                    | Reject a pending pull.                                                   |
| `/minecicd reload`                         | Reload config and webhook server.                                         |
| `/minecicd help`                           | Show help.                                                                |

---

## Permissions Reference

| Permission              | Description                                                     |
| ----------------------- | --------------------------------------------------------------- |
| `minecicd.<subcommand>` | Grants access to a specific subcommand (e.g., `minecicd.pull`). |
| `minecicd.notify`       | Receive in-game notifications from MineCICD actions.            |
| `minecicd.audit`        | View the audit log (`/minecicd audit`).                         |
| `minecicd.analytics`    | View/reset analytics (`/minecicd analytics`).                   |
| `minecicd.confirm`      | Approve/cancel pending pulls (`/minecicd confirm|cancel`).      |

Use a permissions manager (LuckPerms, etc.) to assign these to groups or players.

---

## GitHub Actions Setup

MineCICD provides an official GitHub Action for automated deploys: [lemonlightmc/minecicd-deploy](https://github.com/lemonlightmc/minecicd-deploy).

### Quick start

1. Set `control.secret` in your server's `config.yml` (at least 32 bytes).
2. Configure which actions are allowed under `control.actions` (enable only what you need).
3. Add the secret as a repository secret in GitHub (`Settings → Secrets → Actions`), e.g. `MINECICD_SECRET`.
4. Add the action to your workflow:

```yaml
- name: Deploy to server
  uses: lemonlightmc/minecicd-deploy@v1
  with:
    server-url: ${{ secrets.MINECICD_URL }} # e.g. https://your-server:8080/minecicd
    secret: ${{ secrets.MINECICD_SECRET }}
    actions: pull,restart # comma-separated list
```

The action signs each request with HMAC-SHA256 and drives the deploy to completion. You can poll the `/stream` (SSE) and `/status` endpoints with the same signature to follow progress.

### Passive webhook alternative

Instead of (or in addition to) the job-driven Action, GitHub can push to the server directly:

1. Set `control.github-webhook.secret` and `enabled: true`, then `/minecicd reload`.
2. In your GitHub repo: `Settings → Webhooks` → add the server URL `https://your-server:8080/minecicd/webhook/github`, content type `application/json`, and paste the same secret.
3. MineCICD verifies `X-Hub-Signature-256`, filters to the configured branch, and enqueues `control.github-webhook.actions` (default `pull`).

> Expose the webhook only through TLS (reverse proxy or `control.tls`); never forward the raw port.

---

## Migrating from 1.x / 2.x

Version 3.0.0 is a complete rewrite. The config format has changed and the plugin directory will reset.

1. **Push all in-progress changes** on your server to the remote repository.
2. **Stop the server** and back up `plugins/MineCICD/config.yml` (copy your token, repo URL, and branch name).
3. **Delete** the entire `plugins/MineCICD/` directory (no server files are affected).
4. **Install** the new MineCICD 3.0.0 JAR.
5. **Start the server** — MineCICD generates a fresh config.
6. **Edit** `config.yml` with your Git credentials, repo URL, and branch.
7. Run `/minecicd reload` then `/minecicd pull`.
8. MineCICD should auto-detect tracked files. If not, add them manually with `/minecicd add`.

> Your `secrets.yml` and `scripts/` are also reset. Back them up before step 3 and restore them after step 7.

---

## Troubleshooting

| Problem                                      | Fix                                                                                                           |
| -------------------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| `/minecicd pull` says "repo not initialised" | Run `/minecicd init` (or `/minecicd pull`) to initialize the repo. Check that `git.repo` is correct. |
| Push fails with 401 / 403                    | Verify your Personal Access Token has **contents: read + write** scope. Check `git.user` and `git.pass`.      |
| SSH remote fails                             | Ensure the server's SSH key is added as a deploy key on the remote. Check `ssh-keyscan` output.               |
| Control API returns 403                      | The HMAC signature is invalid. Ensure `control.secret` matches on server and client. Check timestamp skew.    |
| Control API returns 409                      | Another request is already in flight. Wait for it to finish (only one request at a time).                     |
| Secrets not replacing                        | Run `/minecicd reload` after editing `secrets.yml`. Check key names match exactly.                            |
| Merge conflicts                              | Run `/minecicd resolve merge-abort` to undo, or `/minecicd resolve repo-reset` to reset.                      |
| Plugin not loading after pull                | Check the Paper/Spigot console for errors. If using `experimental-jar-loading`, ensure PlugManX is installed. |
| Boss bar not showing                         | Check `bossbar.enabled` in config. Ensure your client supports Adventure boss bars (1.19.3+).                 |

---

## License

See the [LICENSE](LICENSE) file in the repository root.
