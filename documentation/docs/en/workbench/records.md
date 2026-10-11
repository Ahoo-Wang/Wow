---
title: Record Views
description: Filter, sort, choose columns, select, open a record, summarise and export in a record view.
---

# Record Views

A record view lists records, one row each. You decide which records, in which order, with which columns, and how they are laid out.

## Filter

**Filter** in the title bar opens the conditions. A saved view opens with them folded away, a new one with them open; a dot on the button says there are edits not applied yet.

- **Simple** lists one condition per field: pick a field, then its operator and value. A field offers the operators that make sense for it — a date offers ranges such as "last 7 days", a status offers "is any of".
- **Advanced** combines conditions in groups: **All of**, **Any of** or **None of**, nested as deep as you need (**Add a group**).
- Edits wait until you press **Apply**; the editor counts what is **not applied yet**. **Clear** removes every condition.

The conditions line above the result always says what the result is filtered by. A view opened from another one adds a **Back to …** line saying where it came from.

## Read the result

- **Layout** switches between **Table**, **Cards** and, where the view has stages, **Kanban** (one column per stage).
- **Columns** chooses the columns and their order. Drag a column header's right edge to widen it.
- **Sort** by pressing a column header: ascending, descending, then back. Hold Shift to sort by several columns.
- **Summaries** under the table show a sum, average, minimum, maximum or count for a column, both for **This page** and for **All rows** that match.
- **Paging** moves through the result page by page.
- An empty result says why: no record matches the conditions, or there are no records yet, and offers to change or clear the conditions.

## Open a record

Press the empty part of a row — not its checkbox, a link or an action — to open its **Record detail** at the side: every field of the record, grouped, including long text and what the list does not show. With the keyboard, move through the rows with the arrow keys and press Enter or Space. Your application may add its own sections there.

## Select and act

Tick rows to select them; Shift-click selects a range. **Actions** offers what your application lets you do with the selected records — for example retrying failed tasks. An action that changes data may ask you to confirm or fill in a form first, and says how it went for each record.

## Export

**Export** writes the result as a CSV file: the **Selected** rows, or **All** rows under the current conditions, with the conditions noted. Cells that start like a spreadsheet formula (`=`, `+`, `-`, `@`) are written so a spreadsheet does not run them.
