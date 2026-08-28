---
layout: home
kaicodeAward: true
title: 基于 DDD & EventSourcing 的现代响应式 CQRS 架构微服务开发框架
hero:
  name: "Wow"
  text: "领域模型即服务"
  tagline: "基于 DDD & EventSourcing 的现代响应式 CQRS 架构微服务开发框架"
  image:
    src: /images/logo.svg
    alt: Wow
  actions:
    - theme: brand
      text: 30 分钟快速上手
      link: /zh/guide/getting-started
    - theme: alt
      text: 认识 Wow
      link: /zh/guide/introduction
    - theme: alt
      text: 开发指南
      link: /zh/guide/
    - theme: alt
      text: GitHub
      link: https://github.com/Ahoo-Wang/Wow
features:
- title: 领域模型即服务
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m12 3-1.912 5.813a2 2 0 0 1-1.275 1.275L3 12l5.813 1.912a2 2 0 0 1 1.275 1.275L12 21l1.912-5.813a2 2 0 0 1 1.275-1.275L21 12l-5.813-1.912a2 2 0 0 1-1.275-1.275L12 3Z"/></svg>'
  details: 围绕领域模型定义命令、事件与状态，Wow 生成 OpenAPI 元数据并装配运行链路，减少重复基础设施代码。
  link: /zh/guide/domain/
- title: 测试套件
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 3h6l3 7-6 11-6-11 3-7Z"/><path d="M9 3 3 10h18L15 3"/></svg>'
  details: Given->When->Expect 测试套件直接验证命令、事件与状态；覆盖率和交付质量仍由应用门禁证明。
  link: /zh/guide/test-suite
- title: 可复现的性能基线
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M13 2 3 14h9l-1 8 10-12h-9l1-8z"/></svg>'
  details: 提供按用途分层的 JMH 基准任务；性能结论必须绑定当前代码、运行环境与结果清单，避免复用失去上下文的历史数据
  link: /zh/guide/test-runtime#基准分三种用途
- title: 可伸缩性
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 3h6v6"/><path d="M9 21H3v-6"/><path d="m21 3-7 7"/><path d="m3 21 7-7"/></svg>'
  details: 聚合、事件存储与消息抽象降低领域规则对存储拓扑的耦合；实际伸缩能力取决于热点、后端和部署验证。
  link: /zh/guide/introduction.html#_2-性能与伸缩性
- title: 分布式事务 (Saga)
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"/><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/></svg>'
  details: 利用 Saga 编排模式精心管理复杂多服务之间的事务流程，以降低分布式事务的复杂性
  link: /zh/guide/event/saga
- title: 事件补偿自动化
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/><path d="M12 7v5l4 2"/></svg>'
  details: 记录事件处理失败并提供重试、通知和可视化运维入口，帮助应用建立可验证的恢复流程。
  link: /zh/guide/event/compensation
- title: 端到端可观测 (Observability)
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/></svg>'
  details: 为命令、事件、投影、Saga 与存储链路提供 OpenTelemetry 观测点，支持应用定位异步阶段和失败边界。
  link: /zh/guide/extensions/opentelemetry
- title: 响应式编程 (Reactive)
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 12h-4l-3 9L9 3l-3 9H2"/></svg>'
  details: 核心运行链路采用 Reactor 非阻塞组合；吞吐、延迟和弹性仍需在实际 Adapter、硬件与负载下验证。
- title: 商业智能
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 3v18h18"/><path d="m19 9-5 5-4-4-3 3"/></svg>'
  details: 以状态事件和命令提供具有业务语义的数据源，并生成 ClickHouse 同步脚本；实时性与数据质量由应用运维保障。
  link: /zh/guide/bi
---

## 从价值到首次成功

先读[简介](./guide/introduction.md)，判断 Wow 的价值、适用边界和采用成本；准备动手时，进入[30 分钟快速上手](./guide/getting-started.md)。领域测试、真实 HTTP 命令和版本化事件溯源状态均验证通过，才算完成第一次成功。

## 荣誉

Wow 荣获 [KaiCode’26 Excellent Award](https://www.kaicode.org/2026.html)。官方结果页重点提及了项目的模块化 DDD/CQRS 设计、规范且由多位评审者参与的代码审查、基于 Testcontainers 的集成测试、强制执行的测试覆盖率阈值、Detekt 静态分析、双语文档，以及长期的 Maven Central 语义化版本发布记录。
