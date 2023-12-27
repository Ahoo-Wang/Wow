---
layout: home

hero:
  name: "Wow"
  text: "领域模型即服务"
  tagline: 基于 DDD、EventSourcing 的现代响应式 CQRS 架构微服务开发框架
  image:
    src: /images/logo.svg
    alt: Wow
  actions:
    - theme: brand
      text: 快速上手
      link: /guide/getting-started
    - theme: alt
      text: 项目介绍
      link: /guide/introduction

features:
- title: 领域模型即服务
  details: 仅需编写领域模型，即可完成服务开发，Wow 自动为您准备好 OpenAPI 接口。因为高效，CRUD 也值得 DDD。
- title: 测试套件
  details: 基于 Given->When->Expect 模式的测试套件，助力开发者轻松实现 80% 以上的测试覆盖率，确保高质量应用交付
  link: /guide/test-suite
- title: 高性能
  details: Aggregate+EventSourcing、CQRS 架构，写操作仅需进行 AppendOnly 操作，读操作则利用面向查询的搜索引擎
  link: /guide/perf-test
- title: 可伸缩性
  details: 无需考虑数据库关系模式、分片规则等问题，代码无需变更，即可轻松实现水平伸缩
- title: 分布式事务 (Saga)
  details: 利用 Saga 编排模式精心管理复杂多服务之间的事务流程，以降低分布式事务的复杂性
  link: /guide/saga
- title: 事件补偿自动化
  details: 提供可视化的事件补偿控制台和自动补偿机制，确保系统数据的最终一致性
  link: /guide/event-compensation
- title: 端到端可观测 (Observability)
  details: 集成 OpenTelemetry，实现系统的端到端可观测性，助力监控和调试，解决CQRS模式可能引起的系统复杂性问题
  link: /guide/advanced/observability
- title: 响应式编程 (Reactive)
  details: 基于响应式编程模型使系统更适应异步和并发操作，提高整体响应性能。通过异步消息传递，系统组件以非阻塞方式通信，降低系统开销、增强系统弹性，确保高负载和低负载时均能保持即时响应性。
- title: 商业智能
  details: 全量状态事件（快照+领域事件）、聚合命令实时数据分析、操作审计，为业务决策提供有力支持
  link: /guide/bi

---

