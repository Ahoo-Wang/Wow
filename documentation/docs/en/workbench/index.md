---
title: Using the Workbench
description: For the people who read and shape data in an application built on the Wow view engine — record views, analysis views, dashboards, and saved views.
---

# Using the Workbench

Many business applications built on Wow show their data through the **workbench**: a list of saved views on the left, and the open view on the right. These pages are for the people who use it — operations, analysts, support — not for the developers who build it. Nothing here needs code.

What you can do depends on how your application set it up: which data it offers, which fields can be filtered or summed, and who may share views. The names below are the workbench's default wording; your application may use its own.

## Three kinds of view

| Kind | Answers | Read |
| --- | --- | --- |
| **Record view** | Which records match, one row each: filter, sort, pick columns, open a record, export | [Record views](./records) |
| **Analysis view** | How many, how much, by what: metrics by dimension, as a table or a chart | [Analysis views](./analysis) |
| **Dashboard** | Several views and charts on one page, filtered together | [Dashboards](./dashboards) |

Every view can be saved, shared and set as the one that opens first: [Saving and managing views](./views).

## The screen

- **The view list** on the left groups views as **My views**, **Shared views** and **System views**. **New view** starts one. **Hide the view list** gives the view the whole width, and **Fill the screen** hides everything around it.
- **The title bar** names the open view and says whether it is saved: **Not saved yet** for a new view, **Edited** after a change, **Only the look changed** when only the display changed. **Save** sits beside the name; **More view actions** holds the rest: **Save as**, **Make shared** or **Make personal**, and **Copy link** where the page's address names the view.
- **The conditions line** says what is showing now, with a search box at its end when the data can be searched. A search runs when you press Enter. Press **/** anywhere outside a field to jump to it.
- **Refresh** reads the data again. Its arrow sets **Auto refresh**, and the result says when it was last updated.

## When something goes wrong

- A query that fails says so where the result would be, with a way to try again; the last result stays on screen.
- A view that cannot run as saved — a field it uses is gone, or a condition is no longer allowed — says what needs fixing, and offers to remove what is unavailable.
- If you leave a view with unsaved changes, the workbench asks first: **Stay** or **Leave**.
