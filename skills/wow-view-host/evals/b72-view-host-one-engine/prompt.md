---
name: b72-view-host-one-engine
tags: [behavior, positive, wiring]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

Each page builds useCreateViewEngine(createEngine({ locale })) and rebuilds it on a language switch; the router sync for ?view= is hand-written. Clean this up. Give the code and the commands in your answer.
