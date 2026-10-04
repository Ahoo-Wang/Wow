---
name: b73-view-host-wow-view-store
tags: [behavior, storage, security]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

Move our saved views to WowViewStore. Users sign in through CoSec; only the 'ops-lead' role may publish shared views. Pass the tenant and user id into the store options.
