---
title: Wow 编译器
description: wow-compiler 的 KSP 输入、三类输出、运行时消费者与验证边界。
outline: deep
---

# Wow 编译器

`wow-compiler` 是 KSP 处理器集合。它把限界上下文与聚合注解转换成机器可读元数据和 Kotlin 常量，让运行时、查询和接口模块消费同一份模型声明。

它**不直接生成 OpenAPI 文档或 HTTP 路由**。`wow-openapi`、`wow-webflux` 等运行时/构建组件会消费编译元数据并组装各自输出。

## 安装

```kotlin
plugins {
    id("com.google.devtools.ksp")
}

dependencies {
    ksp("me.ahoo.wow:wow-compiler")
}
```

版本必须与应用的 Wow/Kotlin/KSP 组合对齐。具体依赖与 capability 选择见[接入现有项目](../existing-project.md)，本页不重复完整 Gradle 设置。

## 处理器与输出

三个 `SymbolProcessorProvider` 通过 ServiceLoader 注册：

| 处理器 | 主要输入 | 输出 | 消费者 |
| --- | --- | --- | --- |
| `MetadataSymbolProcessor` | `@BoundedContext`、`@AggregateRoot` 及解析出的命令/事件 | `META-INF/wow-metadata.json` | `MetadataSearcher`、聚合解析、路由/Schema/OpenAPI 组件 |
| `AggregatesMetadataProcessor` | 限界上下文与其包范围内的聚合 | 同 package 的 `AggregatesMetadata.kt` | 应用/框架需要类型安全聚合元数据时使用 |
| `QuerySymbolProcessor` | 聚合的状态类型与嵌套属性 | `<StateType>Properties.kt` | 查询条件、排序和字段引用 |

这些是构建输出，不应手工编辑或提交。修改注解/领域类型后重新运行 KSP，让生成结果随源码更新。

## `META-INF/wow-metadata.json`

资源按 context name 保存：

- context alias、description 与 package scopes；
- aggregate name、command/state type scopes；
- 可选静态 tenant ID 与 ID generator name；
- 命令和事件类型名称集合。

`MetadataSymbolProcessor` 以 aggregating KSP output 写入该资源。运行时 `MetadataSearcher` 从 classpath 查找所有同名资源并合并；缺失某个模块的资源时，该模块的聚合/命令/事件不会凭反射自动完整恢复。

检查应用 artifact，而不是只检查源码目录：

```bash
jar tf build/libs/<application>.jar | grep 'META-INF/wow-metadata.json'
```

最终路径和 JAR 名由应用构建决定；示例中的占位符不是可直接复制的固定命令。

## `AggregatesMetadata.kt`

生成对象位于限界上下文 package，并为解析到的每个聚合提供类型化值：

```kotlin
object AggregatesMetadata {
    val OrderAggregateMetadata = aggregateMetadata<Order, OrderState>()
}
```

单类聚合的 command/state 类型相同；组合模式会保留两个不同类型参数。该文件方便代码引用已解析元数据，不是新的公共业务 API，也不替代 `wow-metadata.json`。

## 查询属性常量

`QuerySymbolProcessor` 遍历状态聚合属性，为非 Kotlin/Java 简单类型继续生成嵌套导航：

```kotlin
object OrderStateProperties {
    const val ID = "id"
    const val SHIPPING_ADDRESS = "shippingAddress"
    const val SHIPPING_ADDRESS__CITY = "shippingAddress.city"
}
```

常量名使用大写 snake case，嵌套常量以 `__` 分隔，值使用点路径。递归解析用已添加集合停止重复属性导航；生成常量只减少手写拼写错误，不证明目标存储存在该字段或索引。

## 编译期与运行期失败

| 症状 | 优先检查 |
| --- | --- |
| 没有 `wow-metadata.json` | domain 模块是否应用 KSP、是否包含可解析注解、资源是否打入最终 artifact |
| 缺少某个聚合 | package scope、聚合注解、模块依赖与最终 classpath |
| Properties 常量过期 | clean 后重新运行 KSP，确认使用的是当前生成目录 |
| 运行时无法解析聚合元数据 | classpath 中资源合并结果与实际聚合 class 是否一致 |
| OpenAPI/route 缺失 | 先确认元数据，再检查 `wow-openapi`/`wow-webflux` 的 capability、条件与运行时注册 |

不要把生成目录缺失直接修成手写 metadata 文件；根因通常是 KSP 没运行、模块没被依赖或输出没打包。

## 验证

仓库编译测试直接检查三类输出：

```bash
./gradlew :wow-compiler:test --tests "me.ahoo.wow.compiler.metadata.MetadataSymbolProcessorTest"
./gradlew :wow-compiler:test --tests "me.ahoo.wow.compiler.aggregate.metadata.AggregatesMetadataSymbolProcessorTest"
./gradlew :wow-compiler:test --tests "me.ahoo.wow.compiler.query.QuerySymbolProcessorTest"
```

应用验收还应检查最终 JAR 与实际 OpenAPI/route；编译器测试不证明应用已请求相应运行时 capability。

## 源码与相关页面

- [`MetadataSymbolProcessor`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-compiler/src/main/kotlin/me/ahoo/wow/compiler/metadata/MetadataSymbolProcessor.kt)
- [`AggregatesMetadataProcessor`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-compiler/src/main/kotlin/me/ahoo/wow/compiler/aggregate/metadata/AggregatesMetadataProcessor.kt)
- [`QuerySymbolProcessor`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-compiler/src/main/kotlin/me/ahoo/wow/compiler/query/QuerySymbolProcessor.kt)
- [OpenAPI](../open-api.md)：元数据如何进入接口合同
- [JSON Schema](./schema.md)：Schema 生成职责
