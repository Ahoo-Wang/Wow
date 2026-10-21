---
layout: home
kaicodeAward: true
title: 领域模型即服务
hero:
  name: "Wow"
  text: "领域模型即服务"
  tagline: "只写领域模型，就得到一个能上线的系统：命令、事件、查询、类型化的契约与可重放的读模型。"
  image:
    src: /images/logo.svg
    alt: Wow
  actions:
    - theme: brand
      text: 快速开始
      link: /zh/guide/v10/quickstart
    - theme: alt
      text: 编写服务
      link: /zh/guide/v10/
    - theme: alt
      text: GitHub
      link: https://github.com/Ahoo-Wang/Wow
features:
- title: 只写领域模型
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m12 3-1.912 5.813a2 2 0 0 1-1.275 1.275L3 12l5.813 1.912a2 2 0 0 1 1.275 1.275L12 21l1.912-5.813a2 2 0 0 1 1.275-1.275L21 12l-5.813-1.912a2 2 0 0 1-1.275-1.275L12 3Z"/></svg>'
  details: "命令做出决定，事件演化状态；编译器在构建期完成绑定，没有反射，也不用写基础设施代码。"
  link: /zh/guide/v10/programming-model
- title: 日志就是总线
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M8 6h13"/><path d="M8 12h13"/><path d="M8 18h13"/><path d="M3 6h.01"/><path d="M3 12h.01"/><path d="M3 18h.01"/></svg>'
  details: "追加是唯一的写。订阅带着检查点读日志，已提交的事实在传递途中不会丢失。"
  link: /zh/operate/topology
- title: 一份契约
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/></svg>'
  details: "Manifest 是唯一来源：REST 路由、OpenAPI、TypeScript 客户端与视图都从它推出。"
  link: /zh/guide/v10/http-api
- title: 零代码查询
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5"/></svg>'
  details: "每个聚合的最新状态都能直接查询，所有读模型都能从头重建。"
  link: /zh/guide/v10/query
- title: 用模型的语言测试
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 3h6l3 7-6 11-6-11 3-7Z"/><path d="M9 3 3 10h18L15 3"/></svg>'
  details: "给定事件、执行命令、断言事件与状态：聚合、Saga 与投影无需启动服务即可测试。"
  link: /zh/guide/v10/testing
- title: PostgreSQL 或 MongoDB
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M3 5v14a9 3 0 0 0 18 0V5"/><path d="M3 12a9 3 0 0 0 18 0"/></svg>'
  details: "两者都是一等后端；Kafka 承载跨上下文的消息，ClickHouse 承载数据集。"
  link: /zh/operate/topology
---

## 荣誉

Wow 荣获 [KaiCode’26 Excellent Award](https://www.kaicode.org/2026.html)。官方结果页重点提及了项目的模块化 DDD/CQRS 设计、规范且由多位评审者参与的代码审查、基于 Testcontainers 的集成测试、强制执行的测试覆盖率阈值、Detekt 静态分析、双语文档，以及长期的 Maven Central 语义化版本发布记录。
