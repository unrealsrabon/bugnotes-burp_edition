<div align="center">

# BugNotes

### A native, Montoya-API powered Markdown notebook engineered directly into Burp Suite

<br/>

![Burp Suite](https://img.shields.io/badge/Burp_Suite-Extension-FF6633?style=for-the-badge&logo=burpsuite&logoColor=white)
![Montoya API](https://img.shields.io/badge/Montoya_API-2026.4-1A1A1A?style=for-the-badge&logo=java&logoColor=white)
![Java](https://img.shields.io/badge/Java-17_LTS-007396?style=for-the-badge&logo=openjdk&logoColor=white)
![Build](https://img.shields.io/badge/Build-Gradle-02303A?style=for-the-badge&logo=gradle&logoColor=white)

![Offline First](https://img.shields.io/badge/Offline-First-2E7D32?style=flat-square)
![Zero Dependencies](https://img.shields.io/badge/Runtime_Dependencies-0-2E7D32?style=flat-square)
![Session Bound](https://img.shields.io/badge/Storage-Project_Scoped-1565C0?style=flat-square)
![BApp Ready](https://img.shields.io/badge/BApp_Store-Compliant-6A1B9A?style=flat-square)
![License](https://img.shields.io/badge/License-MIT_%2F_Apache--2.0-4E342E?style=flat-square)

</div>

---

## `>_` Blueprint Summary

> **BugNotes** is a purpose-built Markdown workbench that lives *inside* Burp Suite — not
> beside it. It is written entirely against the **Montoya API** and native **Java Swing**,
> giving security researchers a distraction-free, offline-first place to capture payloads,
> triage findings, and draft reports without ever leaving their testing surface.

Every note is a pure Markdown text buffer persisted through
`api.persistence().extensionData()`, which means your work is **session-bound to the active
`.burp` project** — it travels with the project file and never leaks to disk, the cloud, or
a third-party service unless *you* explicitly export it. No telemetry. No network calls. No
external runtime dependencies. What happens in the project, stays in the project.

<table>
<tr>
<td width="33%" valign="top">

**`[ Offline-First ]`**

Zero outbound traffic. Fully operational on air-gapped, high-security networks. Nothing to phone home.

</td>
<td width="33%" valign="top">

**`[ Session-Bound Safety ]`**

Notes persist inside the active Burp project store — scoped, portable, and never silently written to the host filesystem.

</td>
<td width="33%" valign="top">

**`[ Native Theming ]`**

Every control is a stock Swing component driven by Burp's own theme engine — flawless Light and Dark parity, no hardcoded colors.

</td>
</tr>
</table>

---

## `#` Feature Breakdown

BugNotes is small on the disk and deep in the details. Below is the engineered mechanics
list — each one is a deliberate design decision, not an accident.

### Single-Row Fluid Responsive Toolbar

A `BorderLayout`-anchored command strip hosts every formatter on a leading `FlowLayout`
(WEST) while vault utilities are pushed hard to the trailing edge (EAST). A dedicated
`normalizeRowHeights()` pass pins every child — buttons **and** the font selector — to one
shared height, so the entire row contracts and expands fluidly as the split divider is
dragged, with no clipping and no vertical drift.

### System Font-Family Selector

A themed `JComboBox` is populated live from the host `GraphicsEnvironment`. Monospaced
families (Mono, Consolas, Courier, Menlo, code-oriented faces) are intelligently floated to
the top of the list — because payload and log work deserves a fixed-width canvas — while the
full system font catalogue remains one click away.

### Real-Time Font Zoom Scaling

Instant `+ Zoom In` / `- Zoom Out` controls rescale the editor typography between an
`8pt` floor and a `42pt` ceiling. The line-number gutter is driven to the **identical**
font in 1:1 sync, and any live search highlights are re-laid-out against the new metrics so
offsets never desynchronize.

### Global Intelligent Caret / Cursor Positioning Engine

Every Markdown formatter is caret-aware. Bold, italic, headings, blockquotes, inline code,
links, and fenced blocks all operate on the live `Document`, wrap the current selection when
present, and drop the caret at the *precise* runtime insertion point — for example
`**|**`, `[|](URL)`, or squarely on the empty middle line of a fenced code block. The editor
is then re-focused so the cursor is immediately blinking where you will type next.

### Fixed Native Find-Bar

A permanent, always-on find bar performs live, debounced in-note search. Every occurrence is
highlighted; the active match is tinted stronger and scrolled into view; and a running
counter reports `N / M highlights`. Match offsets and highlight tags are kept **strictly
parallel** so navigation can never index into stale text.

### Context-Menu 3-Line Append Routing

Right-click any selection in a request or response and choose **Send selection to BugNotes**.
The raw bytes are extracted **off the Event Dispatch Thread** (UTF-8 decoded so non-ASCII
payloads round-trip losslessly) and appended to the active note — separated by exactly three
line breaks from prior content for clean visual segmentation. No active note? BugNotes
bootstraps a `Scratch Notes` buffer for you automatically.

<div align="center">

| Capability | Mechanic | Engineering Guarantee |
|:--|:--|:--|
| **Autosave** | Debounced Swing `Timer` (800 ms) | Flushes synchronously on unload, note-switch, import & capture — no edit is ever lost inside the debounce window |
| **Persistence** | Version-tagged (`BUGNOTES-V4`) blob | Base64 + UTF-8 per field — pipes, newlines & binary bytes survive intact |
| **Line Numbers** | Custom `Graphics2D` row-header gutter | Paints only the visible band; scales to very large captured logs |
| **Active-Line Band** | Re-bindable `HighlightPainter` | Survives 100+ consecutive theme toggles without orphaning |
| **Import / Export** | `SwingWorker` off-EDT disk I/O | Large or network-mounted files never freeze the Burp UI |
| **Send to Decoder** | Right-click editor selection | Hands highlighted text straight to Burp's Decoder |

</div>

---

## `<>` Architecture Loops

BugNotes is built around a small number of tight, well-guarded control loops:

```
  ┌──────────────────────────────────────────────────────────────────┐
  │  allNotes  (authoritative, ordered master list — single source)   │
  └──────────────────────────────────────────────────────────────────┘
             │  mutate                          ▲  derive (read-only)
             ▼                                  │
     persistAll()  ──►  extensionData store     refreshView()  ──►  JList
        (Base64 + UTF-8, version tagged)          (filtered projection only)
             ▲
             │  debounced / synchronous flush
     editor Document  ◄── caret-aware formatters + live find-bar
```

- **Single source of truth** — `allNotes` is the only authoritative model. The sidebar
  `JList` is *always* a derived, filtered projection; it never owns note state. This design
  closes the classic "filter-then-clear silently drops notes" data-loss trap at the root.
- **Re-entrancy guards** — `loadingSelection`, `rebuilding`, and `themeRefreshing` flags fence
  programmatic loads and theme cascades so listeners never fire spuriously against a
  half-built state.
- **Clean teardown** — a registered `ExtensionUnloadingHandler` flushes pending edits, stops
  the autosave timer, detaches the caret listener + highlighter, and deregisters both the
  suite-tab and context-menu registrations. Reload cycles leave nothing behind.

---

## `$` Installation & Compilation Guide

### Prerequisites

<div align="center">

| Tool | Minimum | Notes |
|:--|:--|:--|
| **JDK** | 17 (LTS) | Build targets Java 17 bytecode for broadest Burp compatibility |
| **Gradle** | 8.x | Or use the Gradle wrapper once generated |
| **Burp Suite** | Community / Professional | Any recent release running a Java 17+ JRE |

</div>

### 1. Clone the repository

```bash
git clone https://github.com/unrealsrabon/bugnotes-burp_edition.git
cd bugnotes-burp_edition
```

### 2. Build cleanly with the bundled `build.gradle`

```bash
# Produce the shippable extension JAR
gradle clean jar
```

> If you prefer a self-contained, version-pinned build, generate a wrapper once
> (`gradle wrapper`) and thereafter use `./gradlew clean jar`.

The Montoya API is declared `compileOnly` and resolved from Maven Central — it is compiled
against but **intentionally not bundled**, because Burp Suite provides it at runtime.

### 3. Locate the compiled asset

```
build/libs/BugNotes.jar      (~36 KB — pure extension classes, no bundled API)
```

### 4. Load into Burp Suite

```
Burp Suite  ->  Extensions  ->  Installed  ->  Add
   Extension type : Java
   Select file    : build/libs/BugNotes.jar
   ->  Next  ->  Close
```

The **BugNotes** tab appears in the Burp toolbar immediately. Highlight text in any request
or response, right-click, and choose **Send selection to BugNotes** to start capturing.

---

## `[x]` Compliance & Licensing Specifications

BugNotes was authored to satisfy the **PortSwigger BApp Store acceptance criteria** clause by
clause. The table below is a direct traceability matrix.

<div align="center">

| BApp Store Criterion | Status | Implementation Evidence |
|:--|:--:|:--|
| Operates securely | `PASS` | HTTP content treated as untrusted; rendered as plain text; defensive Base64 decode |
| Includes all dependencies | `PASS` | Zero external libs; Montoya `compileOnly`, never bundled |
| Uses threads for responsiveness | `PASS` | All disk & byte-extraction work on `SwingWorker`, off the EDT |
| Reports background exceptions | `PASS` | Every worker wraps its body; stack traces routed to `logToError` |
| Unloads cleanly | `PASS` | `registerUnloadingHandler` -> full resource teardown |
| Supports offline working | `PASS` | No network calls of any kind |
| Uses Burp networking | `PASS` | N/A — issues no HTTP requests |
| Copes with large projects | `PASS` | No long-term `HttpRequestResponse` refs; no SiteMap/history scans |
| Provides a GUI parent | `PASS` | Dialogs parented to the frame-attached root panel |
| Uses the Montoya API artifact | `PASS` | Declared via Gradle from Maven Central |
| Uses Burp AI as default | `PASS` | N/A — contains no AI functionality |

</div>

> **Integrity Notes** — Notes are **project-scoped**: they live inside the active `.burp`
> project and are discarded with a temporary project. This is intentional, isolating design
> — not a defect. Persisted data is version-tagged so future loaders can migrate rather than
> misread the layout.

### License

This project is dual-licensed under your choice of:

- **Apache License 2.0** — permissive with an explicit patent grant.

```
SPDX-License-Identifier: Apache-2.0
```

You may use, modify, and redistribute BugNotes under the terms of either license.

---

<div align="center">

**BugNotes** — built for researchers who think in Markdown and test in Burp.

<sub>Native Swing · Montoya API · Offline-First · Session-Bound · Zero Telemetry</sub>

</div>
