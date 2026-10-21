---
title: The Workbench
description: For the people who use a Wow application's workbench — record views, analysis views, boards, and saving and sharing views — with no code.
---

# The Workbench

Many applications built on Wow show their data in a **workbench**: a list of saved views on one side, the open view on the other. This page is for the people who use it, such as operations, analysts and support, not for the developers who build it. Nothing here needs code.

What you can do depends on how your application set it up: which data it offers, which fields can be filtered or summed, and who may share views. The names below are the workbench's own wording; your application may use different words.

## The screen

- **The view list** groups views as **My views**, **Shared views** and **System views**. **New view** starts one. You can hide the list to give the view the whole width, or fill the screen with it.
- **The title bar** names the open view and says whether it is saved: **Not saved yet**, **Edited** after a change, or **Only the look changed**. **Save** sits beside the name; **More view actions** holds **Save as**, **Make shared** or **Make personal**, **Add to dashboard…** and **Copy link**.
- **The conditions line** says what is showing now, with a search box when the data can be searched. Press **/** outside a field to jump to it.
- **Refresh** reads the data again; its arrow sets **Auto refresh**.

There are three kinds of view.

## Record views: which records

A record view lists records, one row each.

**Filter.** **Filter** opens the conditions. **Simple** lists one condition per field: a field offers the operators that make sense for it, such as "last 7 days" for a date or "is any of" for a status. **Advanced** combines conditions in groups, **All of**, **Any of** or **None of**, nested as deep as you need. Edits wait for **Apply**; **Clear** removes every condition.

**Read the result.**

- **Layout** switches between **Table**, **Cards** and, where records move through stages, **Kanban**, one column per stage.
- **Columns** chooses the columns and their order; drag a header's edge to widen a column.
- Press a column header to sort; hold Shift to sort by several columns.
- **Summaries** under the table show a total, average, minimum, maximum or count, for the page and for all matching rows.
- An empty result says why, and offers to change or clear the conditions.

**Open a record.** Press the empty part of a row to open its detail at the side: every field, grouped, including what the list does not show. With the keyboard, move with the arrow keys and press Enter. In the detail, **K** and **J** move to the record before or after.

**Act on records.** Select rows, or Shift-click a range, and **Actions** offers what your application lets you do with them. An action that changes data may ask you to confirm or fill in a form, and says how it went for each record. A record whose outcome is unknown, because no answer came back, is unselected: refresh to check before you try again.

**Export.** **Export** writes the selected rows, or all matching rows, as a CSV file, with the conditions noted. Cells that start like a spreadsheet formula are written so a spreadsheet does not run them.

## Analysis views: how many, how much, by what

An analysis view answers a question such as "orders and revenue by warehouse, last month", one row per group, as a table or a chart.

**Ask the question.** **Analysis** opens the question, in parts:

| Part | What you set |
| --- | --- |
| **Expand** | Count the entries inside each record instead of the records, such as the lines of each order |
| **Metrics** | What to measure: a count, a sum, an average, a minimum or maximum, a percentile, a distinct count; a **Formula** of two fields, a **Derived metric** of two metrics, or the **Time between two moments** |
| **Dimensions** | How to group: by a field's values, by a date (year, quarter, month, week, day, hour), or by a cycle (weekday, hour of day) |
| **Result** | Which groups to keep, in what order, and how many |
| **Range** | Which records take part: the same conditions as a record view's |

A change that would leave the question incomplete says what it took out and offers **Undo**. Unless you turn it off, the result updates as you edit.

**Show the result.** **Visualize** opens the chart types: bar, line, area, pie, heatmap, scatter, funnel, single number, waterfall, treemap and more. Those that can draw this result come first; the others say what they are missing. When there are more groups than shown, the view says so.

**Follow up.** Press a bar, a slice or a row: **See these records** opens the records behind it; **Split this group by…** breaks it down further; **Only this group** narrows the analysis. A view opened this way has a **Back to …** line.

**Export.** **Export data…** writes the groups as CSV; a chart also exports as PNG or SVG.

## Boards: several views on one page

A board puts saved views, analyses, headings, text, images and links on one page, filtered together.

**Read a board.** **Tabs** split a large board into pages. **Filters** at the top narrow every panel at once; a board may also have a fixed scope its author set. Pressing a chart does what the author chose: open the follow-up menu, set a board filter, or go to another view. A panel that fails keeps its last result and offers to try again.

**Build a board.** **New dashboard** starts one; **Edit** opens an existing one for editing. **Add** a data panel (a saved view, or a new analysis built on the spot) or a content panel (a heading, text, an image or links). Drag a panel by its handle, resize it from its corner, or, with the keyboard, press Enter on the handle and use the arrow keys. A panel's menu edits, duplicates or removes it, moves it to another tab, and chooses what a press on it does. **Add filter** puts a board filter on the bar and says which panels it reaches.

A shared board can show only shared and system views, so everyone who opens it can read every panel.

## Save and share views

A saved view keeps the way you look at the data: conditions, columns, sort, metrics, chart, panels. It never keeps the records themselves, your selection or the page you were on.

| Group | Who sees it | Who can change it |
| --- | --- | --- |
| **My views** | Only you | You |
| **Shared views** | Everyone who uses this data | Those your application allows |
| **System views** | Everyone | Your organisation; you can open them and save a copy |

- **Save** keeps your changes in the open view. The first save asks for a title and **Who can see it**: **Only me** or **Everyone**. Saving a shared view asks first, because it changes it for everyone.
- **Save as** makes a new view from what you see, and leaves the original as it was. It is how you keep your own version of a system view.
- **Revert** goes back to the saved version. **⌘S** (Ctrl+S) saves.
- Leaving a view with unsaved changes asks first.

**Manage views** lists every view of this data: drag to reorder, **Open this one first** to choose the view that opens by default, **Rename**, **Delete**, and **Make shared** or **Make personal**, which keeps the view's place on any board that shows it. Administrators may also **Publish as a system view**.

**When someone else saved first**, saving offers **Take theirs**, **Keep mine** or **Save my copy**; your changes are kept until you choose. **When a save got no answer**, the workbench says the outcome is unknown and offers **Try again**, which is safe because the same request cannot take effect twice, or **Abandon**, which keeps your changes on screen, unsaved.

## When something goes wrong

- A query that fails says so where the result would be, with a way to try again; the last result stays on screen.
- A view that cannot run as saved, because a field it uses is gone or a condition is no longer allowed, says what needs fixing and offers to remove what is unavailable.
