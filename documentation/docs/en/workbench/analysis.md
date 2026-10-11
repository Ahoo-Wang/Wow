---
title: Analysis Views
description: Ask how many and how much, by what — metrics, dimensions, ranges and charts in an analysis view.
---

# Analysis Views

An analysis view answers a question such as "orders and revenue by warehouse, last month". It reads one row per group, as a table or as a chart.

## Ask the question

**Analysis** in the title bar opens the question. A saved view opens with it folded away, a new one with it open. The question has five parts, in the order they depend on each other:

| Part | What you set |
| --- | --- |
| **Expand** | Count the entries inside each record instead of the records, such as the lines of each order |
| **Metrics** | What to measure: a count, a sum, an average, a minimum or maximum, a percentile, a distinct count. **Formula** combines two fields (amount − cost), **Derived metric** combines metrics (revenue ÷ orders), and **Time between two moments** measures a duration |
| **Dimensions** | How to group: by a field's values, by a date (by year, quarter, month, week, day, hour…), or by a cycle (weekday, hour of day, day of month, month of year) |
| **Result** | Which groups to keep (only where a metric meets a condition), how to order them, and how many to show |
| **Range** | Which records take part — the same conditions as a record view's filter |

A change that would leave the question without something it needs — removing the last metric, collapsing an expansion that a metric used — says what it took out and offers **Undo**. Unless you turn it off, the result updates as you edit the question.

## Show the result

The result starts as a table. **Visualize**, on the result's toolbar, opens the chart types at the side — bar, line, area, combination, pie, heatmap, scatter, funnel, single number, waterfall, treemap, and more. Those that can draw this result come first; the others are greyed out and say what they are missing. A chosen type has its own options.

When there are more groups than the view shows, it says so: "Showing N groups". Narrow the range or keep fewer groups to see the rest.

## Follow up on a group

Press a bar, a slice or a row to open its follow-up menu:

- **See these records** opens the records behind that group;
- **Split this group by…** breaks it down by another dimension;
- **Only this group** narrows the analysis to it.

On a time axis the same menu acts on the period pressed. A view opened this way has a **Back to …** line.

## Export

**Export data…** writes the groups as a CSV file; a chart can also be exported as an image (**Export image (PNG)**, or SVG).
