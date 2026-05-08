---
layout: home
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
      text: Get Started
      link: /guide/getting-started
    - theme: alt
      text: Introduction
      link: /guide/introduction
    - theme: alt
      text: GitHub
      link: https://github.com/Ahoo-Wang/Wow
    - theme: alt
      text: Gitee
      link: https://gitee.com/AhooWang/Wow
features:
- title: Domain Model as a Service
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m12 3-1.912 5.813a2 2 0 0 1-1.275 1.275L3 12l5.813 1.912a2 2 0 0 1 1.275 1.275L12 21l1.912-5.813a2 2 0 0 1 1.275-1.275L21 12l-5.813-1.912a2 2 0 0 1-1.275-1.275L12 3Z"/></svg>'
  details: Just write the domain model, and Wow automatically prepares the OpenAPI interface for you. Because it's efficient, CRUD is worth DDD.
  link: /guide/modeling
- title: Test Suite
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 3h6l3 7-6 11-6-11 3-7Z"/><path d="M9 3 3 10h18L15 3"/></svg>'
  details: Test suite based on Given->When->Expect pattern, helping developers easily achieve over 80% test coverage and ensure high-quality application delivery
  link: /guide/test-suite
- title: High Performance
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M13 2 3 14h9l-1 8 10-12h-9l1-8z"/></svg>'
  details: Aggregate+EventSourcing, CQRS architecture, write operations only require AppendOnly operations, read operations utilize query-oriented search engines
  link: /guide/perf-test
- title: Scalability
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 3h6v6"/><path d="M9 21H3v-6"/><path d="m21 3-7 7"/><path d="m3 21 7-7"/></svg>'
  details: No need to consider database relationship patterns, sharding rules, etc., code unchanged, easy horizontal scaling
  link: /guide/introduction.html#performance-and-scalability
- title: Distributed Transactions (Saga)
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"/><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/></svg>'
  details: Carefully manage transaction processes between complex multi-services using Saga orchestration pattern to reduce the complexity of distributed transactions
  link: /guide/saga
- title: Event Compensation Automation
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/><path d="M12 7v5l4 2"/></svg>'
  details: Provide visual event compensation console and automatic compensation mechanism to ensure eventual consistency of system data
  link: /guide/event-compensation
- title: End-to-End Observability
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/></svg>'
  details: Integrate OpenTelemetry to achieve end-to-end observability of the system, helping monitoring and debugging, solving the system complexity that may be caused by CQRS pattern
  link: /guide/extensions/opentelemetry
- title: Reactive Programming
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 12h-4l-3 9L9 3l-3 9H2"/></svg>'
  details: Based on reactive programming model to make the system more adaptable to asynchronous and concurrent operations, improve overall response performance. Through asynchronous message passing, system components communicate in a non-blocking way, reduce system overhead, enhance system resilience, ensure immediate responsiveness under high load and low load.
- title: Business Intelligence
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 3v18h18"/><path d="m19 9-5 5-4-4-3 3"/></svg>'
  details: Provide richer data sources with clear business semantics (including state events and commands). With extremely low ETL cost, help real-time data analysis and operation audit, provide strong support for business decision-making.
  link: /guide/bi
---
