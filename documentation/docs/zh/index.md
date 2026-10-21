---
layout: home
kaicodeAward: true
title: 领域模型即服务
hero:
  name: "Wow"
  text: "领域模型即服务"
  tagline: "只写领域模型，就得到一个能上线的系统。"
  image:
    src: /images/logo.svg
    alt: Wow
  actions:
    - theme: brand
      text: 快速开始
      link: /zh/guide/v10/quickstart
    - theme: alt
      text: Wow 怎样工作
      link: /zh/concepts/
    - theme: alt
      text: GitHub
      link: https://github.com/Ahoo-Wang/Wow
features:
- title: 只写领域模型
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m12 3-1.912 5.813a2 2 0 0 1-1.275 1.275L3 12l5.813 1.912a2 2 0 0 1 1.275 1.275L12 21l1.912-5.813a2 2 0 0 1 1.275-1.275L21 12l-5.813-1.912a2 2 0 0 1-1.275-1.275L12 3Z"/></svg>'
  details: "命令做出决定，事件演化状态，编译器在构建期把两者绑定好：没有反射，也没有基础设施代码。"
  link: /zh/concepts/programming-model
- title: 日志就是总线
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M8 6h13"/><path d="M8 12h13"/><path d="M8 18h13"/><path d="M3 6h.01"/><path d="M3 12h.01"/><path d="M3 18h.01"/></svg>'
  details: "一次追加是唯一的写。其他一切都从自己的检查点读日志，已提交的事实在途中不会丢失。"
  link: /zh/concepts/event-log
- title: 一份契约
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/></svg>'
  details: "Manifest 把服务描述一次。HTTP 路由、OpenAPI、TypeScript 客户端和视图都从它推出。"
  link: /zh/concepts/contract
- title: 读模型可重放
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5"/></svg>'
  details: "每个聚合的最新状态都能直接查询，每个读模型都能从日志开头重建。"
  link: /zh/concepts/read-models
- title: 失败是数据
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 3h6l3 7-6 11-6-11 3-7Z"/><path d="M9 3 3 10h18L15 3"/></svg>'
  details: "一再失败的处理函数，只停放它失败的那一个聚合。不跳过任何东西，别的也不会停。"
  link: /zh/concepts/subscriptions
- title: PostgreSQL 或 MongoDB
  icon: '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M3 5v14a9 3 0 0 0 18 0V5"/><path d="M3 12a9 3 0 0 0 18 0"/></svg>'
  details: "两者都是一等的日志后端。Kafka 承载跨上下文的东西，ClickHouse 提供数据集。"
  link: /zh/concepts/event-log#backends
---

## 你写什么，得到什么

快速开始里银行账户的一个命令函数，基于当前状态对一次取款做出决定：

<!-- sample: example/quickstart/src/main/kotlin/me/ahoo/wow/example/quickstart/Account.kt#withdraw -->

```kotlin
@OnCommand(produces = [Withdrawn::class])
fun onCommand(command: Withdraw): Decision = when {
    command.amount <= 0 ->
        Decision.reject(Rejections.INVALID_AMOUNT, "${command.amount} is not positive.")

    command.amount > state.balance ->
        Decision.reject(Rejections.INSUFFICIENT_BALANCE, "The balance is ${state.balance}.")

    else -> Decision.events(Withdrawn(command.amount))
}
```

它的测试用同样的语言来写：给定事件，执行一条命令，期望得到事件和状态，不需要启动服务：

<!-- sample: example/quickstart/src/test/kotlin/me/ahoo/wow/example/quickstart/AccountSpec.kt#withdraw -->

```kotlin
@Test
fun `a withdrawal the balance does not cover is rejected`() = aggregateSpec(model, id = "alice") {
    given(AccountOpened("Alice"), Deposited(100))
    whenCommand(Withdraw(150)) { expectRejected(Account.Rejections.INSUFFICIENT_BALANCE) }
    whenCommand(Withdraw(30)) {
        expectEvents(Withdrawn(30))
        expectState { balance.assert().isEqualTo(70) }
    }
}
```

不用再写代码，服务就能从这个模型得到：每条命令一个 HTTP 路由、状态读取和快照查询、一份 OpenAPI 文档、一份 Manifest（`wow-generator` 由它生成类型化的 TypeScript 客户端），以及以 `application/problem+json` 返回、带错误码的拒绝。

## 从你的工作开始

| 你想 | 从这里开始 |
| --- | --- |
| 判断 Wow 是否适合你的系统 | [Wow 怎样工作](/zh/concepts/)，然后是[术语表](/zh/reference/glossary) |
| 编写一个服务 | [快速开始](/zh/guide/v10/quickstart)，然后是[决定与演化](/zh/concepts/programming-model) |
| 从 TypeScript 前端调用服务 | [TypeScript 客户端](/zh/guide/typescript/) |
| 在生产环境运行服务 | [身份、租户与访问](/zh/concepts/identity)，然后是[订阅、失败与重放](/zh/concepts/subscriptions) |
| 分析数据 | [读模型](/zh/concepts/read-models)，然后是[工作台](/zh/data/workbench) |

## 荣誉

Wow 荣获 [KaiCode’26 Excellent Award](https://www.kaicode.org/2026.html)。官方结果页重点提及了项目的模块化 DDD/CQRS 设计、规范且由多位评审者参与的代码审查、基于 Testcontainers 的集成测试、强制执行的测试覆盖率阈值、Detekt 静态分析、双语文档，以及长期的 Maven Central 语义化版本发布记录。
