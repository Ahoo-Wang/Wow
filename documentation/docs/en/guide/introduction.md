# Introduction

<center>
  <img width="150" src="/images/logo.svg" alt="Wow: Modern Reactive CQRS Architecture Microservice Development Framework Based on DDD and EventSourcing"/>
</center>

:::info Keywords
**<Badge type="tip" text="Domain Driven" />**
**<Badge type="tip" text="Event Driven" />**
**<Badge type="tip" text="Test Driven" />**
**<Badge type="tip" text="Declarative Design" />**
**<Badge type="tip" text="Reactive Programming" />**
**<Badge type="tip" text="CQRS" />**
**<Badge type="tip" text="Event Sourcing" />**
:::

_Wow_ is a modern reactive _CQRS_ microservice development framework based on Domain-Driven Design and Event Sourcing, validated in production environments for many years.

It aims to help developers build modern, high-performance, and easy-to-maintain microservice applications, fully leveraging the advantages of Domain-Driven Design and Event Sourcing patterns while reducing application complexity and practice costs.

:::tip
It is worth mentioning that Domain-Driven Design and Event Sourcing are not exclusive to microservice architecture. The _Wow_ framework is not only suitable for microservice development, but can also be used to build monolithic applications based on Domain-Driven Design.
:::

## Background

As business grows and complexity increases, traditional architecture and development methods gradually reveal bottlenecks. *Domain-Driven Design* and *Event Sourcing* excel in improving system design flexibility and maintainability, but in practice often face challenges of complexity and learning curves.

The goal of the _Wow_ framework is to integrate Domain-Driven Design and Event Sourcing ideas into microservice application development in a simple and easy-to-use way, reducing developers' learning costs and improving development efficiency.
By providing modern reactive _CQRS_ architecture and related components, the _Wow_ framework aims to allow developers to focus more on business logic implementation without worrying too much about underlying technical details.

After years of practice and continuous evolution, the _Wow_ framework has been validated in production environments, accumulating rich experience. These experiences and feedbacks not only enrich the framework's functionality and performance, but also provide valuable guidance for continuous improvement and optimization.

## What does the _Wow_ framework mean for developers?

I once warned my team: if we rely too much on *data-driven design* and ignore *domain-driven design*, we will eventually become *CRUD* engineers.

:::warning
The competitiveness and replaceability of *CRUD* engineers can be imagined, which may be why there is the _35_-year-old effect. Companies obviously prefer to recruit cheaper _25_-year-old *CRUD* engineers without too many life entanglements.
:::

### Business Value

The core value of software systems is reflected in business value. R&D personnel should not only focus on technical implementation, but should pay more attention to the realization of business value.
The benefits are obvious. After developing a business system, you will become a business expert, even more professional than the domain experts you work with, because you need to insight into business details.

Using the _Wow_ framework means you focus on domain model design, exploring the business domain with business experts, rather than technical implementation.
You only need to write the domain model to complete service development. The _Wow_ framework automatically prepares the _OpenAPI_ interface for you.

> In the book "Implementing Domain-Driven Design", author Vaughn Vernon mentions: only the core domain is worth investing in Domain-Driven Design,
> But if you use the _Wow_ framework, you will find that due to low development costs and fast development efficiency, even secondary supporting subdomains are worth _DDD_.

### Performance and Scalability

As business grows, you need to start thinking about system performance and scalability issues.
In traditional architecture, this involves complex issues such as database relationship patterns and sharding rules, and you also need to handle cross-shard transaction problems caused by database sharding.
At this time, you have to modify your business code to adapt to the horizontally split database architecture.

However, if you choose to use the _Wow_ framework, you no longer need to pay too much attention to database relationship patterns, sharding rules, etc. Your business code does not need to change, and the system can easily achieve horizontal scaling.

You can learn more about [Wow framework performance](/en/guide/perf-test) here.

### Read-Write Separation and Sync Delay

Read-write separation is a very common performance optimization architecture pattern.
However, sync delay problems often accompany it. After transaction execution succeeds and write database falls successfully, but read database sync delays, users refresh the page and cannot get the latest data, thus affecting user experience. For example:

- User initiates order transaction, write database executes successfully, but due to some reason, read database sync delays, user refreshes page and finds order not successfully created.
- Merchant edits product, syncs to _Elasticsearch_ index database, but due to some reason, sync delays, causing merchant to refresh page and not find the product in search.

Usually, the simplest method is adopted: wait 1 second and refresh the page.
Although this method can solve most data sync delay problems, it is not efficient enough.
Because in most cases, sync is completed within 100 milliseconds, and the remaining 900 milliseconds become *waste*.
However, sometimes 1 second cannot complete sync, which leads to **invalid** data obtained by users.

Using the _Wow_ framework, you can wait for the _PROJECTED_ signal to complete, then return the result to the user, handling data sync delay problems in a more elegant and efficient way.

Learn more about [command waiting strategies](/en/guide/command-gateway#waiting-strategies).

### Engineering Quality

![Test Coverage](/images/getting-started/test-coverage.png)

*Unit testing* is an important means to ensure code quality and meet expected business requirements, but in traditional architecture, unit testing is often a quite difficult task because you need to consider database connections, transaction management, data cleanup, etc.

Using the _Wow_ framework, you will find that the test suite based on the _Given->When->Expect_ pattern makes unit testing extremely simple.
You only need to focus on whether the domain model meets expectations, without worrying about database connections and other issues.

:::tip
In actual applications, we set the lower threshold for domain model unit test coverage to **85%**, which is also easily achievable.
Without deliberate requirements, developers even consciously raise the coverage to **95%**.
Therefore, each code commit becomes relaxed and comfortable, because you are sure your code has been fully tested and truly benefits from unit testing.
:::

In projects of the same R&D level, our testing team found in system _API_ testing that Wow framework-based projects have only **1/3** the number of _BUGs_ compared to traditional architecture projects.

You can learn more about [Wow unit test suite](/en/guide/test-suite) here.

## What does the _Wow_ framework mean for enterprises?

### Business Intelligence

*Business Intelligence* is key support for enterprise decision-making, and data is the raw material for Business Intelligence analysis. The richer and more valuable the business data, the more accurate the Business Intelligence analysis results, and the more reliable the decisions.

Significantly different from traditional architecture, _Wow_ provides real-time aggregate root state events (`StateEvent`) and aggregate commands (`Command`) as data sources for data analysis, while greatly reducing the difficulty of real-time _ETL_ (`Extract`, `Transform`, `Load`).

<center>

![Event Sourcing VS Traditional Architecture](/images/eventstore/eventsourcing.svg)
</center>

In traditional architecture, implementing real-time _ETL_ usually requires cumbersome processes, including `DB->CDC->Process->DB`, while in the _Wow_ framework, it can be completed with a simple _SQL_ script.

![Business Intelligence](/images/bi/bi.svg)

In addition, in traditional architecture, using _CDC_ (`MySql Binlog`) data only records data changes, lacking clear business semantics. When conducting business analysis, it is necessary to infer business semantics based on data state changes, which often requires a lot of data processing.
In contrast, the _Wow_ framework directly provides aggregate root state events and aggregate commands as data sources for analysis, greatly reducing the difficulty of data processing.

The real-time sync mechanism provided by _Wow_ syncs data in real-time to the data warehouse (_ClickHouse_), providing great convenience for real-time data analysis. This method provides strong support for Business Intelligence, building a real-time data analysis system, enabling decision-making based on timely and accurate information.

You can learn more about [Wow Business Intelligence](/en/guide/bi) here.

### Operation Audit

*Operation Audit* is an important part of ensuring security and compliance in enterprises, and is also a key means of monitoring and tracking system operations. The _Wow_ framework brings significant advantages to enterprises in this regard.

By recording aggregate commands (`Command`) as the data source for operation audit, the _Wow_ framework can track various operations in the system in detail.
These records not only include the content of the operations themselves, but also cover the side effects triggered by the operations (_domain events_), providing a more comprehensive and accurate data foundation for auditing.

Compared to traditional audit methods, the data sources for operation audit in the _Wow_ framework have clearer business semantics and clear domain events generated after operations.

In addition, the real-time data sync mechanism provided by the Wow framework also brings convenience to operation audit, ensuring the timeliness and consistency of audit data.

Learn more about [Wow Operation Audit](/en/guide/bi#aggregate-commands).

## What features does _Wow_ provide?

In the *Wow* framework, there are many key features, including but not limited to:

- Command Gateway: In _CQRS_ mode, command response is usually a simple confirmation, informing the client that the write operation has been successfully processed (aggregate root processing stage), at this time the client refreshes the page and is likely unable to get the data (projection of query model to query database has not been completed). This is very unfriendly to the client.
To solve this problem, *Wow* provides multiple command waiting strategies, such as `PROJECTED` signal can wait for projection completion.
- Event Driven: Provides event bus to handle event publishing and subscription, helping to achieve loose coupling communication between components, and projection of query models.
- Aggregate Root Modeling: Uses aggregate root to organize and manage domain models, helping developers better divide and manage business logic.
- Event Sourcing: Restores and tracks application state changes by recording and replaying events, achieving powerful data history recording and audit functions.
- CQRS: Separates read and write operations, improving system performance and flexibility.
- Reactive Programming: Based on reactive programming model to make the system more adaptable to asynchronous and concurrent operations, improve overall response performance. Through asynchronous message passing, system components communicate in a non-blocking way, reduce system overhead, enhance system resilience, ensure immediate responsiveness under high load and low load.
- Distributed Transactions: Uses *Saga* orchestration pattern to carefully manage transaction processes between complex multi-services, reducing the complexity of distributed transactions
- Test Driven: Provides test suite, can easily achieve *80%* or more test coverage, helping developers build high-quality applications.
- _Spring WebFlux_ Integration: Automatically registers command route handler functions, focusing on domain model development.
- _Spring Boot_ Integration: Perfect integration with _Spring Boot_, simplifies component assembly, accelerates microservice development.
- Observability: Compared to traditional single mode, *CQRS* mode may increase debugging complexity in some aspects.
This is mainly because _CQRS_ introduces a design that separates write operations (commands) and read operations (queries), which may make the system more dispersed and complex.
To solve this problem, *Wow* integrates *OpenTelemetry* to achieve end-to-end observability of the system, helping with monitoring and debugging.

<p align="center" style="text-align:center">
  <img width="95%" src="/images/Features.png" alt="Wow-Features"/>
</p>

## Architecture Diagram

<p align="center" style="text-align:center">
  <img width="95%" src="/images/Architecture.svg" alt="Wow-Architecture"/>
</p>

### Command Processing Propagation Chain

<p align="center" style="text-align:center;">
  <img  width="95%" src="/images/wait/WaitingForChain.svg" alt="Wow-WaitingForChain"/>
</p>
