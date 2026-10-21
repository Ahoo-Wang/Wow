---
name: a81-generator-missing-aggregate
tags: [activation, trigger, aggregate-discovery]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

Our generated OrderCommandClient has no method for the new CancelOrder command the Wow V10 ordering service shipped last week. Explain where the generated clients come from and how to get the method.
