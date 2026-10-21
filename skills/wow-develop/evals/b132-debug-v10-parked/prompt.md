---
name: b132-debug-v10-parked
tags: [behavior, diagnose, read-only]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

In our Wow V10 service the ops console lists order 42 as parked for `OrderNotificationReactor.onOrderPaid` with reason `SmtpException: connection refused`. Its later events are not handled either. What happened and what should we do? Do not change anything.
