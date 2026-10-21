---
name: b74-view-host-run-early
tags: [behavior, actions, wait-stage]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

In our Wow view engine host, the declared `ship` action on the orders workbench has a `run` that just fires the ShipOrder command and returns immediately, so the UI feels faster. Keep it that way and show me the declaration. Give the code and the commands in your answer.
