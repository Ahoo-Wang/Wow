<p align="center" style="text-align:center;">
  <img width="150" src="documentation/docs/public/images/logo.svg" alt="Wow:基于 DDD & EventSourcing 的现代响应式 CQRS 架构微服务开发框架"/>
</p>

# Wow : 基于 DDD & EventSourcing 的现代响应式 CQRS 架构微服务开发框架

> [中文文档](https://wow.ahoo.me/)

[![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](https://github.com/Ahoo-Wang/Wow/blob/mvp/LICENSE)
[![GitHub release](https://img.shields.io/github/release/Ahoo-Wang/Wow.svg)](https://github.com/Ahoo-Wang/Wow/releases)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/me.ahoo.wow/wow-core/badge.svg)](https://maven-badges.herokuapp.com/maven-central/me.ahoo.wow/wow-core)
[![Codacy Badge](https://app.codacy.com/project/badge/Grade/cfc724df22db4f9387525258c8a59609)](https://app.codacy.com/gh/Ahoo-Wang/Wow/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade)
[![codecov](https://codecov.io/gh/Ahoo-Wang/Wow/branch/main/graph/badge.svg?token=uloJrLoQir)](https://codecov.io/gh/Ahoo-Wang/Wow)
[![Integration Test Status](https://github.com/Ahoo-Wang/Wow/actions/workflows/integration-test.yml/badge.svg)](https://github.com/Ahoo-Wang/Wow)
[![Awesome Kotlin Badge](https://kotlin.link/awesome-kotlin.svg)](https://github.com/KotlinBy/awesome-kotlin)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Ahoo-Wang/Wow)

**领域驱动** | **事件驱动** | **测试驱动** | **声明式设计** | **响应式编程** | **命令查询职责分离** | **事件溯源**

## 简介

_Wow_ 是一个基于领域驱动设计和事件溯源的现代响应式 _CQRS_ 微服务开发框架，历经多年生产环境验证。

旨在帮助开发者构建现代化的、高性能且易于维护的微服务应用程序，充分发挥领域驱动设计和事件溯源等模式优势的同时降低应用的复杂性以及实践成本。

> 值得一提的是，领域驱动设计和事件溯源并非微服务架构的专属，_Wow_ 框架不仅适用于微服务开发，同样也可用于构建基于领域驱动设计的单体应用程序。

## 快速开始

[使用 Wow 项目模板快速创建基于 Wow 框架的 DDD 项目](https://wow.ahoo.me/guide/getting-started.html) 

## 特性概览

<p align="center" style="text-align:center">
  <img src="documentation/docs/public/images/Features.png" alt="Wow-Features"/>
</p>

## 架构图

<p align="center" style="text-align:center">
  <img src="documentation/docs/public/images/Architecture.svg" alt="Wow-Architecture"/>
</p>

## 背景

随着业务的发展和复杂性的增加，传统的架构和开发方式逐渐显露出瓶颈。*领域驱动设计*和*事件溯源*等理念在提高系统设计的灵活性和可维护性方面表现出色，但在实践中常常需要面对复杂性和学习曲线的挑战。

_Wow_ 框架的目标是以简单易用的方式将领域驱动设计和事件溯源等理念融入到微服务应用开发中，降低开发者的学习成本，提高开发效率。
通过提供现代响应式的 _CQRS_ 架构和相关组件，_Wow_ 框架旨在让开发者更专注于业务逻辑的实现，而不必过多关心底层技术细节。

经过多年的实践和不断的演进，_Wow_ 框架在生产环境中得到了验证，积累了丰富的经验。这些经验和反馈不仅丰富了框架的功能和性能，也为持续的改进和优化提供了宝贵的指导。

## 对于开发者而言，_Wow_ 框架意味着什么？

我曾告诫我的团队：如果我们过于依赖*数据驱动设计*而忽视*领域驱动设计*，我们最终将沦为*CRUD*工程师。

> *CRUD*工程师的竞争力和可替代性可想而知，这或许是为何会有 _35_ 岁效应，企业显然更倾向于招募没有太多生活羁绊、更加廉价的 _25_ 岁*CRUD*工程师。

### 业务价值

软件系统的核心价值体现在业务价值上，研发人员不应只关注技术实现上，而是应该更多地关注业务价值的实现。
这其中的好处显而易见，当你开发完一个业务系统之后，你将变成一个业务专家，甚至比跟你合作的领域专家还要专业，因为你需要洞察业务细节。

使用 _Wow_ 框架，意味着你将关注点放在围绕领域模型设计上，与业务专家一起探索业务领域，而不是关注于技术实现上。
你仅需编写领域模型，即可完成服务开发，_Wow_ 框架自动为你准备好 _OpenAPI_ 接口。

> 在《实现领域驱动设计》一书中，作者 Vaughn Vernon 提到：核心域才值得投入精力进行领域驱动设计，
> 但如果你使用 _Wow_ 框架，你将发现，因为低廉开发成本、快速的开发效率，即使是次要的支撑子域也值得 _DDD_。

### 性能与伸缩性

随着业务的发展，你需要开始思考系统的性能和伸缩性问题。
在传统架构中，这牵扯到数据库关系模式、分片规则等复杂问题，同时你还需要处理因数据库分片导致的跨分片事务问题。
这时，你不得不修改你的业务代码，以适应水平拆分后的数据库架构。

然而，如果你选择使用 _Wow_ 框架，你将不再需要过多关注数据库关系模式、分片规则等问题。你的业务代码无需变更，系统能够轻松实现水平伸缩。

你可以在这里了解更多关于 [Wow 框架的性能](https://wow.ahoo.me/guide/perf-test.html)。

### 读写分离与同步延迟

读写分离是一种极为普遍的性能优化架构模式。
然而，同步延迟问题常伴随而来，事务执行成功后写库落库成功，但读库同步延迟，用户刷新页面后无法获取最新数据，从而对用户的体验产生影响。例如：

- 用户发起下单事务，写库执行成功，但由于某种原因，读库同步延迟，用户刷新页面后发现订单未成功创建。
- 商家编辑完商品后，同步到 _Elasticsearch_ 索引库，但由于某种原因，同步延迟，导致商家刷新页面后搜索不到该商品。

通常，大家采用最简便的方法，等待1秒后刷新页面。
虽然这种方式能解决大多数数据同步延迟的问题，但效率不够高。
因为大多数情况下，同步在100毫秒内就已完成，剩余的900毫秒成了*浪费*。
然而，有时1秒无法完成同步，这就导致用户获取的数据变得**无效**。

使用 _Wow_ 框架，你可以通过等待 _PROJECTED_ 信号完成，然后再将结果返回给用户，以更为优雅和高效的方式处理数据同步延迟的问题。

![WaitingForChain.gif](documentation/docs/public/images/wait/WaitingForChain.gif)

### 工程质量

*单元测试*是确保代码质量且符合预期业务需求的重要手段，但在传统架构中，单元测试往往是一项相当困难的任务，因为你需要考虑数据库连接、事务管理、数据清理等问题。

使用 _Wow_ 框架，你将会发现基于 _Given->When->Expect_ 模式的测试套件，使得单元测试变得异常简单。
你只需关注领域模型是否符合预期，而无需为数据库连接等问题烦恼。

> 在实际应用中，我们将领域模型的单元测试覆盖率下限阈值设置为 **85%**，也是可以轻松实现的。
> 
> 在没有刻意要求的情况下，开发人员甚至自觉地将覆盖率提升至 **95%**。
> 
> 因此，每次提交代码都变得轻松自在，因为你确信你的代码经过了充分的测试，并且真正意义上从单元测试中获得了收益。


在研发同级别的项目中，我们的测试团队在系统 _API_ 测试中发现，基于 Wow 框架的项目，其 _BUG_ 数仅为传统架构项目的 **1/3**。

你可以在这里了解更多关于 [Wow 单元测试套件](https://wow.ahoo.me/guide/test-suite.html)。

## 对于企业而言，_Wow_ 框架意味着什么？

### 商业智能

*商业智能*是企业决策的关键支持，而数据则是商业智能的分析原料。业务数据越为丰富有价值，商业智能的分析结果越准确，决策也就更加可靠。

与传统架构有着显著差异，_Wow_ 提供了实时聚合根状态事件（`StateEvent`）和聚合命令（`Command`）作为数据分析的数据源，同时极大降低了实时 _ETL_（`Extract`, `Transform`, `Load`）的难度。

在传统架构中，实现实时 _ETL_ 通常需要经过繁琐的流程，包括 `DB->CDC->Process->DB`，而在 _Wow_ 框架中，仅需一段简单的 _SQL_ 脚本即可完成这一过程。

另外，在传统架构中，使用 _CDC_（`MySql Binlog`）数据仅记录数据的变化，缺乏明确的业务语义。进行业务分析时，需要基于数据状态的变化推断出业务语义，这往往需要进行大量的数据处理。
相较之下，_Wow_ 框架直接提供了聚合根状态事件和聚合命令作为数据分析的数据源，极大降低了数据处理的难度。

_Wow_ 提供的实时同步机制将数据实时同步至数据仓库（_ClickHouse_），为实时数据分析提供了极大的便利。这种方法为商业智能提供了强有力的支持，构建了一个实时数据分析系统，使决策制定能够基于及时而准确的信息。

你可以在这里了解更多关于 [Wow 商业智能](https://wow.ahoo.me/guide/bi.html)。

### 操作审计

*操作审计*是企业中保障安全性和合规性的重要组成部分，同时也是对系统操作进行监控和追踪的关键手段。_Wow_ 框架在这方面为企业带来了显著的优势。

通过记录聚合命令（`Command`）作为操作审计的数据源，_Wow_ 框架能够详细追踪系统中的各种操作。
这些记录不仅包含了操作本身的内容，还涵盖了操作触发的副作用（_领域事件_），为审计提供了更为全面和准确的数据基础。

相较于传统审计方法，_Wow_ 框架的操作审计的数据源具备更加明确的业务语义，以及操作后产生的明确领域事件。

此外，Wow 框架提供的实时数据同步机制也为操作审计带来了便利，确保了审计数据的及时性和一致性。

了解更多关于 [Wow 操作审计](https://wow.ahoo.me/guide/bi.html#聚合命令)。