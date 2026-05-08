# 文档站点优化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 全面优化 Wow 框架文档站点，修复基础设施问题、补充 SEO 元数据、提升视觉体验、优化导航结构。

**Architecture:** 纯 VitePress 站点优化，不涉及框架代码。修改集中在配置文件、主题样式、首页和内容页 frontmatter。

**Tech Stack:** VitePress 1.6.x, TypeScript 配置, CSS 主题变量

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `documentation/docs/en/guide/extensions/r2bdc.md` | Rename → `r2dbc.md` | R2DBC 扩展文档（英文） |
| `documentation/docs/zh/guide/extensions/r2bdc.md` | Rename → `r2dbc.md` | R2DBC 扩展文档（中文） |
| `documentation/docs/.vitepress/configs/sidebar.en.ts` | Modify | 修正 R2dbc 拼写、sidebar 折叠、去重 |
| `documentation/docs/.vitepress/configs/sidebar.zh.ts` | Modify | 同上 |
| `documentation/docs/.vitepress/configs/navbar.en.ts` | Modify | 精简 Resources 菜单 |
| `documentation/docs/.vitepress/configs/navbar.zh.ts` | Modify | 精简资源菜单 |
| `documentation/docs/.vitepress/configs/head.ts` | Modify | 移除 no-cache meta 标签 |
| `documentation/docs/.vitepress/theme/global.css` | Modify | 布局宽度、侧边栏宽度、hero 渐变色 |
| `documentation/docs/en/index.md` | Modify | SVG 图标替换 emoji |
| `documentation/docs/zh/index.md` | Modify | SVG 图标替换 emoji |
| `documentation/docs/en/guide/advanced/metrics.md` | Modify | promql → yaml |
| `documentation/docs/zh/guide/advanced/metrics.md` | Modify | promql → yaml |
| 所有 `guide/*.md`、`extensions/*.md`、`advanced/*.md` (EN+ZH，共 60 个文件) | Modify | 补充 frontmatter |

---

## Task 1: 基础设施 — R2DBC 文件重命名与拼写修正

**Files:**
- Rename: `documentation/docs/en/guide/extensions/r2bdc.md` → `r2dbc.md`
- Rename: `documentation/docs/zh/guide/extensions/r2bdc.md` → `r2dbc.md`
- Modify: `documentation/docs/.vitepress/configs/sidebar.en.ts`
- Modify: `documentation/docs/.vitepress/configs/sidebar.zh.ts`

- [ ] **Step 1: 重命名 EN 文件**

```bash
cd /Users/ahoo/work/ahoo-git/Wow
git mv documentation/docs/en/guide/extensions/r2bdc.md documentation/docs/en/guide/extensions/r2dbc.md
```

- [ ] **Step 2: 重命名 ZH 文件**

```bash
git mv documentation/docs/zh/guide/extensions/r2bdc.md documentation/docs/zh/guide/extensions/r2dbc.md
```

- [ ] **Step 3: 修正 sidebar.en.ts 中的拼写**

在 `sidebar.en.ts` 中将 `r2bdc` 替换为 `r2dbc`（两处：guide sidebar 和 reference sidebar）：
- 第 49 行: `{text: 'R2dbc', link: 'r2bdc'}` → `{text: 'R2DBC', link: 'r2dbc'}`

- [ ] **Step 4: 修正 sidebar.zh.ts 中的拼写**

同样替换两处 `r2bdc` → `r2dbc`：
- 第 49 行: `{text: 'R2bdc', link: 'r2bdc'}` → `{text: 'R2DBC', link: 'r2dbc'}`

- [ ] **Step 5: 构建验证**

```bash
cd documentation && npm run docs:build
```

Expected: 构建成功，无 404 警告

- [ ] **Step 6: 提交**

```bash
git add -A && git commit -m "fix(docs): rename r2bdc to r2dbc, fix typo in sidebar config"
```

---

## Task 2: 基础设施 — 移除 no-cache meta 标签

**Files:**
- Modify: `documentation/docs/.vitepress/configs/head.ts`

- [ ] **Step 1: 删除 no-cache 相关 meta 标签**

在 `head.ts` 中删除以下三行：
- `['meta', {'http-equiv': 'cache-control', content: 'no-cache, no-store, must-revalidate'}],`
- `['meta', {'http-equiv': 'pragma', content: 'no-cache'}],`
- `['meta', {'http-equiv': 'expires', content: '0'}],`

即删除第 27-29 行。

- [ ] **Step 2: 构建验证**

```bash
cd documentation && npm run docs:build
```

Expected: 构建成功

- [ ] **Step 3: 提交**

```bash
git add -A && git commit -m "fix(docs): remove no-cache meta tags for static site"
```

---

## Task 3: 基础设施 — 修复 promql 语法高亮警告

**Files:**
- Modify: `documentation/docs/en/guide/advanced/metrics.md` (第 159 行)
- Modify: `documentation/docs/zh/guide/advanced/metrics.md` (第 158 行)

- [ ] **Step 1: 修改 EN metrics.md**

将第 159 行的 ` ```promql` 改为 ` ```yaml`

- [ ] **Step 2: 修改 ZH metrics.md**

将第 158 行的 ` ```promql` 改为 ` ```yaml`

- [ ] **Step 3: 构建验证**

```bash
cd documentation && npm run docs:build
```

Expected: 构建成功，无 promql 警告

- [ ] **Step 4: 提交**

```bash
git add -A && git commit -m "fix(docs): replace promql with yaml code block to fix build warning"
```

---

## Task 4: SEO — 补充英文页面 frontmatter

**Files:**
- Modify: `documentation/docs/en/guide/` 下 17 个 .md 文件
- Modify: `documentation/docs/en/guide/extensions/` 下 9 个 .md 文件
- Modify: `documentation/docs/en/guide/advanced/` 下 7 个 .md 文件

共 33 个英文页面。

- [ ] **Step 1: 为每个英文页面在文件顶部添加 frontmatter**

每个页面添加格式：
```yaml
---
title: <页面标题>
description: <一句话描述页面内容>
---
```

页面标题和描述来自各页面第一个 `#` 标题和其后的简介段落。具体映射：

**guide/ 目录：**
| File | title | description |
|------|-------|-------------|
| introduction.md | Introduction | Wow is a modern reactive CQRS microservice development framework based on DDD and Event Sourcing, validated in production environments. |
| getting-started.md | Getting Started | Get started with the Wow framework using the project template to quickly create a DDD project. |
| modeling.md | Aggregate Root Modeling | Learn how to model aggregate roots in the Wow framework using the Aggregate Pattern. |
| eventstore.md | Event Store | The Event Store is a core component of the event sourcing architecture, responsible for persisting and retrieving domain event streams. |
| snapshot.md | Snapshot | Snapshot is an important optimization mechanism in event sourcing architecture that improves performance by saving checkpoints of aggregate root state. |
| command-gateway.md | Command Gateway | The command gateway is the core component for receiving and sending commands, handling idempotency, waiting strategies, and validation. |
| saga.md | Distributed Transactions (Saga) | Wow provides a stateless Saga implementation based on the Orchestration pattern for handling distributed transactions. |
| projection.md | Projection Processor | The projection processor transforms domain events into optimized read model projections in CQRS architecture. |
| query.md | Query Service | Query service provides query capabilities through wow-mongo and wow-elasticsearch modules. |
| open-api.md | OpenAPI | The Wow OpenAPI module provides API interfaces based on the OpenAPI specification. |
| test-suite.md | Test Suite | Test suite based on Given->When->Expect pattern, helping developers easily achieve over 80% test coverage. |
| bi.md | Business Intelligence | Wow provides real-time aggregate root state events and commands as data sources for business intelligence analysis. |
| event-compensation.md | Event Compensation | Event compensation handles and recovers from data inconsistencies caused by event processing failures in event-driven architecture. |
| best-practices.md | Best Practices | Architecture and development best practices for building applications with the Wow framework. |
| perf-test.md | Performance Testing | Performance benchmarks and test results for the Wow framework under different scenarios. |
| troubleshooting.md | Troubleshooting Guide | Diagnostics and solutions for common issues with the Wow framework. |
| migration.md | Migration Guide | Guide for migrating from traditional architecture to the Wow framework and upgrading between versions. |
| configuration.md | Configuration | Comprehensive configuration options through Spring Boot's configuration properties mechanism. |
| event-processor.md | Event Processor | The event processor handles domain events published by aggregates for cross-aggregate operations and read model updates. |

**extensions/ 目录：**
| File | title | description |
|------|-------|-------------|
| kafka.md | Kafka | Apache Kafka extension implementing CommandBus, DomainEventBus, and StateEventBus for production environments. |
| mongo.md | Mongo | MongoDB extension providing EventStore and SnapshotRepository for production environments. |
| r2dbc.md | R2DBC | R2DBC extension for reactive relational database event storage and snapshot storage. |
| redis.md | Redis | Redis extension providing high-performance and low-latency event store and snapshot storage. |
| elasticsearch.md | Elasticsearch | Elasticsearch extension for full-text search and complex query support. |
| opentelemetry.md | OpenTelemetry | OpenTelemetry integration for vendor-neutral distributed tracing and monitoring. |
| webflux.md | WebFlux | Spring WebFlux extension for automatic command route handler registration. |
| spring-boot-starter.md | Spring Boot Starter | Spring Boot Starter module integrating all Wow extensions with auto-configuration. |
| tck.md | Technology Compatibility Kit | TCK test cases for verifying that interface implementations conform to specifications. |

**advanced/ 目录：**
| File | title | description |
|------|-------|-------------|
| architecture.md | Architecture | Overall architecture design, core component relationships, and processing flows of the Wow framework. |
| id-generator.md | ID Generator | Message ID and aggregate root ID generation powered by CosId. |
| compiler.md | Wow Compiler | KSP compiler generating metadata for aggregate roots, commands, and domain events. |
| prepare-key.md | Prepare Key | Application-level key uniqueness mechanism for the EventSourcing architecture. |
| metrics.md | Metrics | Micrometer metrics collection for comprehensive performance monitoring and observability. |
| observability.md | Observability | End-to-end observability integration for the Wow framework. |
| aggregate-scheduler.md | Aggregate Scheduler | Dedicated Reactor Scheduler for each aggregate to control concurrent execution and resource allocation. |

- [ ] **Step 2: 构建验证**

```bash
cd documentation && npm run docs:build
```

Expected: 构建成功

- [ ] **Step 3: 提交**

```bash
git add -A && git commit -m "docs(seo): add frontmatter to all English guide pages"
```

---

## Task 5: SEO — 补充中文页面 frontmatter

**Files:**
- Modify: `documentation/docs/zh/guide/` 下 17 个 .md 文件
- Modify: `documentation/docs/zh/guide/extensions/` 下 9 个 .md 文件
- Modify: `documentation/docs/zh/guide/advanced/` 下 7 个 .md 文件

共 33 个中文页面。

- [ ] **Step 1: 为每个中文页面在文件顶部添加 frontmatter**

页面标题和描述映射：

**guide/ 目录：**
| File | title | description |
|------|-------|-------------|
| introduction.md | 简介 | Wow 是一个基于 DDD 和 Event Sourcing 的现代响应式 CQRS 微服务开发框架，经过多年生产环境验证。 |
| getting-started.md | 快速上手 | 使用 Wow 项目模板快速创建基于 DDD 的微服务项目。 |
| modeling.md | 聚合建模 | 使用聚合模式在 Wow 框架中进行聚合根建模。 |
| eventstore.md | 事件存储 | 事件存储是事件溯源架构的核心组件，负责持久化和检索领域事件流。 |
| snapshot.md | 快照 | 快照是事件溯源架构中的重要优化机制，通过保存聚合根状态检查点减少事件回放次数。 |
| command-gateway.md | 命令网关 | 命令网关是系统中接收和发送命令的核心组件，负责命令幂等性、等待策略和命令校验。 |
| saga.md | 分布式事务 (Saga) | Wow 提供基于编排模式的无状态 Saga 实现，用于处理分布式事务。 |
| projection.md | 投影处理器 | 投影处理器将领域事件转换为优化的读模型投影，是 CQRS 架构的核心组件。 |
| query.md | 查询服务 | 通过 wow-mongo 和 wow-elasticsearch 模块提供的查询服务能力。 |
| open-api.md | OpenAPI | Wow OpenAPI 模块提供基于 OpenAPI 规范的 API 接口。 |
| test-suite.md | 测试套件 | 基于 Given->When->Expect 模式的测试套件，助力开发者轻松实现 80% 以上测试覆盖率。 |
| bi.md | 商业智能 | Wow 提供实时聚合根状态事件和命令作为商业智能分析的数据源。 |
| event-compensation.md | 事件补偿 | 事件补偿机制用于处理和恢复事件处理失败导致的数据不一致问题。 |
| best-practices.md | 最佳实践 | 基于 Wow 框架构建应用的架构与开发最佳实践。 |
| perf-test.md | 性能评测 | Wow 框架在不同场景下的性能基准测试和结果。 |
| troubleshooting.md | 故障排查 | Wow 框架常见问题的诊断和解决方案。 |
| migration.md | 迁移指南 | 从传统架构迁移到 Wow 框架以及版本间升级的指南。 |
| configuration.md | 配置 | 通过 Spring Boot 配置属性机制提供的全面配置选项。 |
| event-processor.md | 事件处理器 | 事件处理器处理聚合发布的领域事件，实现跨聚合操作和读模型更新。 |

**extensions/ 目录：**
| File | title | description |
|------|-------|-------------|
| kafka.md | Kafka | Apache Kafka 扩展，实现 CommandBus、DomainEventBus 和 StateEventBus，适用于生产环境。 |
| mongo.md | Mongo | MongoDB 扩展，提供推荐的事件存储和快照存储实现。 |
| r2dbc.md | R2DBC | R2DBC 扩展，提供响应式关系数据库的事件存储和快照存储支持。 |
| redis.md | Redis | Redis 扩展，提供高性能低延迟的事件存储和快照存储。 |
| elasticsearch.md | Elasticsearch | Elasticsearch 扩展，支持全文搜索和复杂查询场景。 |
| opentelemetry.md | OpenTelemetry | OpenTelemetry 集成，提供厂商中立的分布式追踪和监控能力。 |
| webflux.md | WebFlux | Spring WebFlux 扩展，自动注册命令路由处理函数。 |
| spring-boot-starter.md | Spring Boot Starter | Spring Boot Starter 模块集成所有 Wow 扩展并提供自动配置能力。 |
| tck.md | 兼容性测试套件 | 用于验证接口实现是否符合规范的测试用例集。 |

**advanced/ 目录：**
| File | title | description |
|------|-------|-------------|
| architecture.md | 架构 | Wow 框架整体架构设计、核心组件关系和处理流程详解。 |
| id-generator.md | ID 生成器 | 基于 CosId 的消息 ID 和聚合根 ID 生成机制。 |
| compiler.md | 编译器 | KSP 编译器，为聚合根、命令和领域事件生成元数据。 |
| prepare-key.md | 预分配 Key | 事件溯源架构中应用级别的 Key 唯一性机制。 |
| metrics.md | 指标 | 基于 Micrometer 的全面性能监控和可观测性指标采集。 |
| observability.md | 可观测性 | Wow 框架的端到端可观测性集成。 |
| aggregate-scheduler.md | 聚合调度器 | 为每个聚合提供专用 Reactor 调度器，控制并发执行和资源分配。 |

- [ ] **Step 2: 构建验证**

```bash
cd documentation && npm run docs:build
```

Expected: 构建成功

- [ ] **Step 3: 提交**

```bash
git add -A && git commit -m "docs(seo): add frontmatter to all Chinese guide pages"
```

---

## Task 6: 视觉体验 — 主题样式优化

**Files:**
- Modify: `documentation/docs/.vitepress/theme/global.css`

- [ ] **Step 1: 修改内容区最大宽度**

将 `--vp-layout-max-width: 100%` 改为 `--vp-layout-max-width: 1440px`：

```css
:root {
    --vp-sidebar-width: 272px;
    --vp-layout-max-width: 1440px;
}
```

同时将 `--vp-sidebar-width` 从 `200px` 改为 `272px`。

- [ ] **Step 2: 修改 hero 渐变色**

将紫蓝渐变改为品牌色 indigo 渐变：

```css
:root {
    --vp-home-hero-name-background: -webkit-linear-gradient(
            120deg,
            #5f67ee 30%,
            #8b5cf6
    );

    --vp-home-hero-image-background-image: linear-gradient(
            -45deg,
            #5f67ee 50%,
            #8b5cf6 50%
    );
    --vp-home-hero-image-filter: blur(44px);
}
```

保持三个 `@media` 断点的 blur 值不变。

- [ ] **Step 3: 构建验证**

```bash
cd documentation && npm run docs:build
```

Expected: 构建成功

- [ ] **Step 4: 提交**

```bash
git add -A && git commit -m "style(docs): optimize layout width, sidebar width, and hero gradient"
```

---

## Task 7: 视觉体验 — 首页图标替换

**Files:**
- Modify: `documentation/docs/en/index.md`
- Modify: `documentation/docs/zh/index.md`

- [ ] **Step 1: 替换 EN 首页 features 图标**

将 emoji 替换为 SVG 图标（VitePress 内置图标集）：

```yaml
features:
- title: Domain Model as a Service
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><path fill="currentColor" d="M20 6h-4V4c0-1.1-.9-2-2-2h-4c-1.1 0-2 .9-2 2v2H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zM10 4h4v2h-4V4zm10 16H4V8h16v12z"/></svg>'
  details: Just write the domain model, and Wow automatically prepares the OpenAPI interface for you.
  link: /guide/modeling
- title: Test Suite
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><path fill="currentColor" d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41L9 16.17z"/></svg>'
  details: Test suite based on Given->When->Expect pattern, helping developers easily achieve over 80% test coverage.
  link: /guide/test-suite
- title: High Performance
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><path fill="currentColor" d="M13 2.05v2.02c3.95.49 7 3.85 7 7.93 0 3.73-2.56 6.86-6.02 7.75l.72 1.93C19.16 20.6 22 16.72 22 12.14c0-5.18-3.95-9.45-9-10.09zM12 19c-3.87 0-7-3.13-7-7s3.13-7 7-7 7 3.13 7 7-3.13 7-7 7zM11 2.06C5.93 2.56 2 6.84 2 12c0 4.58 2.84 8.48 6.84 10.06l.72-1.93C6.17 19.15 4 16.02 4 12.14c0-4.08 3.05-7.44 7-7.93V2.06z"/></svg>'
  details: Aggregate+EventSourcing, CQRS architecture, write operations only require AppendOnly operations.
  link: /guide/perf-test
- title: Scalability
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><path fill="currentColor" d="M19 8H5c-1.66 0-3 1.34-3 3v6h4v4h12v-4h4v-6c0-1.66-1.34-3-3-3zm-3 11H8v-5h8v5zm3-7c-.55 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.45 1-1 1zm-1-9H6v4h12V3z"/></svg>'
  details: No need to consider database relationship patterns or sharding rules, easy horizontal scaling.
  link: /guide/introduction.html#performance-and-scalability
- title: Distributed Transactions (Saga)
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><path fill="currentColor" d="M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7c-1.71 0-3.1-1.39-3.1-3.1zM8 13h8v-2H8v2zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1s-1.39 3.1-3.1 3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5z"/></svg>'
  details: Carefully manage transaction processes between complex multi-services using Saga orchestration pattern.
  link: /guide/saga
- title: Event Compensation
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><path fill="currentColor" d="M12.5 8c-2.65 0-5.05 1.04-6.83 2.73L3 8v9h9l-2.83-2.83C10.33 12.93 11.36 12.5 12.5 12.5c2.33 0 4.3 1.46 5.11 3.5l2.61-.93C19.08 12.19 16.05 8 12.5 8z"/></svg>'
  details: Visual event compensation console and automatic compensation mechanism to ensure eventual consistency.
  link: /guide/event-compensation
- title: End-to-End Observability
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><path fill="currentColor" d="M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z"/></svg>'
  details: Integrate OpenTelemetry for end-to-end observability, helping monitoring and debugging.
  link: /guide/extensions/opentelemetry
- title: Reactive Programming
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><path fill="currentColor" d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"/></svg>'
  details: Reactive programming model for asynchronous and concurrent operations with non-blocking communication.
  link: /guide/introduction
- title: Business Intelligence
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><path fill="currentColor" d="M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zM9 17H7v-7h2v7zm4 0h-2V7h2v10zm4 0h-2v-4h2v4z"/></svg>'
  details: Rich data sources with clear business semantics for real-time data analysis and operation audit.
  link: /guide/bi
```

- [ ] **Step 2: 替换 ZH 首页 features 图标**

使用相同的 SVG 图标，保持中文 title/details/link 不变，仅替换 icon 字段。图标映射与 EN 完全一致。

- [ ] **Step 3: 构建验证**

```bash
cd documentation && npm run docs:build
```

Expected: 构建成功

- [ ] **Step 4: 提交**

```bash
git add -A && git commit -m "style(docs): replace emoji icons with SVG icons on homepage"
```

---

## Task 8: 导航优化 — 侧边栏折叠与去重

**Files:**
- Modify: `documentation/docs/.vitepress/configs/sidebar.en.ts`
- Modify: `documentation/docs/.vitepress/configs/sidebar.zh.ts`

- [ ] **Step 1: 修改 EN sidebar 折叠状态**

在 `/guide/` sidebar 中：
- Guide 分组保持 `collapsed: false`
- Extensions 分组改为 `collapsed: true`
- Advanced 分组改为 `collapsed: true`
- Reference 分组改为 `collapsed: true`

- [ ] **Step 2: 修改 ZH sidebar 折叠状态**

同 EN 侧边栏处理方式一致。

- [ ] **Step 3: 构建验证**

```bash
cd documentation && npm run docs:build
```

Expected: 构建成功

- [ ] **Step 4: 提交**

```bash
git add -A && git commit -m "docs(nav): collapse non-primary sidebar groups by default"
```

---

## Task 9: 导航优化 — 精简导航栏资源菜单

**Files:**
- Modify: `documentation/docs/.vitepress/configs/navbar.en.ts`
- Modify: `documentation/docs/.vitepress/configs/navbar.zh.ts`

- [ ] **Step 1: 精简 EN navbar Resources 菜单**

将三级嵌套的 "Open source projects - Microservice governance" 拆平为二级菜单：

```typescript
{
    text: "Resources",
    items: [
        {
            text: 'Project template for quickly building DDD projects based on Wow framework',
            link: 'https://github.com/Ahoo-Wang/wow-project-template'
        },
        {
            text: 'Powerful TypeScript code generation tool',
            link: 'https://github.com/Ahoo-Wang/fetcher/blob/main/packages/generator/'
        },
        {
            text: 'Fluent Kotlin Assertion Library',
            link: 'https://github.com/Ahoo-Wang/FluentAssert'
        },
        { text: '-' },
        {
            text: 'CosId - Distributed ID Generator',
            link: 'https://github.com/Ahoo-Wang/CosId'
        },
        {
            text: 'CoSky - Microservice Governance',
            link: 'https://github.com/Ahoo-Wang/CoSky'
        },
        {
            text: 'CoSec - Reactive Security Framework',
            link: 'https://github.com/Ahoo-Wang/CoSec'
        },
        {
            text: 'CoCache - Distributed Cache',
            link: 'https://github.com/Ahoo-Wang/CoCache'
        },
        {
            text: 'Simba - Distributed Lock',
            link: 'https://github.com/Ahoo-Wang/Simba'
        }
    ]
}
```

使用 `{ text: '-' }` 分隔线替代三级嵌套。

- [ ] **Step 2: 精简 ZH navbar 资源菜单**

同样将三级嵌套拆平为二级：

```typescript
{
    text: "资源",
    items: [
        {
            text: '用于快速构建基于 Wow 框架的 DDD 项目模板',
            link: 'https://github.com/Ahoo-Wang/wow-project-template'
        },
        {
            text: '功能强大的 TypeScript 代码生成工具',
            link: 'https://github.com/Ahoo-Wang/fetcher/blob/main/packages/generator/'
        },
        {
            text: '流畅的 Kotlin 断言库',
            link: 'https://github.com/Ahoo-Wang/FluentAssert'
        },
        { text: '-' },
        {
            text: 'CosId - 分布式 ID 生成器',
            link: 'https://github.com/Ahoo-Wang/CosId'
        },
        {
            text: 'CoSky - 微服务治理',
            link: 'https://github.com/Ahoo-Wang/CoSky'
        },
        {
            text: 'CoSec - 响应式安全框架',
            link: 'https://github.com/Ahoo-Wang/CoSec'
        },
        {
            text: 'CoCache - 分布式缓存',
            link: 'https://github.com/Ahoo-Wang/CoCache'
        },
        {
            text: 'Simba - 分布式锁',
            link: 'https://github.com/Ahoo-Wang/Simba'
        }
    ]
}
```

- [ ] **Step 3: 构建验证**

```bash
cd documentation && npm run docs:build
```

Expected: 构建成功

- [ ] **Step 4: 提交**

```bash
git add -A && git commit -m "docs(nav): flatten resources dropdown menu from 3-level to 2-level"
```

---

## Task 10: 最终验证

- [ ] **Step 1: 完整构建**

```bash
cd documentation && npm run docs:build
```

Expected: 构建成功，无警告

- [ ] **Step 2: 启动 dev server 并手动检查**

```bash
cd documentation && npm run docs:dev
```

检查清单：
- 首页图标是否为 SVG（非 emoji）
- 首页 hero 渐变色是否为 indigo 系
- 侧边栏宽度是否合理
- 内容区宽度是否合理
- R2DBC 页面链接是否正常
- Extensions/Advanced 侧边栏默认折叠
- 导航栏资源菜单为二级结构

- [ ] **Step 3: 确认所有提交**

```bash
git log --oneline main..HEAD
```

Expected: 9 个提交（1 design doc + 8 implementation commits）
