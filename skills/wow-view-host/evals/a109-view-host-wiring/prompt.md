---
name: a109-view-host-wiring
tags: [activation, trigger, wiring]
runs: 3
max_turns: 8
allowed_tools: [Read, Glob, Grep, Skill]
---

Our app has three pages that each create their own ViewEngine from @ahoo-wang/wow-view-engine and sync ?view= by hand; move it to one engine with ViewHost, react-router and per-resource routes.
