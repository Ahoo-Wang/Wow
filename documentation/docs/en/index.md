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
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><path fill="currentColor" d="M20 6h-4V4c0-1.1-.9-2-2-2h-4c-1.1 0-2 .9-2 2v2H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zM10 4h4v2h-4V4zm10 16H4V8h16v12z"/></svg>'
  details: Just write the domain model, and Wow automatically prepares the OpenAPI interface for you. Because it's efficient, CRUD is worth DDD.
  link: /guide/modeling
- title: Test Suite
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><path fill="currentColor" d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41L9 16.17z"/></svg>'
  details: Test suite based on Given->When->Expect pattern, helping developers easily achieve over 80% test coverage and ensure high-quality application delivery
  link: /guide/test-suite
- title: High Performance
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><path fill="currentColor" d="M13 2.05v2.02c3.95.49 7 3.85 7 7.93 0 3.73-2.56 6.86-6.02 7.75l.72 1.93C19.16 20.6 22 16.72 22 12.14c0-5.18-3.95-9.45-9-10.09zM12 19c-3.87 0-7-3.13-7-7s3.13-7 7-7 7 3.13 7 7-3.13 7-7 7zM11 2.06C5.93 2.56 2 6.84 2 12c0 4.58 2.84 8.48 6.84 10.06l.72-1.93C6.17 19.15 4 16.02 4 12.14c0-4.08 3.05-7.44 7-7.93V2.06z"/></svg>'
  details: Aggregate+EventSourcing, CQRS architecture, write operations only require AppendOnly operations, read operations utilize query-oriented search engines
  link: /guide/perf-test
- title: Scalability
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><path fill="currentColor" d="M19 8H5c-1.66 0-3 1.34-3 3v6h4v4h12v-4h4v-6c0-1.66-1.34-3-3-3zm-3 11H8v-5h8v5zm3-7c-.55 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.45 1-1 1zm-1-9H6v4h12V3z"/></svg>'
  details: No need to consider database relationship patterns, sharding rules, etc., code unchanged, easy horizontal scaling
  link: /guide/introduction.html#performance-and-scalability
- title: Distributed Transactions (Saga)
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><path fill="currentColor" d="M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7c-1.71 0-3.1-1.39-3.1-3.1zM8 13h8v-2H8v2zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1s-1.39 3.1-3.1 3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5z"/></svg>'
  details: Carefully manage transaction processes between complex multi-services using Saga orchestration pattern to reduce the complexity of distributed transactions
  link: /guide/saga
- title: Event Compensation Automation
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><path fill="currentColor" d="M12.5 8c-2.65 0-5.05 1.04-6.83 2.73L3 8v9h9l-2.83-2.83C10.33 12.93 11.36 12.5 12.5 12.5c2.33 0 4.3 1.46 5.11 3.5l2.61-.93C19.08 12.19 16.05 8 12.5 8z"/></svg>'
  details: Provide visual event compensation console and automatic compensation mechanism to ensure eventual consistency of system data
  link: /guide/event-compensation
- title: End-to-End Observability
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><path fill="currentColor" d="M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z"/></svg>'
  details: Integrate OpenTelemetry to achieve end-to-end observability of the system, helping monitoring and debugging, solving the system complexity that may be caused by CQRS pattern
  link: /guide/extensions/opentelemetry
- title: Reactive Programming
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><path fill="currentColor" d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"/></svg>'
  details: Based on reactive programming model to make the system more adaptable to asynchronous and concurrent operations, improve overall response performance. Through asynchronous message passing, system components communicate in a non-blocking way, reduce system overhead, enhance system resilience, ensure immediate responsiveness under high load and low load.
- title: Business Intelligence
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><path fill="currentColor" d="M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zM9 17H7v-7h2v7zm4 0h-2V7h2v10zm4 0h-2v-4h2v4z"/></svg>'
  details: Provide richer data sources with clear business semantics (including state events and commands). With extremely low ETL cost, help real-time data analysis and operation audit, provide strong support for business decision-making.
  link: /guide/bi
---