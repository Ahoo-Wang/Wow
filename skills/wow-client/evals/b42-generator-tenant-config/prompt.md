---
name: b42-generator-tenant-config
tags: [behavior, generation, config, tenant]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

Our Wow V10 billing service's `Invoice` state has `amount: BigDecimal` and `sequence: Long`, and its `IssueInvoice` command takes `amount`. The browser shows wrong cents on some invoices and the sequence numbers end in 00. Using the clients wow-generator generates from the service's Manifest, fix how the app sends `IssueInvoice` and displays the amounts and sequences. Give the code and the commands in your answer.
