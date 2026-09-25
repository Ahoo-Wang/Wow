---
title: Accessibility of the View Engine
description: The WCAG 2.2 AA conformance statement of the unreleased wow-view-engine — how it was evaluated, the keyboard-only and screen-reader walkthroughs, what was fixed, each criterion's level of support, and the known gaps.
---

# Accessibility of the View Engine

::: warning Not released
`@ahoo-wang/wow-view-engine` has not been published to npm and carries no compatibility promise. This page describes the repository as it stands, and changes with the code.
:::

This page is the conformance statement of the View Engine's interface (`@ahoo-wang/wow-view-engine/ui`: the record workbench, the analysis workbench, dashboards and both embeds) against [WCAG 2.2](https://www.w3.org/TR/WCAG22/) levels A and AA. The result first:

| Supports | Partially supports | Does not support | Not applicable |
|---:|---:|---:|---:|
| 36 | 5 | 0 | 14 |

The five partial criteria are 2.4.11 Focus Not Obscured, 2.5.7 Dragging Movements, 2.5.8 Target Size, 3.1.2 Language of Parts and 4.1.3 Status Messages; the reasons and the plan are under [Known gaps](#known-gaps). No criterion is "does not support", but this statement is **not** a third-party audit and **no** screen-reader software was driven — see the limits under [How it was evaluated](#how-it-was-evaluated).

## Scope

- **What was evaluated**: the Wow repository's `main` (`b17f36a3c`) plus the fixes this page ships with, the `/ui` entry of `typescript/wow-view-engine`. The headless root entry and `/react` draw nothing and are out of scope.
- **What the host owns**: the View Engine always sits in a host's page. The page title (2.4.2), the page language (3.1.1), a skip-to-content link, site-wide navigation, sign-in (3.3.8) and any help mechanism (3.2.6) are the host's, and a host that overrides the `--fve-*` colour variables owns their contrast (see [Theming the View Engine](./view-engine-theming.md#contrast-is-the-overrider-s-responsibility)). Most "not applicable" rows below are for this reason.
- **Data**: the walkthrough ran in [Storybook](/storybook/)'s host shell, on the repository's retail data set and regression fixtures.

## How it was evaluated

- **Date**: 2026-09-25.
- **Environment**: macOS 27.0; the Storybook 10.6 dev server; **Chromium 153** and **WebKit 26.6** (Safari's engine) driven by Playwright 1.63, at a 1440×900 viewport, rechecked at 320 and 900 pixels wide.
- **Keyboard only**: every step is a real key press (Tab, Shift+Tab, the arrows, Enter, Space, Esc, Alt and Shift combinations) — no mouse and no scripted clicks. After each press the walk recorded which element holds focus (role and name), whether a focus indicator is drawn, whether the element is in the viewport, whether its centre point is covered by another element (2.4.11), and what the live regions (`aria-live`, `role=status|alert`) newly said.
- **The screen-reader half**: **VoiceOver was not driven.** Letting AppleScript control VoiceOver means changing the system's security settings, which this evaluation did not do. Three things stand in for it:
  1. Chromium's own computed accessibility tree (CDP `Accessibility.getFullAXTree`) — the names, roles and states the browser hands to the platform's screen-reader API;
  2. Playwright's ARIA snapshots, for structure, landmarks, dialogs and tables;
  3. the text of the live regions after every key press.

  They show what *should* be spoken; they cannot show how a screen reader actually speaks it, how often, or whether another announcement cuts it off. A human pass with VoiceOver and NVDA is a to-do.
- **Automated checks**: every Storybook story runs axe after its interactions (`@storybook/addon-a11y`, `test: 'error'`, so a violation fails it), jsdom unit tests carry their own axe assertions, and the built-in themes' contrast is held by the contrast-matrix tests.
- **One WebKit difference**: Safari's default Tab stops only at text fields and at controls with a `tabindex` ("Press Tab to highlight each item on a webpage" is off). Playwright's WebKit follows that default, so links and some buttons missing from WebKit's Tab order are the platform's behaviour, not a defect; Option+Tab still reaches them.

## Walkthrough results

"Keyboard": whether the task can be done with the keyboard alone, the focus order, visible focus, traps, bypasses and whether shortcuts can be found. "Screen reader": names, roles, states, announcements and structure. ✅ can be done, ⚠️ can be done with a recorded gap, 🔧 found during the walk and fixed with this page.

### Record workbench

| Task | Keyboard | Screen reader |
|---|---|---|
| Open a view | ✅ One Tab stop per view in the sidebar list, Enter opens it, focus stays on the list | ✅ "Opening the view", "N records in all" |
| Filter | 🔧 Add → the field search box → tick → Esc back to Add; the new condition sits before Add, so Shift+Tab goes back to fill it in. **Discard edits and Clear dropped focus to the top of the page**; fixed: it lands on Apply | ✅ Every control of a condition is named after its field ("Warehouse value", "Negate the Warehouse condition"); after Apply, "Running the query" and "N records in all" |
| Sort | ✅ The header row is one Tab stop, ←/→ walk the columns, Enter sorts, Shift+Enter adds a key | ✅ `aria-sort` on the headers; the button's name says what a press will do ("Sort by Order, descending") |
| Resize a column | ⚠️ Alt+←/→ on a header (Shift for large steps), Enter back to automatic; written only in `aria-keyshortcuts`, not on screen | ⚠️ Every step re-runs the query and says "Running the query", "N records in all"; the new width is never said |
| Page | 🔧 Next, Previous and the page box are all reachable; **reaching the last page (or back to the first) disabled the step just pressed and dropped focus to the top of the page**; fixed: it moves to the other step | ✅ Paging is a named `navigation`; the total is said after a page |
| Column settings | 🔧 Search box → each row's handle, pin and summary; **the "Show 〈column〉" checkboxes could not be reached by Tab at all** (the toolbar's roving-focus context leaked into the popup); fixed. Esc takes two presses (the first closes the tooltip keyboard focus opened) | ✅ Moving a row says "Warehouse moved to position 3 of 4"; pinning says "Status is now pinned left" |
| Export | ✅ A modal dialog: focus stays inside, Esc or Cancel returns to Export | ✅ The dialog is named; its content reads the count, the conditions, the columns and the file name |
| Save as | ✅ More view actions menu → dialog; after creating, focus lands on the new view's name | ✅ "Opening the view" |

### Analysis

| Task | Keyboard | Screen reader |
|---|---|---|
| Build a question in the tray | 🔧 Analysis opens the tray and moves focus into it; adding a dimension or a metric is a menu. **Adding the last groupable field disabled Add dimension and dropped focus to the top of the page**; fixed: it lands on the new dimension card | ✅ The tray is four named regions (range, dimensions, metrics, result); each change says "Running the query" and the number of groups |
| Switch chart type | 🔧 Visualize opens the panel with focus on its heading; Tab reaches the chart types (a radiogroup: one Tab stop, the arrows choose). **The result toolbar's arrow keys skipped Visualize** (it was a second Tab stop inside the toolbar); fixed | ✅ Chart types are `radio`s; the disabled ones say why ("Needs two dimensions") |
| Select a range | ✅ In the table layout the rows are one Tab stop, ↑/↓ walk them, Enter is this group and Shift+Enter the stretch between two rows. Chart marks are pointer-only (ECharts has no keyboard navigation); the table layout is the keyboard's way | ✅ "Selected Created between … . Follow-up menu open."; the rows' description explains Shift |
| Follow up | 🔧 The follow-up menu: arrows choose, Enter runs. **"See these records" replaces the whole view and dropped focus to the top of the page; so did the origin bar's Back**; fixed: both land on the new view's name | ✅ The menu is named after the chosen group |
| Export an image | ✅ PNG/SVG in the Export menu, Enter downloads, focus returns to Export | ✅ The browser announces the download |
| Read a chart | — | ✅ A named `role=img` with a one-sentence summary on `aria-describedby` ("30 periods from … to …, rising overall; highest …, lowest …") beside a screen-reader-only data table with a `caption`; ⚠️ the metric card's sparkline has no summary sentence |

### Dashboard

| Task | Keyboard | Screen reader |
|---|---|---|
| Enter build mode | ✅ Edit → focus lands on the edit bar's "Editing"; after Save it returns to Edit | ✅ The edit bar is a named region; "Dashboard saved" |
| Add a panel | ✅ Add menu → dialog (search, a kind filter, grouped list), Esc returns to Add | ✅ Grouped as system, shared and my views; views already on the board say so |
| Move or resize a panel | ✅ Enter on the grip picks it up, the arrows move it, Shift+arrows resize, Enter drops, Esc puts it back: a complete keyboard equivalent of dragging | ✅ "Arranging …", "… is at column 2, row 3, 14 by 4", "… stays where it is", "… is back where it was" |
| Filters | ✅ Every control on the filter bar is reachable; after Clear, focus lands on the first value | ⚠️ The board says nothing after a value changes; a panel's failure is not announced either |
| Cross-filter | ✅ Chart marks are pointer-only; the keyboard equivalent is the same filter on the filter bar, and rows of a table-layout panel can be pressed. The "Click filters “Warehouse”" badge in a panel's header is an explanatory button: focus shows the note, a press does nothing | ✅ The note is read as its description |

### Embeds

| Task | Keyboard | Screen reader |
|---|---|---|
| Static embed (view, dashboard) | ✅ Only reading controls: the header, copy, and panel bodies (a scrollable region is a Tab stop) | ✅ Two embeds' landmarks are named after their own views ("Showing: 〈view〉") and never share a name; an embed has no `main` — the host provides it |
| Interactive embed | ✅ Open in the workbench, export, fill the screen (Esc to leave), search, sort, page; a locked filter reads as its value | ✅ The same names and announcements as the workbench |

### Across the board

- **Visible focus**: one indicator (1px `--ring` plus a 3px halo), drawn inside the cells for rows and cards; ≥3:1 in both themes, held by tests. Every control in the walk drew it.
- **Obscured focus**: 🔧 in a dashboard panel, the copy buttons of the last rows of a list were wholly hidden under the sticky "All" row when focused; fixed (`scroll-padding`). ⚠️ When a column is narrower than its content, the overflowing cell covers most of a focused button in the cell to its right.
- **Traps**: none. Modal dialogs keep focus by design, and Esc closes them and hands focus back to what opened them; popups and menus close when Tab leaves them.
- **Bypassing blocks**: the engine gives named landmarks (`main` named after the view, the view list a `navigation`, the filters, the edit bar and the applied bar `region`s) and two heading levels; where the host's page has a `main` of its own, a workbench given `landmark="region"` draws its column as a `region` of the same name, so the page keeps one `main` (Q64, decided 2026-09-25); the toolbar, the header row and the result rows are one Tab stop each. Each row's checkbox and copy button are a Tab stop each, so a long list takes many presses — headings and landmarks are the faster way through. A skip-to-content link is the host's.
- **Finding shortcuts**: the grips, the rows and the column-settings handles say their keys in their name or description; Alt+←/→ for column width and Shift for ranges are only in the screen reader's description, so a sighted keyboard user cannot find them. There are no single-character shortcuts.

## Fixed with this page

All in the same pull request, each with a regression test (a jsdom unit test or a Storybook interaction test):

| Problem | Fix | Test |
|---|---|---|
| Discard edits and Clear dropped focus to `body` | It lands on Apply | `test/filterPanel.test.tsx` |
| The paging step disabled by the page it fetched dropped focus to `body` | Once the page lands, focus moves to the other step | `test/recordPagination.test.tsx` |
| Adding the last groupable field disabled Add dimension and dropped focus to `body` | The menu hands focus to the new dimension card | `test/analysisFocus.test.tsx` |
| "See these records" and the origin bar's Back replace the view and dropped focus to `body` | A view drawn in place of another takes focus on its name when focus fell | story `KeyboardFollowUpKeepsTheKeyboard` |
| The result toolbar's arrows skipped Visualize | Visualize joins the toolbar's roving order | `test/analysisFocus.test.tsx` |
| The column-settings and card-settings checkboxes could not be reached by Tab | An explicit `tabIndex` (root cause is a to-do) | `test/resultToolbar.test.tsx`, `test/cardSettings.test.tsx` |
| Focus hidden under a sticky summary row (2.4.11) | The table's scroll port carries `scroll-padding-block` | story `FocusClearsTheStickyBands` |

## Known gaps

Each is in the "可访问性" section of the package's [todo.md](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/docs/design/todo.md) with its reason, done criteria and landing; when one is done, its row in the table below changes.

- **A human screen-reader pass**: VoiceOver with Safari and NVDA with Firefox or Chrome have not been run. A 30-minute step-by-step checklist for it (the keys and the expected announcements per task, a results table and severity guidance, in Chinese) is the [screen-reader walkthrough](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/docs/design/screen-reader-walkthrough.md).
- **2.5.7 Dragging Movements**: column order, sort priority, view order, dashboard filter order, panel move and resize, and column width can only be dragged with a pointer; each has a keyboard equivalent, but a pointer user who cannot drag has none.
- **2.5.8 Target Size**: the column-width drag area is 8px wide.
- **2.4.11 Focus Not Obscured**: an overflowing cell in a narrow column covers a focused button.
- **3.1.2 Language of Parts**: the surface does not declare its language, so where its wording differs from the host page's language a screen reader reads it in the host's.
- **4.1.3 Status Messages**: a dashboard's filter change and a panel's failure are not announced; resizing a column announces the query rather than the width.
- **The toolbar popup's roving focus**: the root cause remains, and under Safari's default settings Tab skips the pin buttons and the summary selects in column settings.
- **Other**: record tables have no accessible name; the metric card's sparkline has no summary sentence.

## Criterion by criterion

"Supports": met. "Partially supports": not met in places, which are named. "Not applicable": the View Engine has no such content, or the matter is the host's. WCAG 2.2 removed 4.1.1 Parsing, so it is not listed.

### 1 Perceivable

| Criterion | Level | Result | Notes |
|---|---|---|---|
| 1.1.1 Non-text Content | A | Supports | Icon-only buttons are named (one string for the name and the tooltip); charts are named `role=img` with a summary sentence and a data table; an image in a panel without `alt` takes the panel's title |
| 1.2.1 Audio-only and Video-only (Prerecorded) | A | Not applicable | No audio or video |
| 1.2.2 Captions (Prerecorded) | A | Not applicable | As above |
| 1.2.3 Audio Description or Media Alternative (Prerecorded) | A | Not applicable | As above |
| 1.2.4 Captions (Live) | AA | Not applicable | As above |
| 1.2.5 Audio Description (Prerecorded) | AA | Not applicable | As above |
| 1.3.1 Info and Relationships | A | Supports | Tables use `th` and `rowheader`, chart data tables have a `caption`; form controls are labelled; landmarks and two heading levels; groups are named `group`s |
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
| 1.4.11 Non-text Contrast | AA | Supports | The focus ring, row and card focus, borders and rules are ≥3:1 under the built-in presets |
| 1.4.12 Text Spacing | AA | Supports | With WCAG's line, letter, word and paragraph spacing injected, no text is cut off; glyphs in the 20px badges stay whole |
| 1.4.13 Content on Hover or Focus | AA | Supports | Tooltips open on focus too, close with Esc, can be hovered and do not vanish on their own |

### 2 Operable

| Criterion | Level | Result | Notes |
|---|---|---|---|
| 2.1.1 Keyboard | A | Supports | Every core task can be done with the keyboard alone (this pass fixed the unreachable column-settings checkboxes); chart marks are pointer-only, and the same operations are keyboard-operable in the table layout and on the filter bar |
| 2.1.2 No Keyboard Trap | A | Supports | Only modal dialogs keep focus, and Esc leaves them |
| 2.1.4 Character Key Shortcuts | A | Supports | No single-character shortcuts; every key acts on the focused control only |
| 2.2.1 Timing Adjustable | A | Not applicable | No time limits |
| 2.2.2 Pause, Stop, Hide | A | Supports | Auto refresh is switched on by the user and can be switched off at any time |
| 2.3.1 Three Flashes or Below Threshold | A | Supports | Nothing flashes |
| 2.4.1 Bypass Blocks | A | Supports | Named landmarks and headings; a skip-to-content link is the host's |
| 2.4.2 Page Titled | A | Not applicable | The page title is the host's |
| 2.4.3 Focus Order | A | Supports | This pass fixed six places where focus fell to the top of the page; a newly added filter condition sits before Add, reached with Shift+Tab, which is still logical |
| 2.4.4 Link Purpose (In Context) | A | Supports | Links in panels are named by their text and say they open in a new tab |
| 2.4.5 Multiple Ways | AA | Not applicable | The View Engine is not a set of pages; site navigation is the host's |
| 2.4.6 Headings and Labels | AA | Supports | View, panel and region names say what they are |
| 2.4.7 Focus Visible | AA | Supports | One focus indicator, ≥3:1 in both themes |
| 2.4.11 Focus Not Obscured (Minimum) | AA | Partially supports | Sticky table headers and footers no longer hide focus (fixed in this pass); when a column is narrower than its content, an overflowing cell covers most of a focused button in the cell to its right |
| 2.5.1 Pointer Gestures | A | Supports | No multipoint or path gesture is the only way: a range can be picked with two taps or the keyboard |
| 2.5.2 Pointer Cancellation | A | Supports | Buttons act on release; dropping a drag where it started cancels it |
| 2.5.3 Label in Name | A | Supports | Accessible names contain the visible words ("Order" → "Sort by Order, ascending") |
| 2.5.4 Motion Actuation | A | Not applicable | Nothing is triggered by device motion |
| 2.5.7 Dragging Movements | AA | Partially supports | Column order, sort priority, view order, filter order, panel move and resize, and column width can only be dragged; series order and tab order have buttons |
| 2.5.8 Target Size (Minimum) | AA | Partially supports | The column-width drag area is 8px wide; other controls are ≥24px or meet the spacing exception |

### 3 Understandable

| Criterion | Level | Result | Notes |
|---|---|---|---|
| 3.1.1 Language of Page | A | Not applicable | The page's `lang` is the host's |
| 3.1.2 Language of Parts | AA | Partially supports | The surface writes no `lang`, so where its wording differs from the host's language a screen reader reads it in the host's |
| 3.2.1 On Focus | A | Supports | Focus only opens tooltips |
| 3.2.2 On Input | A | Supports | A record view's filter waits for Apply; the analysis tray's auto run is a visible switch; changing a value updates the result without changing context |
| 3.2.3 Consistent Navigation | AA | Supports | The three workbenches share one frame |
| 3.2.4 Consistent Identification | AA | Supports | One function, one name (all names come from one wording catalogue); every grip is the same component |
| 3.2.6 Consistent Help | A | Not applicable | The View Engine offers no help mechanism; help a host adds is the host's to place consistently |
| 3.3.1 Error Identification | A | Supports | Failing conditions are marked where they are (`aria-invalid` and text); query failures are `role=alert` |
| 3.3.2 Labels or Instructions | A | Supports | Every input is labelled, with its rules beside the control |
| 3.3.3 Error Suggestion | AA | Supports | Errors say how to fix them ("A whole number from 1 to 100") |
| 3.3.4 Error Prevention (Legal, Financial, Data) | AA | Supports | Deleting a view asks first; removing a panel can be undone; leaving unsaved edits asks |
| 3.3.7 Redundant Entry | A | Supports | Save as starts from the current name and a follow-up carries the chosen group; nothing asks for the same entry twice |
| 3.3.8 Accessible Authentication (Minimum) | AA | Not applicable | The View Engine has no sign-in |

### 4 Robust

| Criterion | Level | Result | Notes |
|---|---|---|---|
| 4.1.2 Name, Role, Value | A | Supports | Chromium's accessibility tree has no unnamed interactive control; states are exposed with `aria-pressed`, `aria-expanded`, `aria-sort`, `aria-checked` and the like |
| 4.1.3 Status Messages | AA | Partially supports | Results, errors, range selection, arranging and moving are announced; a dashboard's filter change and panel failures are not; resizing a column announces the query rather than the width |

## Where to read more

- [View Engine](./view-engine.md): the problem it solves and how to use it.
- [Theming the View Engine](./view-engine-theming.md): the contrast an override owes.
- [Design documents](https://github.com/Ahoo-Wang/Wow/tree/main/typescript/wow-view-engine/docs/design): the focus and announcement rules are in `ui/README.md`.
