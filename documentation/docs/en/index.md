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
  details: Just write the domain model, and Wow automatically prepares the OpenAPI interface for you. Because it's efficient, CRUD is worth DDD.
  link: /guide/modeling
- title: Test Suite
  details: Test suite based on Given->When->Expect pattern, helping developers easily achieve over 80% test coverage and ensure high-quality application delivery
  link: /guide/test-suite
- title: High Performance
  details: Aggregate+EventSourcing, CQRS architecture, write operations only require AppendOnly operations, read operations utilize query-oriented search engines
  link: /guide/perf-test
- title: Scalability
  details: No need to consider database relationship patterns, sharding rules, etc., code unchanged, easy horizontal scaling
  link: /guide/introduction.html#performance-and-scalability
- title: Distributed Transactions (Saga)
  details: Carefully manage transaction processes between complex multi-services using Saga orchestration pattern to reduce the complexity of distributed transactions
  link: /guide/saga
- title: Event Compensation Automation
  details: Provide visual event compensation console and automatic compensation mechanism to ensure eventual consistency of system data
  link: /guide/event-compensation
- title: End-to-End Observability
  details: Integrate OpenTelemetry to achieve end-to-end observability of the system, helping monitoring and debugging, solving the system complexity that may be caused by CQRS pattern
  link: /guide/extensions/opentelemetry
- title: Reactive Programming
  details: Based on reactive programming model to make the system more adaptable to asynchronous and concurrent operations, improve overall response performance. Through asynchronous message passing, system components communicate in a non-blocking way, reduce system overhead, enhance system resilience, ensure immediate responsiveness under high load and low load.
- title: Business Intelligence
  details: Provide richer data sources with clear business semantics (including state events and commands). With extremely low ETL cost, help real-time data analysis and operation audit, provide strong support for business decision-making.
  link: /guide/bi
---