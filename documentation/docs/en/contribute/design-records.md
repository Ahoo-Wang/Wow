---
title: Design Records
description: Where Wow's design records live, how the decision and review ledgers are kept, and when a change needs a record of its own.
---

# Design Records

A design record explains why the code is shaped the way it is: the problem, the constraints, the compatibility boundary, the decision and the alternatives rejected. The site says how to use Wow; the records say why it works that way, for the people who change it.

## Wow 10

Wow 10 was redesigned as a whole, and its records live together in [`documentation/designs/`](https://github.com/Ahoo-Wang/Wow/tree/main/documentation/designs), in Chinese:

| Record | Holds |
| --- | --- |
| `README.md` | The index and the conventions |
| `00-architecture.md` | The stance, the value, the principles and the overall architecture: read it first |
| `01`–`08` | One topic each: the programming model, the write path, the log and snapshots, subscriptions and waits, the read side and the contract, modules, migration, the wire formats |
| `09-capability-parity.md` | Where every 9.x capability went |
| `decisions.md` | The decision ledger |
| `reviews.md` | The review ledger |
| `roadmap.md`, `progress.md` | The milestones and work packages, and where the work stands |
| `YYYY-MM-DD-<topic>-design.md` | A dated record of one decision's design, implementation or review, such as the subscription side's |

`00` keeps to conclusions and reasons; detail lives in the topic records, and where they disagree the topic record wins and `00` is fixed in the same change. Each record states its date, baseline and status: draft, adopted, in progress, implemented or superseded.

## The decision ledger

Every Wow 10 decision is recorded once, in `decisions.md`, under a number, `D6` say, that code comments, records and pull requests cite instead of repeating the argument. A decision is **settled** (with its date and who decided), **open** (with a recommendation), **awaiting evidence** (waiting on a spike's result) or **revoked** (naming what replaced it). Work that depends on an assumption awaiting evidence does not start before the evidence is in. When a decision is settled, the ledger is updated first, then the records it affects.

## The review ledger

`reviews.md` is the one record of what reviews found and is still open. Each finding has a number, a severity (P0 to P3), its place in the code, what happens and when, what is left to do and a status: recorded, in progress, on hold, to be measured, or partly done with what remains. A finding is recorded before it is assigned, and a fix's pull request names its number. A finding that is dealt with (fixed, decided against, or moved to the roadmap's 10.x list) leaves the ledger; git keeps what it said.

## When a change needs a record

- A decision that spans JVM modules or the repository, or changes a public contract, a wire or storage format, or a module boundary, is recorded: a decision in the ledger, its reasoning in the topic record it belongs to.
- A design for a single TypeScript package or application stays beside it, in its `docs/design/`.
- A plan, review notes or scratch evidence for one change stays out of the repository: the pull request holds it.

[Documentation Governance](./documentation) gives the home of every other kind of document.
