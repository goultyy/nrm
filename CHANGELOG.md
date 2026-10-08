# Changelog

Every change bumps the version in `pom.xml` (the only place it is set; the About box, the jar name and the installer
all read it) and adds a line here, newest first. Patch (1.1.x) for fixes and small changes, minor (1.x.0) for new
features or screens, major (x.0.0) only when asked.

## 1.4.3

- Fix: Change History stayed empty until Refresh was pressed. The panel decided only once, when it was created, whether
  the server was reachable. It now follows the connection: it loads when it is shown, when the connection comes up, and
  when the configuration is reloaded (which is what an apply does), empties itself on disconnect, and re-reads once more
  if news arrives while a read is in flight.

## 1.4.2

- Docs: `CLAUDE.md` now allows `git push` when needed (after a passing build, to `origin` only, never forced).

## 1.4.1

- Fix: importing a certificate kept saying "Choose the private key file" after the key had been chosen, until you ticked
  "Replace an imported certificate". The shared form dialog only re-checked itself for text boxes, checkboxes and
  drop-downs placed directly in it, so a file field (a text box beside a Browse button) never triggered a re-check. It
  now looks inside rows and groups, which also fixes any other form with a field in a row.

## 1.4.0

- Download a server certificate's **private key**, as an explicit choice in the Download list (set apart, in red, never the
  default). A warning comes first: anyone with the file can pose as the site, a leak means replacing the certificate and key,
  where not to keep it, and that a new key on the other machine is often safer. You must tick "I understand" and confirm the
  key file (taken from the sites using the certificate, else the usual name). The server checks the key belongs to the
  certificate and refuses a missing, encrypted or mismatching key. Server certificates only; CA keys are never downloaded.
- The key is read through a new logged command mode that withholds the output from the command log (the log records that a key
  was downloaded and which file), and is saved to a file that only your Windows account can open.
- `CLAUDE.md`'s rule is updated: keys stay on the server unless the user explicitly downloads one.

## 1.3.1

- Fix: downloading a server certificate showed only the Windows `.p7b` option; the plain certificate and chain were
  hidden in the save dialog's "Save as type" drop-down (three of its entries shared `*.crt`). "Download" now first asks
  what to save, in a list with an explanation each: Windows install file, certificate and chain in one PEM file,
  certificate only, or chain only. The save dialog then offers exactly that file type. The file contents are now built in
  one tested place. Private keys are still never downloaded.

## 1.3.0

- New optional add-on (View > Features): IP addresses. Lists every address on the server with the ports nginx listens on
  now (from the server), the sites whose `listen` covers it (from the loaded configuration), and warnings where the two
  disagree. "Find external addresses" looks up the public address behind each private one: first the cloud's metadata
  (AWS, Google Cloud, Azure; Oracle names only the private address), then a "what is my IP" service asked from the server
  through that address. The services are a list you can edit ("Services used", default api.ipify.org then icanhazip.com),
  you are asked before the first lookup, and the requests show in the command log. "Check from this computer" tries a
  plain TCP connection to the external address. View only; nothing is stored on the server profile.

## 1.2.0

- Choose a folder or file on the server from a dialog instead of typing the path. A "Choose folder" button sits beside
  a virtual host's root, a location's root and alias, and a cache zone's folder; "Choose file" beside the SSL
  certificate and key, the password file and an access log. The dialog lists only the server, opens near what the
  field holds (or the nearest folder above it that exists), can create a new folder, and has a "Show files" box.

## 1.1.2

- Docs: `CLAUDE.md` now says every version bump is committed locally and that pushing is left to the owner.

## 1.1.1

- Docs: the README checklist now shows steps 1 to 8 done, step 9 done except the WiX installers, and lists the
  features added since the plan. The status note in `CLAUDE.md` matches.

## 1.1.0

- Fix: the packaged app's Cloudflare pages failed with "java/net/http/HttpClient doesn't exist". The bundled Java
  runtime was missing the `java.net.http` module. The installer script now also checks its module list against the
  built jars with `jdeps` and stops if one is missing.
- Real visitor IP is now set per site (Real IP part of a virtual host) instead of one global add-on page.
- Change History: every apply is recorded, and an earlier version can be restored through the normal test, diff and
  confirm flow.
- Security headers presets (Basic, Recommended, Strict) on a site's Headers tab.
- Log analysis on the Logs page; custom log formats with an interactive builder.
- Cloudflare integration, dark mode, optional add-ons (Status page), remote "New file", and the earlier polish
  listed in the README.

## 1.0.0

- First complete build: steps 1 to 9 of the plan.
