---
title: Ask with an Agent
description: Let an AI agent answer business questions from a running Wow service with the wow-data-query Skill, what it reads, what it never does, and what its answers come with.
---

# Ask with an Agent

The `wow-data-query` Agent Skill answers a business question, "how many orders shipped late last week, by warehouse?", from a running Wow service. It reads the service's query models through their read-only routes and answers with the numbers, the query that produced them, and what could make them misleading. It never writes code and never writes data. [Agent Skills](../start/skills) says how to install it.

## What it reads

The three query models of [Data in a Wow System](./): an aggregate's snapshots for what things are now, its event streams for what happened and when, and a dataset for questions across aggregates or services. Before any query it reads the model's capability descriptor, the `GET` on the same route, and keeps to what the descriptor admits: a field it does not list does not exist for the agent, and an operator or a grouping it does not grant is not tried. The same question can be fine on one backend and refused on another; the service's descriptor decides.

## What it never does

- **Query production without your consent.** It works on a development or staging service by default. A production URL found in configuration, docs or history is not consent; you give it in the conversation.
- **Handle your credentials.** It reads them from the environment, an exported token or a configured CLI, never asks for a password or a token in chat, and never prints one. It reads as the caller those credentials are, so only that caller's tenant, owner and space.
- **Write.** Only the query routes and the state reads: never a command route, the snapshot rebuild, the operator routes or anything else that writes.
- **Get round protection.** A masked field stays masked, a field that cannot be compared is not filtered or sorted, and no protected field is grouped. It never tries to recover a masked value through filters, ranges or search.
- **Join by hand.** A question across aggregates is answered by a dataset that serves it, or not at all.

## What an answer comes with

- The direct answer, with its units, the period it covers and the time zone of any grouping by date. Days are UTC unless the query names a zone, so the agent asks for or infers the business's zone and says which it used.
- The caveats that change the meaning:
  - a count or a total marked inexact is a lower bound;
  - approximate metrics are estimates;
  - a limit or a top N may have cut the rows;
  - deleted aggregates are left out unless the query selects them;
  - snapshots lag commits by moments, and a dataset lags its sources.
- The evidence: the environment, the descriptor's version and the exact request it sent, so you can run it again.

## When it is refused

A refused query names its rule in each violation; the agent fixes the query within the descriptor and tries once more. 429 `wow.query.busy` waits for `Retry-After`; 409 `wow.query.dataset-unavailable` means the dataset is being revised; 504 `wow.query.timeout` means narrowing the query. A second refusal, a server error or a result that contradicts the data is a question for the service's developers, and the `wow-develop` Skill diagnoses it.

Without a reachable service, the agent still gives the request bodies and the commands that would send them, and says that no number was read. For a view people open again and again rather than a one-off answer, define one for [The Workbench](./workbench).
