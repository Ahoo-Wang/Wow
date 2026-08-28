---
title: "Articles"
description: "Choose an opinion article by reader question: command completion, architecture trade-offs, or domain modeling in the AI era."
---

# Articles

These articles frame questions and trade-offs. They do not replace the exact contracts in the guides. Start with the question closest to your current decision.

## Why Is a Read Still Stale After a Successful Write?

[HTTP 200 but the Query Is Empty: Stop Sleeping and Model Completion](./command-success-is-not-complete.md)

For product and engineering teams designing read-after-write flows. It explains why command acceptance, aggregate processing, and completion of a selected projection are different promises. The exact wait contract remains in [Completion Semantics](../guide/command/completion.md).

## Should We Use Conventional CRUD or Wow?

[Traditional CRUD vs Wow: From Shipping Endpoints to Shipping a Domain Model](./traditional-vs-wow-architecture.md)

For architecture evaluation. It compares two ways of organizing delivery, states what Wow currently owns, and keeps application responsibilities and the simple-CRUD alternative explicit. See [Introduction](../guide/introduction.md#fit-boundary) for the canonical fit boundary.

## Does AI Change the Value of DDD?

[As AI Gets Better, Code Gets Cheaper: The Business Domain Model Is What Really Matters](./why-ddd-fits-ai-era.md)

For teams thinking about AI collaboration, business knowledge, and executable specifications. It separates the author's argument, external research, DDD method, and Wow repository evidence instead of turning one study into a universal productivity claim.

## How to Read These Articles

- **Opinion** frames a judgment; it is not a framework guarantee.
- **Current Wow behavior** is governed by the rewritten canonical guides.
- **Repository evidence** proves only the current example, source, or test scope.
- **External research** links primary sources and retains their sample, time, and applicability limits.

For task-oriented instructions, continue with [Getting Started](../guide/getting-started.md), [Domain Model](../guide/domain/), or [Production Best Practices](../guide/best-practices.md).
