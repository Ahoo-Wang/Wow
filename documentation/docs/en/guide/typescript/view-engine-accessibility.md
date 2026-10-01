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
| 41 | 0 | 0 | 14 |

The five criteria this statement first gave as partial — 2.4.11 Focus Not Obscured, 2.5.7 Dragging Movements, 2.5.8 Target Size, 3.1.2 Language of Parts and 4.1.3 Status Messages — are supported since the [follow-up fixes](#fixed-since). The second walkthrough on 2026-09-30 covered the interactions added after 09-25 and [fixed a batch of them](#fixed-in-the-second-walkthrough); the five criteria it left at "partially supports" — 1.3.1, 2.4.3, 3.1.2, 3.3.2 and 4.1.3, all because of **declared actions** (a row's primary action and ⋯ menu, the bulk bar and action forms) — are supported since [their fixes](#declared-actions-fixed) (PR #3834). No criterion is "does not support", but this statement is **not** a third-party audit and **no** screen-reader software was driven — see the limits under [How it was evaluated](#how-it-was-evaluated) and [Known gaps](#known-gaps).

## Scope

- **What was evaluated**: the Wow repository's `main` (`c1494d2ed`) plus the fixes merged with this version of the page (the second walkthrough, PR #3826, and the declared actions' fixes, PR #3834), the `/ui` entry of `typescript/wow-view-engine`; the first evaluation was of `b17f36a3c`. The headless root entry and `/react` draw nothing and are out of scope.
- **What the host owns**: the View Engine always sits in a host's page. The page title (2.4.2), the page language (3.1.1), a skip-to-content link, site-wide navigation, sign-in (3.3.8) and any help mechanism (3.2.6) are the host's, and a host that overrides the `--fve-*` colour variables owns their contrast (see [Theming the View Engine](./view-engine-theming.md#contrast-is-the-overrider-s-responsibility)). Most "not applicable" rows below are for this reason.
- **Data**: the walkthrough ran in [Storybook](/storybook/)'s host shell, on the repository's retail data set and regression fixtures.

## How it was evaluated

- **Date**: the first pass 2026-09-25; the second 2026-09-30 (what was added after 09-25 — declared actions, `ViewHost`, the analysis tray's expansion and undo notice, the dashboard's toasts, the chart picker's two-column grid — plus forced colours and reduced motion), walked again on this branch after its fixes.
- **Environment**: macOS 27.0; the Storybook 10.6 dev server; **Chromium 153** and **WebKit 26.6** (Safari's engine) driven by Playwright 1.63, at a 1440×900 viewport, rechecked at 320 and 900 pixels wide. The second pass used Chromium alone, on a static Storybook build, from 1440×900 and also at 1280×720, 640×450 (about 200% zoom) and 320×640.
- **Keyboard only**: every step is a real key press (Tab, Shift+Tab, the arrows, Enter, Space, Esc, Alt and Shift combinations) — no mouse and no scripted clicks. After each press the walk recorded which element holds focus (role and name), whether a focus indicator is drawn, whether the element is in the viewport, whether its centre point is covered by another element (2.4.11), and what the live regions (`aria-live`, `role=status|alert`) newly said.
- **The screen-reader half**: **VoiceOver was not driven.** Letting AppleScript control VoiceOver means changing the system's security settings, which this evaluation did not do. Three things stand in for it:
  1. Chromium's own computed accessibility tree (CDP `Accessibility.getFullAXTree`) — the names, roles and states the browser hands to the platform's screen-reader API;
  2. Playwright's ARIA snapshots, for structure, landmarks, dialogs and tables;
  3. the text of the live regions after every key press.

  They show what *should* be spoken; they cannot show how a screen reader actually speaks it, how often, or whether another announcement cuts it off. A human pass with VoiceOver and NVDA is a to-do.
- **Automated checks**: every Storybook story runs axe after its interactions (`@storybook/addon-a11y`, `test: 'error'`, so a violation fails it), jsdom unit tests carry their own axe assertions, and the built-in themes' contrast is held by the contrast-matrix tests. The second pass also ran axe 4.13 over 11 stories × 4 presets × light and dark: 88 runs, no violations.
- **Forced colours and reduced motion**: the second pass walked the menus, the selected rows, the current view and focus under Playwright's `forcedColors: 'active'` (an approximation of Windows contrast themes), checked that `reducedMotion: 'reduce'` holds the animations, and injected WCAG 1.4.12's text spacing.
- **One WebKit difference**: Safari's default Tab stops only at text fields and at controls with a `tabindex` ("Press Tab to highlight each item on a webpage" is off). Playwright's WebKit follows that default, so links and some buttons missing from WebKit's Tab order are the platform's behaviour, not a defect; Option+Tab still reaches them.

## Walkthrough results

"Keyboard": whether the task can be done with the keyboard alone, the focus order, visible focus, traps, bypasses and whether shortcuts can be found. "Screen reader": names, roles, states, announcements and structure. ✅ can be done, ⚠️ can be done with a recorded gap, 🔧 found by the walk or listed as a known gap, and since fixed (with this page, or [since](#fixed-since)).

### Record workbench

| Task | Keyboard | Screen reader |
|---|---|---|
| Open a view | ✅ One Tab stop per view in the sidebar list, Enter opens it, focus stays on the list | ✅ "Opening the view", "N records in all" |
| Filter | 🔧 Add → the field search box → tick → Esc lands on the new condition's value box, ready to fill in. **Discard edits and Clear dropped focus to the top of the page**; fixed: it lands on Apply | ✅ Every control of a condition is named after its field ("Warehouse value", "Negate the Warehouse condition"); after Apply, "Running the query" and "N records in all" |
| Sort | ✅ The header row is one Tab stop, ←/→ walk the columns, Enter sorts, Shift+Enter adds a key | 🔧 `aria-sort` on the headers; the button's name says what a press will do ("Sort by Order, descending"). **A header's name was the button's action plus the width handle's name** ("Sort by Order, ascending Resize Order"), read as the column's name in every cell; fixed: a header is named by its column |
| Resize a column | 🔧 Alt+←/→ on a header (Shift for large steps), Alt+Enter back to automatic; **written only in `aria-keyshortcuts`**, fixed: column settings write the keys out, and each row there has a width box | 🔧 **Every step re-ran the query and said "Running the query", "N records in all", never the width**; fixed: a width fetches nothing, and each step says "〈column〉 is N px wide" |
| Page | 🔧 Next, Previous and the page box are all reachable; **reaching the last page (or back to the first) disabled the step just pressed and dropped focus to the top of the page**; fixed: it moves to the other step | ✅ Paging is a named `navigation`; the total is said after a page |
| Column settings | 🔧 Search box → each row's handle, checkbox, summary, width and pin; **the "Show 〈column〉" checkboxes could not be reached by Tab at all** (the toolbar's roving-focus context leaked into the popup); fixed, at the root: the popup is drawn outside the toolbar. Esc takes two presses (the first closes the tooltip keyboard focus opened) | ✅ Moving a row says "Warehouse moved to position 3 of 4"; pinning says "Status is now pinned left" |
| Export | ✅ A modal dialog: focus stays inside, Esc or Cancel returns to Export | ✅ The dialog is named; its content reads the count, the conditions, the columns and the file name |
| Save as | ✅ More view actions menu → dialog; after creating, focus lands on the new view's name | ✅ "Opening the view" |

### Analysis

| Task | Keyboard | Screen reader |
|---|---|---|
| Build a question in the tray | 🔧 Analysis opens the tray and moves focus into it; adding a dimension or a metric is a menu. **Adding the last groupable field disabled Add dimension and dropped focus to the top of the page**; fixed: it lands on the new dimension card | ✅ The tray is four named regions (range, dimensions, metrics, result); each change says "Running the query" and the number of groups |
| Expansion and its undo notice | 🔧 "Expand into Items" took the order-level metrics with it, and a notice with Undo and Dismiss appears at the tray's foot. **The expand button, replaced by the new level's card, and Undo and Dismiss, gone with the notice, all dropped focus to the top of the page**; fixed: after expanding focus lands on the new card, after Undo back on "Expand into Items", after Dismiss on the card | ✅ "Expanding Items took out 2 metrics: …"; after Undo, "Undone; the question is back as it was" |
| Switch chart type | 🔧 Visualize opens the panel with focus on its heading; Tab reaches the chart types (a radiogroup: one Tab stop, the arrows choose). **The result toolbar's arrow keys skipped Visualize** (it was a second Tab stop inside the toolbar); fixed | ✅ Chart types are `radio`s; the disabled ones say why ("Needs two dimensions") |
| Select a range | 🔧 In the table layout the rows are one Tab stop, ↑/↓ walk them, Enter is this group and Shift+Enter the stretch between two rows. **Shift was said only in the rows' description**; fixed: the menu a single row opens writes it under the group. Chart marks are pointer-only (ECharts has no keyboard navigation); the table layout is the keyboard's way | ✅ "Selected Created between … . Follow-up menu open."; the rows' description explains Shift |
| Follow up | 🔧 The follow-up menu: arrows choose, Enter runs. **"See these records" replaces the whole view and dropped focus to the top of the page; so did the origin bar's Back**; fixed: both land on the new view's name | ✅ The menu is named after the chosen group |
| Export an image | ✅ PNG/SVG in the Export menu, Enter downloads, focus returns to Export | ✅ The browser announces the download |
| Read a chart | — | ✅ A named `role=img` with a one-sentence summary on `aria-describedby` ("30 periods from … to …, rising overall; highest …, lowest …") beside a screen-reader-only data table with a `caption`; 🔧 **the metric card's sparkline had no summary sentence**, fixed: it has the time axis's sentence, and its table's first column is named by the dimension |

### Dashboard

| Task | Keyboard | Screen reader |
|---|---|---|
| Enter build mode | ✅ Edit → focus lands on the edit bar's "Editing"; after Save it returns to Edit | ✅ The edit bar is a named region; "Dashboard saved" |
| Add a panel | ✅ Add menu → dialog (search, a kind filter, grouped list), Esc returns to Add | ✅ Grouped as system, shared and my views; views already on the board say so |
| Move or resize a panel | ✅ Enter on the grip picks it up, the arrows move it, Shift+arrows resize, Enter drops, Esc puts it back: a complete keyboard equivalent of dragging. 🔧 **A pointer that cannot drag had no way**; fixed: the panel's ⋯ has Move or resize, one click a step | ✅ "Arranging …", "… is at column 2, row 3, 14 by 4", "… stays where it is", "… is back where it was" |
| Filters | ✅ Every control on the filter bar is reachable; after Clear, focus lands on the first value | 🔧 **The board said nothing after a value changed, nor of a panel's failure**; fixed: once its panels settle it says "Filtered by 〈filter〉; N panels updated.", with "M panels could not load." when some did not |
| Auto-wiring notice | ✅ After adding a time filter, the "Wired …" toast's action and its close button are reachable by Tab | ✅ The toast is read in a polite region named for it |
| Cross-filter | ✅ Chart marks are pointer-only; the keyboard equivalent is the same filter on the filter bar, and rows of a table-layout panel can be pressed. The "Click filters “Warehouse”" badge in a panel's header is an explanatory button: focus shows the note, a press does nothing | ✅ The note is read as its description |

### Declared actions

The commands a host declares with `actions()`: a row's primary action and its ⋯ menu, the bulk bar for selected rows, actions with a form and dangerous confirmations, progress and results. Added for the second pass; the gaps it found are [fixed](#declared-actions-fixed), and the rows below are re-walked on the fix (keyboard in Storybook on Chromium, and jsdom).

| Task | Keyboard | Screen reader |
|---|---|---|
| Run an action | 🔧 The primary action and the ⋯ menu are reachable, Enter runs. **While it ran, every action on the surface was disabled and focus fell to the top of the page**, fixed: a busy button stays focusable (`aria-disabled`), the ⋯ stays openable with its items held, a question closes back onto the button that asked, and when the refresh takes the row or the selection's bar away, focus lands on the result line's "Dismiss" | 🔧 "发货 · 正在执行 0/1", then "发货 · SO-1003 已完成；共 4 条记录". **A host passing its wording by props heard it in English, and the result was replaced 10ms later by "N records in all"**, fixed: the surface's wording is passed down, and the result leads the refresh's count in one sentence |
| A disabled action | 🔧 **Native `disabled`, out of the Tab order**, fixed: focusable, its tooltip opens on focus | 🔧 The reason is the button's description |
| An action with a form | ✅ The dialog keeps focus, Esc returns to ⋯; 🔧 the submit button stays pressable — pressed with a required field blank, it marks the field invalid and moves focus there | 🔧 **"Required" was not exposed to assistive technology and the form was an `alertdialog`**, fixed: the label points at its control, `aria-required`, "Required" as its description, `aria-invalid` only after a submit; a question with a form, or a routine one, is a `dialog` — only a dangerous question with nothing to fill is an `alertdialog` |
| Selection and bulk | 🔧 **Each bulk button was a Tab stop of its own**, fixed: they are items of the result toolbar, one Tab stop with the arrows through them | ✅ "N selected"; "发货 3/4 条" says how many can take it before the press |

### Embeds

| Task | Keyboard | Screen reader |
|---|---|---|
| Static embed (view, dashboard) | ✅ Only reading controls: the header, copy, and panel bodies (a scrollable region is a Tab stop) | ✅ Two embeds' landmarks are named after their own views ("Showing: 〈view〉") and never share a name; an embed has no `main` — the host provides it |
| Interactive embed | ✅ Open in the workbench, export, fill the screen (Esc to leave), search, sort, page; a locked filter reads as its value | ✅ The same names and announcements as the workbench |

### Across the board

- **Visible focus**: one indicator for controls (1px `--ring` plus a 3px halo, wider where a preset says), drawn inside the cells for rows and cards; the item under the keyboard in a menu, a select or a combobox draws 2px of `--ring` inside its edge. All ≥3:1 against what is around them, held by tests. 🔧 The second pass found a menu item's place told only by its highlight fill — 1.03–1.07:1 against the popup under neutral and azure — and the items of the panel, drill-down and export menus given a pale grey of about 1.2:1 under contrast; fixed: the `--ring` mark, and the highlight rules pick items by role. Every control in the walk drew a focus indicator.
- **Forced colours**: 🔧 every menu item wore the same frame, so the focused one could not be told apart, and popups had no edge; fixed: the item under the keyboard is framed in `Highlight`, the others not at all, and popups draw a `CanvasText` edge. Focus, selected rows, the current view and pressed toggles all stay visible.
- **Reduced motion**: under `prefers-reduced-motion` animations and transitions drop to 0.01ms, keeping the spinner and skeletons; charts read the setting too.
- **Obscured focus**: 🔧 in a dashboard panel, the copy buttons of the last rows of a list were wholly hidden under the sticky "All" row when focused; fixed (`scroll-padding`). 🔧 When a column was narrower than its content, the overflowing cell covered most of a focused button in the cell to its right; fixed: a cell with a width clips its content, and a copy button stays whole in its own cell.
- **Traps**: none. Modal dialogs keep focus by design, and Esc closes them and hands focus back to what opened them; popups and menus close when Tab leaves them.
- **Bypassing blocks**: the engine gives named landmarks (`main` named after the view, the view list a `navigation`, the filters, the edit bar and the applied bar `region`s) and two heading levels; where the host's page has a `main` of its own, a workbench given `landmark="region"` draws its column as a `region` of the same name, so the page keeps one `main` (Q64, decided 2026-09-25); the toolbar, the header row and the result rows are one Tab stop each. Each row's checkbox and copy button are a Tab stop each, so a long list takes many presses — headings and landmarks are the faster way through. A skip-to-content link is the host's.
- **Finding shortcuts**: the grips, the rows and the column-settings handles say their keys in their name or description; Alt+←/→ for column width is written out in column settings, and Shift for a range in the follow-up menu a row opens. There are no single-character shortcuts.
- **Language**: the surface writes its wording's language as `lang` (and so does every popup it opens), so Chinese wording inside an English page is read as Chinese. 🔧 Three third-party parts wrote English of their own onto a Chinese surface — the number box's role description "Number field", the calendar's "Navigation bar" and "Loading" while value suggestions load; fixed: the first two come from the wording catalogue ("数字输入框", "月份切换"), and the spinner is no longer said beside words that already say it. Declared actions still speak English on hosts that pass their wording by props (above).

## Fixed with this page

All in the same pull request, each with a regression test (a jsdom unit test or a Storybook interaction test):

| Problem | Fix | Test |
|---|---|---|
| Discard edits and Clear dropped focus to `body` | It lands on Apply | `test/filterPanel.test.tsx` |
| The paging step disabled by the page it fetched dropped focus to `body` | Once the page lands, focus moves to the other step | `test/recordPagination.test.tsx` |
| Adding the last groupable field disabled Add dimension and dropped focus to `body` | The menu hands focus to the new dimension card | `test/analysisFocus.test.tsx` |
| "See these records" and the origin bar's Back replace the view and dropped focus to `body` | A view drawn in place of another takes focus on its name when focus fell | story `KeyboardFollowUpKeepsTheKeyboard` |
| The result toolbar's arrows skipped Visualize | Visualize joins the toolbar's roving order | `test/analysisFocus.test.tsx` |
| The column-settings and card-settings checkboxes could not be reached by Tab | An explicit `tabIndex` at first; since, the root cause is fixed (see below) | `test/resultToolbar.test.tsx`, `test/cardSettings.test.tsx` |
| Focus hidden under a sticky summary row (2.4.11) | The table's scroll port carries `scroll-padding-block` | story `FocusClearsTheStickyBands` |

## Fixed since {#fixed-since}

The known items this statement listed as gaps, fixed in one later pull request, each with a regression test:

| Problem | Fix | Test |
|---|---|---|
| The toolbar's roving focus leaked into its popups: Safari's default Tab skipped the pin buttons and summary selects, and checkboxes needed a hand-written `tabIndex` | The popups of the column, card and sort settings are drawn outside the toolbar, joined to its buttons by `Popover.createHandle`, and so is the export window the record and analysis result toolbars open (`Dialog.createHandle`); the hand-written `tabIndex`es are gone and every control writes its own `tabindex="0"` | `test/resultToolbar.test.tsx`, `test/chartImageExport.test.tsx`; story `ToolbarPopupsKeepTheirTabStops` |
| Panel move and resize, and column width, only by dragging (2.5.7) | A panel's ⋯ has Move or resize (move left, right, up, down; wider, narrower, taller, shorter), one click a step; column settings have a width box on every row | `test/dashboardPlacement.test.tsx`, `test/columnWidthInput.test.tsx`; stories `PanelArrangedByClicks`, `ColumnWidthByTyping` |
| The 8px column-width drag area (2.5.8) | The width box is the equivalent control the spacing exception allows; the strip keeps clear of the sort button | as above |
| Column width, order, pin and summaries re-ran the query and said so (4.1.3) | An edit that only changes how the rows are drawn runs nothing; a summary change asks for the aggregation alone; each width step says the width | `test/restyle.test.tsx`, `test/headerRoving.test.tsx`; story `WidthStepsSayTheWidth` |
| A dashboard's filter change and panel failures were silent (4.1.3) | Said once the panels settle: which filters, how many panels, how many failed | `test/dashboardFilterOutcome.test.tsx`; story `FilterChangeIsSaid` |
| Record tables had no name; the sparkline no summary, its table's first header "Category" | Tables are named by the view or the panel; the sparkline has the time axis's sentence, the header is the dimension's name | `test/tableNames.test.tsx`; story `TableNamedByItsView` |
| An overflowing cell covered a focused copy button (2.4.11) | The width clip had lost its class prefix and did nothing; restored, and the copy cell lets its text give way | story `CopyStaysInANarrowCell` |
| The surface declared no language (3.1.2) | `lang` from the wording catalogue on the surface and its popups; a host may override it | `test/surfaceLanguage.test.tsx` |
| Alt+←/→ and Shift+Enter were only in the screen reader's words | Written in column settings and in the follow-up menu | `test/columnWidthInput.test.tsx`; story `KeyboardPicksAStretch` |

## Fixed in the second walkthrough {#fixed-in-the-second-walkthrough}

Merged with PR #3826, each with a regression test:

| Problem | Fix | Test |
|---|---|---|
| "Expand into Items" dropped focus to `body` (2.4.3) | Expanding to the chain's last level lands on the new card | `test/elementsSlot.test.tsx`; story `ExpansionUndo` |
| The undo notice's Undo and Dismiss dropped focus to `body` (2.4.3) | The landing is worked out at the press: Undo returns to "Expand into Items", Dismiss lands on the card | as above |
| A menu item's place was told only by its highlight fill, 1.03–1.07:1 off the popup under neutral and azure (1.4.11, 2.4.7) | The item under the keyboard draws 2px of `--ring` inside its edge; the contrast matrix holds it ≥3:1 off the popup | `test/presetContrast.test.ts`; stories `MenuFocusMark*` |
| The panel, drill-down and export menus' items missed the theme's highlight (about 1.2:1 under contrast) | The highlight rules pick `menuitem` / `option` roles rather than a list of `data-slot`s | story `PanelMenuHighlight` |
| Under forced colours the focused menu item could not be told apart, and popups had no edge (2.4.7) | The focused item is framed in `Highlight`, the others not; popups draw a `CanvasText` edge | story `MenuInForcedColors` |
| "Number field", "Navigation bar" and "Loading" in English on a Chinese surface (3.1.2) | From the wording catalogue; the spinner is not said twice | `test/surfaceLanguage.test.tsx`, `test/suggestedValue.test.tsx` |
| A column header's name was the sort button's action plus the width handle's name (1.3.1, 2.4.6) | Headers are named by their column; the sort is `aria-sort`'s | `test/tableNames.test.tsx` |

### Declared actions {#declared-actions-fixed}

Merged with PR #3834, each with a regression test:

| Problem | Fix | Test |
|---|---|---|
| Running an action dropped focus to the top of the page — row primary action, ⋯ menu, bulk bar (2.4.3) | Busy and unavailable buttons are `focusableWhenDisabled` (`aria-disabled`, the press swallowed); the ⋯ stays openable; a question closes onto the control that asked; focus that fell when the refresh took the row or the bar lands on the result line's "Dismiss" | `test/declaredActions.test.tsx` "the keyboard after a command"; stories `RowPrimaryAndOverflow`, `BulkPartialAvailability`, `FormInput`, `DangerConfirm` (focus and the live region asserted in Chromium) |
| A disabled primary action could not be reached by Tab; its reason only on hover and in the ⋯ menu's group label | Focusable, the tooltip opens on focus, the reason is its description | as above |
| A host passing its wording by props heard an action's start and result in English (3.1.2), and the result was replaced at once by "N records in all" (4.1.3) | The record view passes its wording to the action surface; the result leads the refresh's count in one sentence | `test/declaredActions.test.tsx` "what the surface says"; stories `RowPrimaryAndOverflow`, `BulkPartialAvailability`, `FormInput`, `DangerConfirm` (focus and the live region asserted in Chromium) |
| An action form's "Required" was not exposed, its submit button disabled without saying why (1.3.1, 3.3.2), the form an `alertdialog` | `aria-required` and "Required" as the description, `aria-invalid` after a submit, the submit pressable and moving focus to the first blank field; `dialog` unless a dangerous question with no form | `test/declaredActions.test.tsx`; story `FormInput` |
| The bulk buttons broke the result toolbar's one Tab stop | Toolbar items, in its arrow-key order | `test/declaredActions.test.tsx` |

## Known gaps

The package's [todo.md](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/docs/design/todo.md) holds what is still open with its reason, done criteria and landing; when one is done, its row in the table below changes.

- **A human screen-reader pass**: VoiceOver with Safari and NVDA with Firefox or Chrome have not been run. A 30-minute step-by-step checklist for it (the keys and the expected announcements per task, a results table and severity guidance, in Chinese) is the [screen-reader walkthrough](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/docs/design/screen-reader-walkthrough.md).

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
| 2.4.3 Focus Order | A | Supports | The first pass fixed six places where focus fell to the top of the page, the second two in the analysis tray and the declared actions' (row primary action, ⋯ menu, bulk bar); Esc after adding a filter condition lands on its value box |
| 2.4.4 Link Purpose (In Context) | A | Supports | Links in panels are named by their text and say they open in a new tab |
| 2.4.5 Multiple Ways | AA | Not applicable | The View Engine is not a set of pages; site navigation is the host's |
| 2.4.6 Headings and Labels | AA | Supports | View, panel and region names say what they are; column headers are named by their column |
| 2.4.7 Focus Visible | AA | Supports | One focus indicator for controls and a `--ring` mark inside menu items, ≥3:1 in both themes; under forced colours focus, menu items and selection are all framed |
| 2.4.11 Focus Not Obscured (Minimum) | AA | Supports | Sticky table headers and footers do not hide focus; a column narrower than its content clips it, and a copy button stays whole inside its own cell |
| 2.5.1 Pointer Gestures | A | Supports | No multipoint or path gesture is the only way: a range can be picked with two taps or the keyboard |
| 2.5.2 Pointer Cancellation | A | Supports | Buttons act on release; dropping a drag where it started cancels it |
| 2.5.3 Label in Name | A | Supports | Accessible names contain the visible words ("Order" → "Sort by Order, ascending") |
| 2.5.4 Motion Actuation | A | Not applicable | Nothing is triggered by device motion |
| 2.5.7 Dragging Movements | AA | Supports | Every ordered list's handle opens a menu of places on a click; a panel's ⋯ moves and resizes it one click a step; a column's width is typed in column settings |
| 2.5.8 Target Size (Minimum) | AA | Supports | Controls are ≥24px or meet the spacing exception; the 8px column-width drag area meets the equivalent exception — the width box in column settings does the same |

### 3 Understandable

| Criterion | Level | Result | Notes |
|---|---|---|---|
| 3.1.1 Language of Page | A | Not applicable | The page's `lang` is the host's |
| 3.1.2 Language of Parts | AA | Supports | The surface and its popups write the wording catalogue's language as `lang` (a host may set its own); third-party English in the number box, the calendar and value suggestions now comes from the catalogue; a declared action's start and result are said in the surface's wording, props-passed included |
| 3.2.1 On Focus | A | Supports | Focus only opens tooltips |
| 3.2.2 On Input | A | Supports | A record view's filter waits for Apply; the analysis tray's auto run is a visible switch; changing a value updates the result without changing context |
| 3.2.3 Consistent Navigation | AA | Supports | The three workbenches share one frame |
| 3.2.4 Consistent Identification | AA | Supports | One function, one name (all names come from one wording catalogue); every grip is the same component |
| 3.2.6 Consistent Help | A | Not applicable | The View Engine offers no help mechanism; help a host adds is the host's to place consistently |
| 3.3.1 Error Identification | A | Supports | Failing conditions are marked where they are (`aria-invalid` and text); query failures are `role=alert` |
| 3.3.2 Labels or Instructions | A | Supports | Every input is labelled, with its rules beside the control; a declared action form says which fields are required, and its submit button, pressed with one blank, says which and goes there |
| 3.3.3 Error Suggestion | AA | Supports | Errors say how to fix them ("A whole number from 1 to 100") |
| 3.3.4 Error Prevention (Legal, Financial, Data) | AA | Supports | Deleting a view asks first; removing a panel can be undone; leaving unsaved edits asks |
| 3.3.7 Redundant Entry | A | Supports | Save as starts from the current name and a follow-up carries the chosen group; nothing asks for the same entry twice |
| 3.3.8 Accessible Authentication (Minimum) | AA | Not applicable | The View Engine has no sign-in |

### 4 Robust

| Criterion | Level | Result | Notes |
|---|---|---|---|
| 4.1.2 Name, Role, Value | A | Supports | Chromium's accessibility tree has no unnamed interactive control; states are exposed with `aria-pressed`, `aria-expanded`, `aria-sort`, `aria-checked` and the like |
| 4.1.3 Status Messages | AA | Supports | Results, errors, range selection, arranging, moving and column widths are announced; a dashboard says what a filter change came to, failed panels included; the undo notice says what an edit took and, after Undo, that it is back; a declared action's start and result are said, the result leading the refresh's count in one sentence |

## Where to read more

- [View Engine](./view-engine.md): the problem it solves and how to use it.
- [Theming the View Engine](./view-engine-theming.md): the contrast an override owes.
- [Design documents](https://github.com/Ahoo-Wang/Wow/tree/main/typescript/wow-view-engine/docs/design): the focus and announcement rules are in `ui/README.md`.
