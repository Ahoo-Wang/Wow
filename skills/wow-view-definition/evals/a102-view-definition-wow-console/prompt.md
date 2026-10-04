---
name: a102-view-definition-wow-console
tags: [activation, trigger, wow-repository, drift]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

The MongoDB descriptor for the compensation console's execution_failed snapshot no longer grants CONTAINS on the error message; revise the console's view definitions under compensation/dashboard/src/views to fit it.
