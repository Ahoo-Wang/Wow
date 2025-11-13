# Wow API

[![Maven Central](https://img.shields.io/maven-central/v/me.ahoo.wow/wow-api)](https://central.sonatype.com/artifact/me.ahoo.wow/wow-api)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

Wow 框架的核心 API 定义，提供用于构建 CQRS/事件溯源应用程序的抽象和注解。

## 概述

Wow API 是 Wow 框架的基础库，提供了一套完整的接口、注解和抽象，用于在 Kotlin 应用程序中实现命令查询职责分离 (CQRS) 和事件溯源模式。

### 主要特性

- **注解驱动**: 丰富的注解用于声明聚合、命令、事件和处理器
- **类型安全建模**: 强类型的聚合、事件和命令抽象
- **消息基础设施**: 内置的消息头、主题和函数元数据支持
- **查询能力**: 灵活的查询 API，支持分页、排序和动态投影
- **异常处理**: 标准化的错误信息和可恢复异常类型
- **多租户**: 内置的租户隔离和所有权支持

## 模块

### 注解 (`me.ahoo.wow.api.annotation`)

核心注解用于声明领域组件：

- **聚合注解**: `@AggregateRoot`, `@AggregateId`, `@AggregateName`, `@AggregateVersion`
- **命令注解**: `@OnCommand`, `@CommandRoute`, `@CreateAggregate`
- **事件注解**: `@OnEvent`, `@Event`, `@EventProcessor`
- **投影注解**: `@ProjectionProcessor`, `@StatelessSaga`
- **工具注解**: `@Retry`, `@Order`, `@BoundedContext`

### 命令 (`me.ahoo.wow.api.command`)

命令相关的抽象和验证：

- `CommandMessage`: 带有元数据的命令表示
- `CommandId`, `RequestId`: 命令的唯一标识符
- `CommandResultAccessor`: 命令执行结果访问
- 命令验证框架

### 事件 (`me.ahoo.wow.api.event`)

事件溯源抽象：

- `DomainEvent`: 核心领域事件接口
- `AggregateDeleted`, `AggregateRecovered`: 生命周期事件
- `@IgnoreSourcing`: 注解用于从溯源中排除事件
- `Revision`: 事件版本控制支持

### 异常 (`me.ahoo.wow.api.exception`)

标准化的错误处理：

- `ErrorInfo`: 结构化的错误信息
- `ErrorInfoCapable`: 可提供错误信息的对象接口
- `RecoverableType`: 可重试异常的分类

### 消息 (`me.ahoo.wow.api.messaging`)

消息处理基础设施：

- `Message`: 带有头的消息基接口
- `Header`: 消息头工具
- `TopicKind`: 消息路由分类
- 函数元数据: `FunctionInfo`, `FunctionKind`, 处理器信息

### 建模 (`me.ahoo.wow.api.modeling`)

领域建模抽象：

- `AggregateId`: 带有租户/所有者支持的聚合标识符
- `NamedAggregate`: 带有上下文信息的命名聚合
- 状态能力: `StateCapable`, `DeletedCapable`, `OperatorCapable`
- 时间能力: `CreateTimeCapable`, `EventTimeCapable`

### 命名 (`me.ahoo.wow.api.naming`)

命名和限定工具：

- `Named`: 基本命名接口
- `QualifiedNamed`: 带有上下文的限定命名
- `NamedBoundedContext`: 限界上下文命名
- 状态能力: `EnabledCapable`, `CompletedCapable`

### 查询 (`me.ahoo.wow.api.query`)

查询和投影 API：

- 查询类型: `SingleQuery`, `ListQuery`, `PagedQuery`
- 条件: `Condition`, `RewritableCondition`
- 投影: `RewritableProjection`, `DynamicDocument`
- 结果: `PagedList`, `MaterializedSnapshot`

## 快速开始

### 基本聚合定义

```kotlin
@AggregateRoot
class Order {
    @AggregateId
    lateinit var id: String

    @AggregateVersion
    var version: Int = 0

    var status: OrderStatus = OrderStatus.CREATED
    var items: List<OrderItem> = emptyList()

    @OnCommand
    fun create(@Valid command: CreateOrderCommand): OrderCreatedEvent {
        // 命令处理逻辑
        return OrderCreatedEvent(command.items)
    }

    @OnEvent
    fun onCreated(event: OrderCreatedEvent) {
        this.items = event.items
        this.status = OrderStatus.CONFIRMED
    }
}
```

### 命令定义

```kotlin
@ValueObject
data class CreateOrderCommand(
    @AggregateId
    val orderId: String,
    val items: List<OrderItem>
)
```

### 事件定义

```kotlin
@Event
data class OrderCreatedEvent(
    val items: List<OrderItem>
)
```

### 查询使用

```kotlin
val query = PagedQuery(
    condition = Condition.and(
        Condition.eq("status", OrderStatus.CONFIRMED),
        Condition.gt("createTime", yesterday)
    ),
    sort = listOf(SortCapable.asc("createTime")),
    pagination = Pagination(0, 20)
)

val results = orderQueryService.paged(query)
```

## 安装

### Gradle

```kotlin
dependencies {
    implementation("me.ahoo.wow:wow-api:6.5.1")
}
```

### Maven

```xml
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-api</artifactId>
    <version>6.5.1</version>
</dependency>
```