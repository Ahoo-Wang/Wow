---
layout: home
kaicodeAward: true
title: Modern Reactive CQRS Architecture Microservice Development Framework Based on DDD & EventSourcing
hero:
  name: "Wow"
  text: "Domain Model as a Service"
  tagline: "Modern Reactive CQRS Architecture Microservice Development Framework Based on DDD & EventSourcing"
  image:
    src: /images/logo.svg
    alt: Wow
  actions:
    - theme: brand
      text: 30-Minute Quickstart
      link: /guide/getting-started
    - theme: alt
      text: Why Wow
      link: /guide/introduction
    - theme: alt
      text: Development Guide
      link: /guide/
    - theme: alt
      text: GitHub
      link: https://github.com/Ahoo-Wang/Wow
features:
- title: Domain Model as a Service
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m12 3-1.912 5.813a2 2 0 0 1-1.275 1.275L3 12l5.813 1.912a2 2 0 0 1 1.275 1.275L12 21l1.912-5.813a2 2 0 0 1 1.275-1.275L21 12l-5.813-1.912a2 2 0 0 1-1.275-1.275L12 3Z"/></svg>'
  details: Define commands, events, and state around the domain model; Wow generates OpenAPI metadata and wires the runtime pipeline with less infrastructure boilerplate.
  link: /guide/domain/
- title: Test Suite
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 3h6l3 7-6 11-6-11 3-7Z"/><path d="M9 3 3 10h18L15 3"/></svg>'
  details: The Given->When->Expect suite verifies commands, events, and state directly; application gates still prove coverage and delivery quality.
  link: /guide/test-suite
- title: Reproducible Performance Baselines
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M13 2 3 14h9l-1 8 10-12h-9l1-8z"/></svg>'
  details: Purpose-specific JMH tasks keep performance claims tied to the current code, runtime environment, and result manifest instead of context-free historical numbers
  link: /guide/test-runtime#benchmarks-have-three-uses
- title: Scalability
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 3h6v6"/><path d="M9 21H3v-6"/><path d="m21 3-7 7"/><path d="m3 21 7-7"/></svg>'
  details: Aggregate, event-store, and messaging abstractions reduce domain coupling to storage topology; real scalability depends on hot spots, backends, and deployment evidence.
  link: /guide/introduction.html#_2-performance-and-scalability
- title: Distributed Transactions (Saga)
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"/><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/></svg>'
  details: Carefully manage transaction processes between complex multi-services using Saga orchestration pattern to reduce the complexity of distributed transactions
  link: /guide/event/saga
- title: Event Compensation Automation
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/><path d="M12 7v5l4 2"/></svg>'
  details: Record event-processing failures and provide retry, notification, and visual operations so applications can build verifiable recovery flows.
  link: /guide/event/compensation
- title: End-to-End Observability
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/></svg>'
  details: OpenTelemetry observation points cover commands, events, projections, sagas, and storage so applications can locate asynchronous stages and failures.
  link: /guide/extensions/opentelemetry
- title: Reactive Programming
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 12h-4l-3 9L9 3l-3 9H2"/></svg>'
  details: Core runtime paths compose non-blocking work with Reactor; throughput, latency, and resilience still require validation on real adapters, hardware, and load.
- title: Business Intelligence
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 3v18h18"/><path d="m19 9-5 5-4-4-3 3"/></svg>'
  details: State events and commands provide business-semantic data and generated ClickHouse sync scripts; applications still own latency and data-quality guarantees.
  link: /guide/bi
---

## From Value to First Success

Read the [Introduction](./guide/introduction.md) to evaluate Wow's value, fit, and adoption cost. When you are ready to build, follow the [30-Minute Quickstart](./guide/getting-started.md). Your first success requires a passing domain test, a real HTTP command, and verified versioned event-sourced state.

## Recognition

Wow received the [KaiCode’26 Excellent Award](https://www.kaicode.org/2026.html). The official results highlighted its modular DDD/CQRS design, disciplined multi-reviewer code review, Testcontainers-based integration testing, enforced test coverage thresholds, Detekt static analysis, bilingual documentation, and long history of semantically versioned releases on Maven Central.
