---
layout: home
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
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><path fill="currentColor" d="M20 6h-4V4c0-1.1-.9-2-2-2h-4c-1.1 0-2 .9-2 2v2H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zM10 4h4v2h-4V4zm10 16H4V8h16v12z"/></svg>'
  details: 仅需编写领域模型，即可完成服务开发，Wow 自动为您准备好 OpenAPI 接口。因为高效，CRUD 也值得 DDD。
  link: /zh/guide/modeling
- title: 测试套件
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><path fill="currentColor" d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41L9 16.17z"/></svg>'
  details: 基于 Given->When->Expect 模式的测试套件，助力开发者轻松实现 80% 以上的测试覆盖率，确保高质量应用交付
  link: /zh/guide/test-suite
- title: 高性能
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><path fill="currentColor" d="M13 2.05v2.02c3.95.49 7 3.85 7 7.93 0 3.73-2.56 6.86-6.02 7.75l.72 1.93C19.16 20.6 22 16.72 22 12.14c0-5.18-3.95-9.45-9-10.09zM12 19c-3.87 0-7-3.13-7-7s3.13-7 7-7 7 3.13 7 7-3.13 7-7 7zM11 2.06C5.93 2.56 2 6.84 2 12c0 4.58 2.84 8.48 6.84 10.06l.72-1.93C6.17 19.15 4 16.02 4 12.14c0-4.08 3.05-7.44 7-7.93V2.06z"/></svg>'
  details: Aggregate+EventSourcing、CQRS 架构，写操作仅需进行 AppendOnly 操作，读操作则利用面向查询的搜索引擎
  link: /zh/guide/perf-test
- title: 可伸缩性
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><path fill="currentColor" d="M19 8H5c-1.66 0-3 1.34-3 3v6h4v4h12v-4h4v-6c0-1.66-1.34-3-3-3zm-3 11H8v-5h8v5zm3-7c-.55 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.45 1-1 1zm-1-9H6v4h12V3z"/></svg>'
  details: 无需考虑数据库关系模式、分片规则等问题，代码无需变更，即可轻松实现水平伸缩
  link: /zh/guide/introduction.html#性能与伸缩性
- title: 分布式事务 (Saga)
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><path fill="currentColor" d="M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7c-1.71 0-3.1-1.39-3.1-3.1zM8 13h8v-2H8v2zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1s-1.39 3.1-3.1 3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5z"/></svg>'
  details: 利用 Saga 编排模式精心管理复杂多服务之间的事务流程，以降低分布式事务的复杂性
  link: /zh/guide/saga
- title: 事件补偿自动化
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><path fill="currentColor" d="M12.5 8c-2.65 0-5.05 1.04-6.83 2.73L3 8v9h9l-2.83-2.83C10.33 12.93 11.36 12.5 12.5 12.5c2.33 0 4.3 1.46 5.11 3.5l2.61-.93C19.08 12.19 16.05 8 12.5 8z"/></svg>'
  details: 提供可视化的事件补偿控制台和自动补偿机制，确保系统数据的最终一致性
  link: /zh/guide/event-compensation
- title: 端到端可观测 (Observability)
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><path fill="currentColor" d="M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z"/></svg>'
  details: 集成 OpenTelemetry，实现系统的端到端可观测性，助力监控和调试，解决CQRS模式可能引起的系统复杂性问题
  link: /zh/guide/extensions/opentelemetry
- title: 响应式编程 (Reactive)
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><path fill="currentColor" d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"/></svg>'
  details: 基于响应式编程模型使系统更适应异步和并发操作，提高整体响应性能。通过异步消息传递，系统组件以非阻塞方式通信，降低系统开销、增强系统弹性，确保高负载和低负载时均能保持即时响应性。
- title: 商业智能
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><path fill="currentColor" d="M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zM9 17H7v-7h2v7zm4 0h-2V7h2v10zm4 0h-2v-4h2v4z"/></svg>'
  details: 提供更丰富且具有明确业务语义的数据源（包括状态事件和命令）。具有极低的ETL成本，助力实时数据分析和操作审计，为业务决策提供有力支持。
  link: /zh/guide/bi
---