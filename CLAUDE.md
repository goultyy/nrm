# NRM — project brief for Claude Code

Read this fully before making changes. It's the plan for the whole app; you'll usually be
working on one numbered step at a time, but decisions in later steps depend on choices made here.

## What this is

A Windows desktop app (Java 17+, JavaFX 21, Maven) for managing multiple remote Nginx servers
over SSH, with a classic IIS Manager-style interface. No cloud component. All certificate work
happens on the server; private keys stay on it unless the user explicitly chooses to download one
(an opt-in per download, behind a warning; the key is never written to the command log, and the server first
checks that it belongs to the chosen certificate). CA keys are never downloaded.

## Current status

All nine steps are built and tested, and the user has run the app and reports it works (steps 1–8
confirmed; the packaged build's Cloudflare pages needed a missing Java module, fixed in 1.1.0 and
awaiting a rebuild to confirm). Features added since the plan are listed in the README checklist.
Step 9 is done except the `.msi`/`.exe` installers, which need the WiX Toolset 3.x (the app image
from `packaging\build-installer.ps1` is built and runs). Run `.\mvnw test` to confirm before
starting new work, and again before ending a session. Tests that need a real server are skipped
unless `NRM_TEST_HOST`/`NRM_TEST_USER`/`NRM_TEST_PASSWORD` are set (see the README).

## Non-negotiable decisions

These were chosen already; don't revisit them without asking.

- **SSH library: sshj.** Actively maintained, modern key types, solid SFTP support.
- **Root access:** `sudo -S`, password piped via stdin, never on the command line.
- **Host keys:** trust-on-first-connect, then pin the SHA-256 fingerprint on the profile
  (`ServerProfile.hostKeyFingerprint` already exists for this). If a pinned key ever changes,
  block the connection with a clear warning — never silently reconnect.
- **Let's Encrypt:** webroot challenge by default; the nginx plugin as an option. No DNS
  challenges in v1.
- **CAs:** root CAs, issuing server certs with SAN support. Intermediate CAs (a CA signed by
  another CA, nested to any depth) were added at the user's request; each CA stays in its own
  folder one level under the CA storage folder, and an intermediate also has `chain.crt` and
  `bundle.crt` next to it.
- **Every SSH/SFTP call goes through one logging hook.** No exceptions, no "quick" calls that
  bypass it. This is a security requirement, not a nicety: it's how the command log panel can
  promise nothing sent to the server is hidden.
- **Unrecognised nginx directives are kept as raw, opaque blocks** by the parser — never dropped,
  never guessed at. If the parser doesn't understand something, round-tripping it unchanged is
  correct behaviour, not a bug to "fix" by skipping it.
- **All SSH work runs off the JavaFX UI thread.** Every screen in steps 2 and 6 must stay
  responsive during a slow SSH call.

## Architecture (packages)

```
mt.su.nrm.app       JavaFX entry point (Launcher, NrmApp)
mt.su.nrm.model     ServerProfile, ServerPaths, enums, cached tool checks       [done]
mt.su.nrm.config    Encrypted profile store, key protectors, ProfileRepository [done]
mt.su.nrm.ssh       SSH/SFTP, the logging hook, requirement checks              [step 3]
mt.su.nrm.nginx     Config parser, generator, validator, layout detection      [step 5]
mt.su.nrm.ui        All screens and panels                                     [steps 2, 4, 6, 7, 8]
mt.su.nrm.util      Small shared helpers                                       [done, extend as needed]
```

Keep this separation. UI code should never talk to SSH directly — go through a service class in
`mt.su.nrm.ssh` — and nothing outside `mt.su.nrm.config` should touch the encrypted store format.

## Build order (do these in order; don't skip ahead)

1. ~~Maven skeleton, data model, encrypted profile store~~ — done
2. **Server selector**: list of saved profiles, Add/Edit/Delete, the create/edit dialog
   (including the collapsible Paths section), delete confirmation, inline validation messages
3. **SSH layer**: sshj wrapper, the single logging hook, host key pinning, requirement checks
   for openssl/certbot (cached on the profile) — then the **command log panel** (console-style,
   passwords masked, full output per line, backed by the logging hook)
4. **Main window shell**: menu bar, toolbar, tree view, tabbed property panel, Actions pane,
   status bar — wire the server selector into it
5. **Config parser, generator, validator**, including sites-available/sites-enabled vs conf.d
   layout detection — this needs the most thorough tests in the project, since it rewrites live
   server configs
6. **Virtual host editor** (General, Locations, SSL, Headers, Rewrites, Limits, Logging tabs),
   the **validate/apply pipeline** (stage locally → upload to temp file → `nginx -t` → diff →
   confirm → overwrite real config → reload), and the **pending changes panel**
7. **Location editor** (static/reverse proxy/redirect, auth, rate limiting, compression) and the
   remaining editors: load balancing (upstreams), cache zones, rate/connection limits, error
   pages, global settings (workers, compression defaults, shows detected layout)
8. **SSL manager**: certificate list with expiry warnings, Let's Encrypt issue/renew via
   certbot, self-signed CAs and certs issued from them
9. **Error handling pass, polish, Windows installer** (jpackage, `.msi`/`.exe`, bundled JRE)

## Working method

- **One step per session, or one sub-piece of a big step if it's cleaner.** Don't jump ahead to
  a later step even if it seems quick — later steps depend on interfaces settled in earlier ones.
- **Write tests as you go**, especially for `mt.su.nrm.nginx` (round-trip parser tests: parse a
  real-world config, regenerate it, byte-for-byte or semantically identical) and `mt.su.nrm.ssh`
  (the logging hook must never be bypassable). Run `.\mvnw test` before calling a step done.
- **After a UI step, tell me to run `.\mvnw javafx:run` and check it myself** before we move on.
  You can't see the rendered UI, so don't mark a screen "done" until I confirm it.
- **For steps 3, 5, and 6**, ask me to stand up a disposable Linux test box (WSL or Docker) with
  nginx installed before you start, so you can run real SSH commands and real `nginx -t` instead
  of guessing at output. Never point unfinished code at a real production server.
- **Bump the version with every change.** The version lives only in `pom.xml` (`<version>`); the About box, the jar
  name and the installer read it. Patch (1.1.x) for fixes and small changes, minor (1.x.0) for new features or screens,
  major only when I ask. Add a line to `CHANGELOG.md` for it, and say the new version in your closing report so I can
  tell builds apart.
- **Keep the README's checklist current.** Tick off each step there as you finish it.
- **Commit on every version bump, however minor**, once `.\mvnw test` passes, with the version in the message
  (for example `1.1.2: fix the module list`) and the usual Co-Authored-By line. Commit only; **never push**. I push
  myself. The report should say the commit hash and that it is not pushed. Run git by its full path
  (`C:\Program Files\Git\cmd\git.exe`) if it isn't on PATH, and write commit messages from a file without a BOM
  (PowerShell 5.1's `-Encoding utf8` and piped here-strings add one).
- **If something in this brief conflicts with what turns out to be sensible**, stop and ask
  rather than silently deciding — especially anything touching sudo, host key handling, or the
  encrypted store format.

## Known constraints from step 1

- Profiles are stored as flat key/value maps (see `ProfileCodec`) specifically so new fields can
  be added later without a migration step. Add fields there, don't redesign the format.
- `ServerProfile.copy()` must stay a deep copy — the UI relies on editing copies safely before
  calling `ProfileRepository.update()`.
- `ServerProfile.toString()` must never leak secrets. Keep this invariant for any new class that
  might end up in a log line (SSH sessions, command results, etc.).
