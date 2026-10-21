---
layout: home
kaicodeAward: true
title: Domain Model as a Service
hero:
  name: "Wow"
  text: "Domain Model as a Service"
  tagline: "Write the domain model; get a system you can run in production: commands, events, queries, a typed contract and replayable read models."
  image:
    src: /images/logo.svg
    alt: Wow
  actions:
    - theme: brand
      text: Quick Start
      link: /guide/v10/quickstart
    - theme: alt
      text: Write a Service
      link: /guide/v10/
    - theme: alt
      text: GitHub
      link: https://github.com/Ahoo-Wang/Wow
features:
- title: Only the domain model
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m12 3-1.912 5.813a2 2 0 0 1-1.275 1.275L3 12l5.813 1.912a2 2 0 0 1 1.275 1.275L12 21l1.912-5.813a2 2 0 0 1 1.275-1.275L21 12l-5.813-1.912a2 2 0 0 1-1.275-1.275L12 3Z"/></svg>'
  details: "Commands decide, events evolve state; the compiler binds them at build time, with no reflection and no infrastructure code."
  link: /guide/v10/programming-model
- title: The log is the bus
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M8 6h13"/><path d="M8 12h13"/><path d="M8 18h13"/><path d="M3 6h.01"/><path d="M3 12h.01"/><path d="M3 18h.01"/></svg>'
  details: "One append is the only write. Subscriptions read the log with checkpoints, so no committed fact is ever lost on the way."
  link: /guide/v10/deployment
- title: One contract
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/></svg>'
  details: "The Manifest is the single source: REST routes, OpenAPI, the TypeScript client and the views are derived from it."
  link: /guide/v10/http-api
- title: Queries with no code
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5"/></svg>'
  details: "The latest state of every aggregate is queryable as it is, and every read model can be rebuilt from the start."
  link: /guide/v10/query
- title: Tests in the model's terms
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 3h6l3 7-6 11-6-11 3-7Z"/><path d="M9 3 3 10h18L15 3"/></svg>'
  details: "Given events, when a command, expect events and state: aggregates, sagas and projections test without a server."
  link: /guide/testing
- title: PostgreSQL or MongoDB
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M3 5v14a9 3 0 0 0 18 0V5"/><path d="M3 12a9 3 0 0 0 18 0"/></svg>'
  details: "Both are first-class backends; Kafka carries what crosses contexts, ClickHouse serves the datasets."
  link: /guide/v10/deployment
---

## Recognition

Wow received the [KaiCode’26 Excellent Award](https://www.kaicode.org/2026.html). The official results highlighted its modular DDD/CQRS design, disciplined multi-reviewer code review, Testcontainers-based integration testing, enforced test coverage thresholds, Detekt static analysis, bilingual documentation, and long history of semantically versioned releases on Maven Central.
