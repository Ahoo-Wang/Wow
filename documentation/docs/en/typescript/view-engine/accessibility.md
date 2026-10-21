---
title: Accessibility
description: How the view engine's interface meets WCAG 2.2 AA — how it was evaluated, how keyboard and screen-reader users work through each surface, what the host owes, and the known gaps.
---

# Accessibility

This page is the accessibility conformance statement of the view engine's interface — the record and analysis workbenches, boards and both embeds, the `/ui` entry — against [WCAG 2.2](https://www.w3.org/TR/WCAG22/) levels A and AA:

| Supports | Partially supports | Does not support | Not applicable |
| ---: | ---: | ---: | ---: |
| 41 | 0 | 0 | 14 |

It is a self-evaluation, not a third-party audit, and screen-reader software has been used only for a spot check: see [How it was evaluated](#how-it-was-evaluated) and [Known gaps](#known-gaps).

## What the host owns

The engine always sits in a host's page, and some criteria are the host's:

- the page title (2.4.2), the page language (3.1.1), a skip-to-content link, site navigation (2.4.5), sign-in (3.3.8) and any help mechanism (3.2.6);
- the contrast of every colour the host overrides ([Theming](./theming#contrast-is-the-overrider-s-responsibility));
- one `main` landmark: a page that has its own passes `landmark="region"` to the workbench.

Most "not applicable" rows below are for this reason.

## How it was evaluated

- **Keyboard only**: every core task was walked with real key presses — Tab, Shift+Tab, the arrows, Enter, Space, Esc and modifier combinations — in Chromium and WebKit, at desktop width and down to 320 pixels. After each press the walk recorded which element held focus, whether a focus indicator was drawn and fully visible, and what the live regions said.
- **Screen readers**: the names, roles, states and structure were read from the browser's own accessibility tree and from ARIA snapshots, and the live regions after every press. They show what should be spoken, not how a screen reader speaks it. A maintainer spot-checked two scenes with VoiceOver by hand, and both passed.
- **Automated checks**: every Storybook story runs axe after its interactions, and a violation fails it; unit tests carry their own axe assertions; the built-in themes' contrast is held by the contrast-matrix tests, in both modes, on every preset.
- **Forced colours, reduced motion and text spacing**: menus, selection, the current view and focus were walked under forced colours; reduced motion holds the animations; WCAG's text spacing cuts no text off.

## Working through each surface

**Record workbench.** The view list is one Tab stop per view. The header row is one Tab stop: ←/→ walk the columns, Enter sorts, Shift+Enter adds a sort key, Alt+←/→ changes a column's width, which column settings also take as a number. The rows are one Tab stop: ↑/↓ walk them, Enter opens a record. Paging is a named navigation, and the result's total is announced after every query.

**Analysis.** Opening the analysis moves focus into it; dimensions and metrics are added from menus, and each change announces the number of groups. Chart types are a radio group. Chart marks are pointer-only, so the table layout is the keyboard's way to the same follow-ups: a row's Enter is its group, Shift+Enter a range. Every chart is a named image with a one-sentence summary and a screen-reader-only data table.

**Boards.** Enter on a panel's grip picks it up; the arrows move it, Shift+arrows resize it, Enter drops it and Esc puts it back. A panel's menu also moves and resizes it a step at a click, for a pointer that cannot drag. After a filter changes, the board says how many panels updated and how many failed.

**Actions.** A disabled action stays focusable and gives its reason on focus. A form's required fields are announced, and submitting with one blank moves focus to it. The selection bar is one Tab stop with the arrows through its actions. A run's start and result are announced in one sentence with the refreshed count.

**Embeds.** A static embed offers only reading controls; an interactive one the same names and announcements as the workbench. Two embeds' landmarks are named after their own views.

**Across the board.**

- Focus is always visible: one indicator for controls, a mark inside the item a menu has under the keyboard, all at 3:1 or more against what surrounds them.
- No traps: modal dialogs keep focus, and Esc closes them and hands focus back to what opened them.
- No single-character shortcuts; the keys of grips, rows and handles are in their names or descriptions.
- The surface writes its wording's language as `lang`, on itself and on every popup, so Chinese wording on an English page is read as Chinese.
- Under reduced motion, animations stop; charts follow the setting too.

## Known gaps

- **A full human screen-reader pass** with VoiceOver and Safari, and NVDA with Firefox or Chrome, over the core tasks has not been run beyond the spot check. A step-by-step checklist for it is in the package's design documents: [screen-reader walkthrough](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/docs/design/screen-reader-walkthrough.md).
- **Cut text** opens whole in a tooltip on hover, but not on keyboard focus; a screen reader reads the whole text from the accessible name.

## Criterion by criterion

"Supports": met. "Not applicable": the engine has no such content, or the matter is the host's. WCAG 2.2 removed 4.1.1 Parsing.

### 1. Perceivable

| Criterion | Level | Result | Notes |
|---|---|---|---|
| 1.1.1 Non-text Content | A | Supports | Icon-only buttons are named (one string for the name and the tooltip); charts are named `role=img` with a summary sentence and a data table; an image in a panel without `alt` takes the panel's title |
| 1.2.1 Audio-only and Video-only (Prerecorded) | A | Not applicable | No audio or video |
| 1.2.2 Captions (Prerecorded) | A | Not applicable | As above |
| 1.2.3 Audio Description or Media Alternative (Prerecorded) | A | Not applicable | As above |
| 1.2.4 Captions (Live) | AA | Not applicable | As above |
| 1.2.5 Audio Description (Prerecorded) | AA | Not applicable | As above |
| 1.3.1 Info and Relationships | A | Supports | Tables use `th` and `rowheader`, headers named by their column, chart data tables have a `caption`; form controls are labelled; landmarks and two heading levels; groups are named `group`s; a declared action form's required field carries `aria-required`, with "Required" as its description |
| 1.3.2 Meaningful Sequence | A | Supports | DOM order is reading order |
| 1.3.3 Sensory Characteristics | A | Supports | Instructions do not rely on shape, position or colour |
| 1.3.4 Orientation | AA | Supports | No orientation lock |
| 1.3.5 Identify Input Purpose | AA | Not applicable | No inputs collect information about the user |
| 1.4.1 Use of Color | A | Supports | Status badges carry words; rises and falls carry a sign; series have legend text and a data table, and patterns can be pinned on |
| 1.4.2 Audio Control | A | Not applicable | No audio |
| 1.4.3 Contrast (Minimum) | AA | Supports | Every built-in preset in both modes passes the contrast-matrix tests; colours a host overrides are the host's |
| 1.4.4 Resize Text | AA | Supports | No page-level horizontal scrolling at 320px wide (400% zoom at 1280px) |
| 1.4.5 Images of Text | AA | Supports | Text in charts is SVG text; exported images are downloaded files |
| 1.4.10 Reflow | AA | Supports | No horizontal page scrolling at 320px; data tables and charts scroll inside their own frames (the two-dimensional exception) |
| 1.4.11 Non-text Contrast | AA | Supports | The focus ring, row and card focus, the mark on the menu item under the keyboard, borders and rules are ≥3:1 under the built-in presets (held by the contrast matrix and browser stories) |
| 1.4.12 Text Spacing | AA | Supports | With WCAG's line, letter, word and paragraph spacing injected, no text is cut off; glyphs in the 20px badges stay whole |
| 1.4.13 Content on Hover or Focus | AA | Supports | Tooltips open on focus too, close with Esc, can be hovered and do not vanish on their own; so does the whole-text tooltip on cut text |

### 2. Operable

| Criterion | Level | Result | Notes |
|---|---|---|---|
| 2.1.1 Keyboard | A | Supports | Every core task can be done with the keyboard alone; chart marks are pointer-only, and the same operations are keyboard-operable in the table layout and on the filter bar |
| 2.1.2 No Keyboard Trap | A | Supports | Only modal dialogs keep focus, and Esc leaves them |
| 2.1.4 Character Key Shortcuts | A | Supports | No single-character shortcuts; every key acts on the focused control only |
| 2.2.1 Timing Adjustable | A | Not applicable | No time limits |
| 2.2.2 Pause, Stop, Hide | A | Supports | Auto refresh is switched on by the user and can be switched off at any time |
| 2.3.1 Three Flashes or Below Threshold | A | Supports | Nothing flashes |
| 2.4.1 Bypass Blocks | A | Supports | Named landmarks and headings; a skip-to-content link is the host's |
| 2.4.2 Page Titled | A | Not applicable | The page title is the host's |
| 2.4.3 Focus Order | A | Supports | Focus never falls to the top of the page when a control goes away; a handed-over view, a newly added panel and a one-day date put focus somewhere that makes sense; Esc after adding a filter condition lands on its value box |
| 2.4.4 Link Purpose (In Context) | A | Supports | Links in panels are named by their text and say they open in a new tab |
| 2.4.5 Multiple Ways | AA | Not applicable | The View Engine is not a set of pages; site navigation is the host's |
| 2.4.6 Headings and Labels | AA | Supports | View, panel and region names say what they are; column headers are named by their column; a manager row's button group is named by its view |
| 2.4.7 Focus Visible | AA | Supports | One focus indicator for controls and a `--ring` mark inside menu items, ≥3:1 in both themes; under forced colours focus, menu items and selection are all framed |
| 2.4.11 Focus Not Obscured (Minimum) | AA | Supports | Sticky table headers and footers do not hide focus; a column narrower than its content clips it, and a copy button stays whole inside its own cell |
| 2.5.1 Pointer Gestures | A | Supports | No multipoint or path gesture is the only way: a range can be picked with two taps or the keyboard |
| 2.5.2 Pointer Cancellation | A | Supports | Buttons act on release; dropping a drag where it started cancels it |
| 2.5.3 Label in Name | A | Supports | Accessible names contain the visible words ("Order" → "Sort by Order, ascending") |
| 2.5.4 Motion Actuation | A | Not applicable | Nothing is triggered by device motion |
| 2.5.7 Dragging Movements | AA | Supports | Every ordered list's handle opens a menu of places on a click; a panel's ⋯ moves and resizes it one click a step; a column's width is typed in column settings |
| 2.5.8 Target Size (Minimum) | AA | Supports | Controls are ≥24px or meet the spacing exception; the 8px column-width drag area meets the equivalent exception — the width box in column settings does the same |

### 3. Understandable

| Criterion | Level | Result | Notes |
|---|---|---|---|
| 3.1.1 Language of Page | A | Not applicable | The page's `lang` is the host's |
| 3.1.2 Language of Parts | AA | Supports | The surface and its popups write the wording catalogue's language as `lang` (a host may set its own); third-party parts are worded from the catalogue too; a declared action's start and result are said in the surface's wording, props-passed included |
| 3.2.1 On Focus | A | Supports | Focus only opens tooltips |
| 3.2.2 On Input | A | Supports | A record view's filter waits for Apply; the analysis tray's auto run is a visible switch; changing a value updates the result without changing context |
| 3.2.3 Consistent Navigation | AA | Supports | The three workbenches share one frame |
| 3.2.4 Consistent Identification | AA | Supports | One function, one name (all names come from one wording catalogue); every grip is the same component |
| 3.2.6 Consistent Help | A | Not applicable | The View Engine offers no help mechanism; help a host adds is the host's to place consistently |
| 3.3.1 Error Identification | A | Supports | Failing conditions are marked where they are (`aria-invalid` and text); query failures are `role=alert` |
| 3.3.2 Labels or Instructions | A | Supports | Every input is labelled, with its rules beside the control; a declared action form says which fields are required, and its submit button, pressed with one blank, says which and goes there |
| 3.3.3 Error Suggestion | AA | Supports | Errors say how to fix them ("A whole number from 1 to 100") |
| 3.3.4 Error Prevention (Legal, Financial, Data) | AA | Supports | Deleting a view asks first, and deleting a system view says everyone loses it; updating a shared view asks first; removing a panel can be undone; leaving unsaved edits asks |
| 3.3.7 Redundant Entry | A | Supports | Save as starts from the current name and a follow-up carries the chosen group; nothing asks for the same entry twice |
| 3.3.8 Accessible Authentication (Minimum) | AA | Not applicable | The View Engine has no sign-in |

### 4. Robust

| Criterion | Level | Result | Notes |
|---|---|---|---|
| 4.1.2 Name, Role, Value | A | Supports | Chromium's accessibility tree has no unnamed interactive control; states are exposed with `aria-pressed`, `aria-expanded`, `aria-sort`, `aria-checked` and the like |
| 4.1.3 Status Messages | AA | Supports | Results, errors, range selection, arranging, moving and column widths are announced; a dashboard says what a filter change came to, failed panels included; the undo notice says what an edit took and, after Undo, that it is back; a declared action's start and result are said, the result leading the refresh's count in one sentence |
