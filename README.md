<div align="center">
<img src="bugnotes-icon.svg" width="64" height="64" alt="BugNotes icon">

# BugNotes

**A Markdown notebook built directly into Burp Suite, for people who test in Burp and write in Markdown.**

<br>

![Burp Suite](https://img.shields.io/badge/Burp_Suite-Extension-FF6633?style=for-the-badge&logo=burpsuite&logoColor=white)
![Java](https://img.shields.io/badge/Java-17-007396?style=for-the-badge&logo=openjdk&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-4E342E?style=for-the-badge)
![Offline](https://img.shields.io/badge/Offline_First-2E7D32?style=flat-square)
![Storage](https://img.shields.io/badge/Storage-Project_Scoped-1565C0?style=flat-square)
![Zero Telemetry](https://img.shields.io/badge/Telemetry-None-6A1B9A?style=flat-square)

</div>

<br>

## <img src="https://raw.githubusercontent.com/feathericons/feather/master/icons/info.svg" width="22" valign="middle"> Overview

Security testing usually means juggling two windows: Burp Suite for the actual work, and a separate notes app on the side for writing down what you find. BugNotes removes that second window. It is a full Markdown notebook that lives inside Burp Suite itself, so payloads, findings, and draft report text stay in the same place you are already working.

It is built for bug bounty hunters, penetration testers, and anyone who spends long sessions inside Burp and wants a fast, clean place to keep notes without leaving the tool.

---

## <img src="https://raw.githubusercontent.com/feathericons/feather/master/icons/shield.svg" width="22" valign="middle"> Why it matters

<table>
<tr>
<td width="60" align="center"><img src="https://raw.githubusercontent.com/feathericons/feather/master/icons/wifi-off.svg" width="26"></td>
<td><b>Fully offline.</b> BugNotes never makes a network call. It behaves exactly the same on an isolated, air gapped machine as it does anywhere else.</td>
</tr>
<tr>
<td align="center"><img src="https://raw.githubusercontent.com/feathericons/feather/master/icons/folder.svg" width="26"></td>
<td><b>Stored with your project.</b> Notes are saved inside your active Burp project file, not in a separate database and not on any server.</td>
</tr>
<tr>
<td align="center"><img src="https://raw.githubusercontent.com/feathericons/feather/master/icons/eye-off.svg" width="26"></td>
<td><b>No tracking.</b> No analytics, no usage data, nothing sent anywhere unless you choose to export a file yourself.</td>
</tr>
<tr>
<td align="center"><img src="https://raw.githubusercontent.com/feathericons/feather/master/icons/layers.svg" width="26"></td>
<td><b>Client work stays separated.</b> Because notes travel with the project file, notes from one engagement never mix with another.</td>
</tr>
</table>

---

## <img src="https://raw.githubusercontent.com/feathericons/feather/master/icons/list.svg" width="22" valign="middle"> Features

<table>
<tr valign="top">
<td width="50%">
<img src="https://raw.githubusercontent.com/feathericons/feather/master/icons/bold.svg" width="22"><br>
<b>Markdown formatting toolbar</b><br>
<sub>Bold, italic, headings, quotes, links, and code blocks, applied instantly to whatever text you have selected.</sub>
</td>
<td width="50%">
<img src="https://raw.githubusercontent.com/feathericons/feather/master/icons/type.svg" width="22"><br>
<b>Font family switcher</b><br>
<sub>Pick any font installed on your system. Monospaced fonts are shown first, since they are usually best for reading payloads.</sub>
</td>
</tr>
<tr valign="top">
<td>
<img src="https://raw.githubusercontent.com/feathericons/feather/master/icons/zoom-in.svg" width="22"><br>
<b>Zoom controls</b><br>
<sub>Increase or decrease text size at any time, useful for long sessions or reading small print comfortably.</sub>
</td>
<td>
<img src="https://raw.githubusercontent.com/feathericons/feather/master/icons/search.svg" width="22"><br>
<b>Built in search</b><br>
<sub>A find bar highlights every match inside the current note and shows a live count of results.</sub>
</td>
</tr>
<tr valign="top">
<td>
<img src="https://raw.githubusercontent.com/feathericons/feather/master/icons/corner-down-right.svg" width="22"><br>
<b>Send selection to notes</b><br>
<sub>Right click any highlighted text in a request or response and send it straight into your active note.</sub>
</td>
<td>
<img src="https://raw.githubusercontent.com/feathericons/feather/master/icons/code.svg" width="22"><br>
<b>Send selection to Decoder</b><br>
<sub>Send highlighted note text directly to Burp's Decoder tool without copying and pasting.</sub>
</td>
</tr>
<tr valign="top">
<td>
<img src="https://raw.githubusercontent.com/feathericons/feather/master/icons/save.svg" width="22"><br>
<b>Autosave</b><br>
<sub>Notes save automatically as you type. Switching notes, closing the tab, or unloading the extension never loses your work.</sub>
</td>
<td>
<img src="https://raw.githubusercontent.com/feathericons/feather/master/icons/hash.svg" width="22"><br>
<b>Line numbers</b><br>
<sub>A line number gutter makes it easy to reference a specific point in long pasted logs or payloads.</sub>
</td>
</tr>
<tr valign="top">
<td>
<img src="https://raw.githubusercontent.com/feathericons/feather/master/icons/sun.svg" width="22"><br>
<b>Active line highlight</b><br>
<sub>The line your cursor is on is gently highlighted, so your position stays visible in dense text.</sub>
</td>
<td>
<img src="https://raw.githubusercontent.com/feathericons/feather/master/icons/moon.svg" width="22"><br>
<b>Matches your Burp theme</b><br>
<sub>Follows Burp's light and dark mode automatically. There is no separate theme setting to manage.</sub>
</td>
</tr>
<tr valign="top">
<td>
<img src="https://raw.githubusercontent.com/feathericons/feather/master/icons/upload.svg" width="22"><br>
<b>Import and export</b><br>
<sub>Bring in existing Markdown files or export any note as a clean, portable file whenever you need it outside Burp.</sub>
</td>
<td>
<img src="https://raw.githubusercontent.com/feathericons/feather/master/icons/sidebar.svg" width="22"><br>
<b>Multiple notes, one place</b><br>
<sub>Keep separate notes for different targets or stages of testing, organized in a simple side list.</sub>
</td>
</tr>
</table>

---

## <img src="https://raw.githubusercontent.com/feathericons/feather/master/icons/zap.svg" width="22" valign="middle"> Built for long sessions

BugNotes stays responsive even when you paste in very large captured requests or long recon logs. Heavy work such as loading and saving files runs quietly in the background, so Burp's interface never freezes while BugNotes is busy. When the extension is removed or reloaded, it cleans up fully after itself and leaves nothing behind.

---

## <img src="https://raw.githubusercontent.com/feathericons/feather/master/icons/download-cloud.svg" width="22" valign="middle"> Installation

### What you need

<table>
<tr>
<td width="30%"><b>Java</b></td>
<td>Version 17 or newer</td>
</tr>
<tr>
<td><b>Gradle</b></td>
<td>Used once to build the extension file</td>
</tr>
<tr>
<td><b>Burp Suite</b></td>
<td>Community or Professional, any recent version</td>
</tr>
</table>

### Steps

<table>
<tr valign="top">
<td width="40" align="center"><img src="https://raw.githubusercontent.com/feathericons/feather/master/icons/git-branch.svg" width="24"></td>
<td>

**Download the source**
```bash
git clone https://github.com/unrealsrabon/bugnotes-burp_edition.git
cd bugnotes-burp_edition
```
</td>
</tr>
<tr valign="top">
<td align="center"><img src="https://raw.githubusercontent.com/feathericons/feather/master/icons/tool.svg" width="24"></td>
<td>

**Build the extension**
```bash
gradle clean jar
```
This produces the extension file at `build/libs/BugNotes.jar`.
</td>
</tr>
<tr valign="top">
<td align="center"><img src="https://raw.githubusercontent.com/feathericons/feather/master/icons/plus-square.svg" width="24"></td>
<td>

**Load it into Burp Suite**

Open Burp Suite, go to **Extensions**, then **Installed**, then **Add**. Set the extension type to **Java** and select the `BugNotes.jar` file you built.
</td>
</tr>
<tr valign="top">
<td align="center"><img src="https://raw.githubusercontent.com/feathericons/feather/master/icons/check.svg" width="24"></td>
<td>

**Start writing**

A **BugNotes** tab appears in Burp's main toolbar. Highlight text anywhere in a request or response, right click, and choose **Send selection to BugNotes** to begin.
</td>
</tr>
</table>

---

## <img src="https://raw.githubusercontent.com/feathericons/feather/master/icons/help-circle.svg" width="22" valign="middle"> Frequently asked

<table>
<tr valign="top">
<td width="35%"><b>Where are my notes actually saved</b></td>
<td>Inside your Burp project file. If you use a temporary project, notes are discarded along with it, which is intentional and keeps engagements separate.</td>
</tr>
<tr valign="top">
<td><b>Does BugNotes send anything over the network</b></td>
<td>No. It makes no network calls at all, so it works the same on a closed or air gapped network.</td>
</tr>
<tr valign="top">
<td><b>Can I keep a note outside of Burp</b></td>
<td>Yes. Export any note as a plain Markdown file at any time, and import it back later or into a different project.</td>
</tr>
</table>

---

## <img src="https://raw.githubusercontent.com/feathericons/feather/master/icons/file-text.svg" width="22" valign="middle"> License

BugNotes is released under the MIT License.

```
MIT License

Copyright (c) 2026 unrealsrabon

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

<div align="center">
<sub><b>BugNotes.</b> Created by Shakil Ahmed Srabon</sub>
</div>
