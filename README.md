# FidDb Manager

A Ghidra extension for managing **Function ID databases (FidDbs)** from one place: see every
database Ghidra knows about, turn them on or off, install them into Ghidra's FunctionID data
folder, and add new ones from a file, a folder, or a URL.

Built for **Ghidra 12.1.4** (Java 21).

## Features

Adds a **Tools → FidDb Manager** menu to the CodeBrowser:

| Menu item | What it does |
|---|---|
| **Manage FidDbs…** | Table of every installed (`.fidbf`) and attached (`.fidb`) database |
| **Add FidDb from File…** | Add one or more `.fidb` / `.fidbf` files |
| **Add FidDbs from Directory…** | Recursively add every FidDb under a folder |
| **Add FidDb from URL…** | Download a `.fidb`, `.fidbf`, or a `.zip` of them and add it |

In the manager table:

- **Enabled** — include or exclude the database from Function ID analysis. This uses Ghidra's own
  active/inactive setting, so it persists across restarts.
- **Installed** — checking it writes a raw `.fidbf` copy into
  `<ghidra>/Ghidra/Features/FunctionID/data` if it isn't already there, and detaches the packed
  original (the original file is left untouched). Unchecking moves the `.fidbf` to a backup folder
  in your Ghidra user settings directory — it is never deleted.
- **Remove**, **Enable All**, **Disable All**, **Refresh**.
- An **Install added databases** checkbox installs everything you add in one step.

GitHub `/blob/` links are converted to raw downloads automatically. Downloads are kept in
`<user settings>/FidDbManager/downloads`.

## Install

1. Build the extension (below), or download the zip from the Releases page if one is published.
2. In Ghidra: **File → Install Extensions → +**, select the zip, and restart Ghidra.
3. In the CodeBrowser, open **Tools → FidDb Manager**. If the menu is missing, enable
   **FidDbManagerPlugin** under **File → Configure → Miscellaneous**.

To update, uncheck FidDbManager in **Install Extensions**, restart, then install the new zip.

## Build

Requires Gradle and a JDK 21+.

```
gradle -PGHIDRA_INSTALL_DIR=<path to ghidra_12.1.4_PUBLIC>
```

or set the `GHIDRA_INSTALL_DIR` environment variable. The installable zip is written to `dist/`.

## Notes

- Raw `.fidbf` databases can only be used from a module's data folder, so adding one always
  installs it.
- Installing requires write access to the Ghidra installation folder.
- Ghidra only scans for installed `.fidbf` files at startup. To make a newly installed database
  usable immediately, the extension registers it with Ghidra's internal FidDb list via
  reflection. If a future Ghidra version changes those internals, the extension falls back to
  asking you to restart.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
