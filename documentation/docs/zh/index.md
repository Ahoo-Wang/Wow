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
      text: 快速上手
      link: /zh/guide/getting-started
    - theme: alt
      text: 简介
      link: /zh/guide/introduction
    - theme: alt
      text: GitHub
      link: https://github.com/Ahoo-Wang/Wow
    - theme: alt
      text: Gitee
      link: https://gitee.com/AhooWang/Wow
features:
- title: 领域模型即服务
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m12 3-1.912 5.813a2 2 0 0 1-1.275 1.275L3 12l5.813 1.912a2 2 0 0 1 1.275 1.275L12 21l1.912-5.813a2 2 0 0 1 1.275-1.275L21 12l-5.813-1.912a2 2 0 0 1-1.275-1.275L12 3Z"/></svg>'
  details: 仅需编写领域模型，即可完成服务开发，Wow 自动为您准备好 OpenAPI 接口。因为高效，CRUD 也值得 DDD。
  link: /zh/guide/modeling
- title: 测试套件
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 3h6l3 7-6 11-6-11 3-7Z"/><path d="M9 3 3 10h18L15 3"/></svg>'
  details: 基于 Given->When->Expect 模式的测试套件，助力开发者轻松实现 80% 以上的测试覆盖率，确保高质量应用交付
  link: /zh/guide/test-suite
- title: 高性能
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M13 2 3 14h9l-1 8 10-12h-9l1-8z"/></svg>'
  details: Aggregate+EventSourcing、CQRS 架构，写操作仅需进行 AppendOnly 操作，读操作则利用面向查询的搜索引擎
  link: /zh/guide/perf-test
- title: 可伸缩性
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 3h6v6"/><path d="M9 21H3v-6"/><path d="m21 3-7 7"/><path d="m3 21 7-7"/></svg>'
  details: 无需考虑数据库关系模式、分片规则等问题，代码无需变更，即可轻松实现水平伸缩
  link: /zh/guide/introduction.html#性能与伸缩性
- title: 分布式事务 (Saga)
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"/><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/></svg>'
  details: 利用 Saga 编排模式精心管理复杂多服务之间的事务流程，以降低分布式事务的复杂性
  link: /zh/guide/saga
- title: 事件补偿自动化
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/><path d="M12 7v5l4 2"/></svg>'
  details: 提供可视化的事件补偿控制台和自动补偿机制，确保系统数据的最终一致性
  link: /zh/guide/event-compensation
- title: 端到端可观测 (Observability)
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/></svg>'
  details: 集成 OpenTelemetry，实现系统的端到端可观测性，助力监控和调试，解决CQRS模式可能引起的系统复杂性问题
  link: /zh/guide/extensions/opentelemetry
- title: 响应式编程 (Reactive)
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 12h-4l-3 9L9 3l-3 9H2"/></svg>'
  details: 基于响应式编程模型使系统更适应异步和并发操作，提高整体响应性能。通过异步消息传递，系统组件以非阻塞方式通信，降低系统开销、增强系统弹性，确保高负载和低负载时均能保持即时响应性。
- title: 商业智能
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 3v18h18"/><path d="m19 9-5 5-4-4-3 3"/></svg>'
  details: 提供更丰富且具有明确业务语义的数据源（包括状态事件和命令）。具有极低的ETL成本，助力实时数据分析和操作审计，为业务决策提供有力支持。
  link: /zh/guide/bi
---

## 荣誉

Wow 荣获 [KaiCode’26 Excellent Award](https://www.kaicode.org/2026.html)。官方结果页重点提及了项目的模块化 DDD/CQRS 设计、规范且由多位评审者参与的代码审查、基于 Testcontainers 的集成测试、强制执行的测试覆盖率阈值、Detekt 静态分析、双语文档，以及长期的 Maven Central 语义化版本发布记录。
