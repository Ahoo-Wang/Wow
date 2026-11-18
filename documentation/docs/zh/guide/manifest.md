---
title: Manifest 与兼容
description: 服务的 Manifest 怎样构建和合并、用于什么、对端怎样得到它们那一部分，以及兼容检查怎样防止改变破坏客户端。
outline: deep
---

# Manifest 与兼容

服务的 Manifest 就是一份文档形式的契约：它的上下文、聚合、命令、事件、状态 schema、订阅、发布的事件流和错误码。路由、OpenAPI 文档、生成的客户端、等待计划和查询准入都从它推出。本页跟随它从编译器到服务、到对端、再到兼容检查。它为什么存在，见[一份契约：Manifest](../concepts/contract)。

## 从片段到服务的 Manifest {#from-fragments-to-the-service-s-manifest}

1. **编译器**写出每个领域模块的片段 `META-INF/wow/manifest/<module>.json`（[编译器生成什么](./generated-code#the-manifest-fragment)）。
2. **Gradle 插件**（`generateWowManifestFragment`）用片段中类型的 JSON Schema、查询关键字和状态的指纹补全片段。类型上的 Jakarta Bean Validation 约束成为它的 schema 的校验关键字，客户端在发送之前就能看到：`@Positive` 是值为 0 的 `exclusiveMinimum`，`@NotBlank` 是值为 1 的 `minLength`，`@Size` 是长度或条目数的上下界，`@Pattern` 是 `pattern`，`@Email` 是 `email` 格式。字段是否必填、能否为 null，仍然由它的 Kotlin 类型决定。补全后的片段替换模块资源中编译器写出的那个。
3. **在构建时**，`mergeWowManifest` 把服务运行时类路径上的片段合并成 `build/wow/manifest.json`。
4. **在启动时**，宿主合并同样的片段，加上框架的错误码，并在 `GET /wow/manifest` 上提供结果。

快照查询从补全后的片段读取状态 schema：只有插件补全之后，状态字段才能被过滤和排序。

## 由自定义序列化器写出的类型 {#a-type-written-by-a-custom-serializer}

插件通过询问 Jackson 怎样写出一个类来得知它的 schema。它看不到类自己的序列化器（`@JsonSerialize(using = …)`）或 `@JsonValue` 的内部，所以不去猜：`generateWowManifestFragment` 停下来，要求声明它的结构。对任何种类的类，包括接口、抽象类和枚举，只要 `@JsonSerialize` 给了它自己的序列化器或转换器（注解在它自己或某个超类型上），无论这个序列化器写出什么，构建也会停下来。

```text
Jackson does not write com.example.ledger.Ledger as an object (a custom serializer or @JsonValue), so its schema cannot be introspected: declare it in wow { schemaOverrides.put("com.example.ledger.Ledger", "<JSON Schema>") }.
```

在领域模块的 `build.gradle.kts` 中，以 JSON Schema 声明序列化器写出的结构。每个类用它的二进制名指明，所以嵌套类写作 `Outer$Inner`。状态类型和事件的声明方式相同：

<!-- sample: fragment -->

```kotlin
wow {
    // 状态，由 LedgerSerializer 写成 {"owner": "…", "lines": [12, 30]}
    schemaOverrides.put(
        "com.example.ledger.Ledger",
        """{"type":"object","properties":{"owner":{"type":"string"},"lines":{"type":"array","items":{"type":"integer","format":"int64"}}},"required":["owner","lines"]}""",
    )
    // 事件，由 PostedSerializer 写成 {"amount": 250}
    schemaOverrides.put(
        "com.example.ledger.Posted",
        """{"type":"object","properties":{"amount":{"type":"integer","format":"int64"}},"required":["amount"]}""",
    )
}
```

由自己的序列化器写出的属性，也就是属性上带 `using`、`converter`、`contentUsing` 或 `contentConverter` 的 `@JsonSerialize`，或者 `@JsonRawValue`，同样会让构建停下来。最常见的情形是写成字符串的 `Long` id，否则 Manifest 和生成的客户端会把它说成数字：

```text
Jackson writes com.example.ledger.Order#id with a serializer of its own (@JsonSerialize using, converter, contentUsing or contentConverter, or @JsonRawValue), so its schema cannot be introspected: declare it in wow { schemaOverrides.put("com.example.ledger.Order#id", "<JSON Schema>") }.
```

<!-- sample: fragment -->

```kotlin
wow {
    // Order 的 id，由 @field:JsonSerialize(using = ToStringSerializer::class) 写成 "1024"
    schemaOverrides.put("com.example.ledger.Order#id", """{"type":"string"}""")
}
```

属性持有一个平台类，而 Wow 的 JSON 把它写成字符串或数字时，构建同样会停下，除非 Manifest 按写出的样子描述这个类。它描述的是状态、命令和事件常持有的值类：`Year` 和 `Month`（它的序号，1 到 12）为整数，`YearMonth`、`MonthDay`、`ZoneOffset`、`TimeZone`、`Locale`、`Currency` 和 `URL` 为字符串，`ByteArray` 为它的 Base64 文本，此外还有日期、时间、时长、数字和 UUID。其他这样的类都会被拒绝，无论直接持有，还是放在集合、`Optional` 或映射的值中：`Charset`、`Path`、`AtomicLong`，以及列表中的 `kotlin.time.Duration`。请声明这个属性的 schema，或者把值存为 `String`：

```text
Wow's JSON does not write java.nio.charset.Charset, which com.example.ledger.Ledger#encoding holds, as an object, and it is no scalar Wow describes, so its schema would not be what is written: declare it in wow { schemaOverrides.put("com.example.ledger.Ledger#encoding", "<JSON Schema>") }, or hold it as a String.
```

键用二进制名指明一个类；或者指明一个属性：声明 Jackson 写出它所用的访问器的那个类，加上 `#`，再加上这个类声明的属性名，与它的 JSON 名无关。构建停下时会打印出确切的那一行。所以继承来的属性只由声明它的类指明一次，被某个类覆盖的属性由那个类指明，由接口上带注解的 getter 声明的属性则由实现它的每个类指明。只要键存在，它声明的 schema 就代表整个类，或者代表线上写出的整个属性，包括属性的列表元素和映射的值。生成器给声明的属性加上的，只有它给任何属性都会加上的东西：可以为 null 的属性在类型中加上 `null`，以及它的查询注解的关键字。属性的键优先于它的类型的键；在模块中没有指明任何东西的键会被忽略，所以约定插件可以共用一个 `wow { }` 块。键的序列化器（`keyUsing`）不需要声明：JSON 对象的键都是字符串，schema 不约束它们。

凡是读取 Manifest 的地方，声明的 schema 都代表这个类：
- 快照查询和事件查询可以按它的字段过滤、排序和投影，例如 `state.owner`；
- OpenAPI 和生成的客户端按它描述这个类；
- [兼容检查](#the-compatibility-check)按任何状态或事件 schema 的规则，把它与基线比较。

检查比较的是声明的结构，而不是序列化器写出的内容。两者要靠手工保持一致，没有任何东西拿一个去检查另一个。如果序列化器开始写出另一个字段，而它的声明没有改变，线上的格式就变了，检查却看不到。请在同一次提交中修改两者。

## 对端得到它们那一部分 {#peers-get-their-part}

发送另一个上下文的命令、或读取它发布的事件流的服务，需要那个上下文的命令、事件、状态和错误。插件从对方上下文的片段中挑出它们，即 `META-INF/wow/contract.json`，由它的 api 模块作为特性 `contract` 发布。依赖这个 api 模块的服务在类路径上找到这份契约，用它来指明它发送的命令、检查跨进对方上下文的等待计划，并解码对方发布的事件。不需要任何配置：依赖 api 就够了。

无法引入 api jar 的工具，例如控制台或网关，改为从对端的 `GET /wow/manifest` 读取它的 Manifest（[调用其他服务](./calling-services#call-a-command-by-name)）。

## 兼容检查 {#the-compatibility-check}

每个服务把合并后的 Manifest 作为基线 `manifest/baseline.json` 提交。基线记录服务的兼容线，即其版本的主版本号（`"line": 10`），而不是随每次发布变化的版本。`checkWowManifest`（`check` 的一部分）把每次构建合并出的 Manifest 与基线比较，并为每处差异归类：

| 类别 | 例子 | 在 `check` 中 |
| --- | --- | --- |
| 增量的 | 新的命令、事件或聚合；新的可选字段；放宽命令的约束 | 基线记录它之前失败 |
| 破坏性的 | 删除命令或事件；新的必填字段；删除字段或改变字段类型；收紧命令的约束，例如提高最小值或新增 `pattern`；删除事件或状态的字段、改变其类型或使其必填，无论修订号如何 | 在兼容线内让构建失败，并列出每处改变及其路径 |

关于约束的这两行说的是命令，它由客户端发送：更紧的约束会拒绝它们以前发送过的值。状态或事件的 schema 是读者读取的：每个聚合都有的事件查询的客户端，以及快照查询的客户端和发布的事件流的订阅者。所以对状态或事件，这两行反过来：在那里收紧约束是增量的，放宽约束是破坏性的；服务自己存储的记录从不按约束检查。删除字段、改变其类型或使其必填，无论修订号如何，都会破坏这些读者：新的修订号和它的 `@Upgrade` 只让服务自己的历史仍可读，而不是读者的。要在兼容线内改变一个事件，就在旧事件旁边新增一个事件，待它的读者都迁移之后再停用旧事件；否则就等下一个主版本。

数值的宽度是其类型的一部分。命令的字段从 `Long` 收窄为 `Int`（`format` 从 `int64` 变为 `int32`，或从 `Double` 变为 `Float`）会拒绝客户端以前发送过的数，是破坏性的；从 `Int` 放宽为 `Long` 是增量的。对状态或事件又反过来：放宽是破坏性的，因为生成为 `Int` 的读者容纳不了 `Long`；而服务自己存储的记录仍须能读回，所以收窄它们同样是破坏性的。手写 schema 的 `oneOf` 只接受恰好匹配一个分支的值，所以新增或放宽的分支若与另一个分支有相同的 JSON 类型（`integer` 也是 `number`），对命令就是破坏性的：客户端以前发送的值现在可能匹配两个分支。带判别字段的 `oneOf` 仍可增长：新分支所必填的判别属性若取一个其他分支都没有的 `const`（或 `enum` 值），就是增量的，因为没有值能同时匹配两个分支。`anyOf` 没有这条规则。

`build/wow/manifest-compatibility.txt` 列出每处改变，包括增量的改变。没有基线时 `check` 也会失败。有意的改变之后，`updateWowManifestBaseline` 记录新的基线：把它与改变一起提交，这样评审时就能看到客户端和对端会看到什么。每次失败都会写明这个任务。eShop 从基线重新生成它的 TypeScript 客户端，所以客户端与它的服务永远不会在不知不觉中偏离。

破坏性改变需要服务的下一个主版本；或者在旧的命令或事件旁边新增一个，待最后一个客户端迁移之后再让旧的退役。服务的主版本号等于基线的兼容线时，`checkWowManifest` 让破坏失败，`updateWowManifestBaseline` 拒绝记录它。版本的主版本号大于兼容线之后，更新会在同一次改变中记录这次破坏和新的兼容线。一个主版本首个版本的预发布版（例如 `11.0.0-beta.1`）让它的兼容线保持开放：它可以记录破坏，因为这条线上还没有发布过任何东西。其他预发布版只有带上 `-Pwow.manifest.allowBreaking` 才能记录破坏，更新的报告 `build/wow/manifest-baseline.txt` 会记下这个标志和它放过的改变。

## 谁读取 Manifest {#what-reads-the-manifest}

| 读者 | 用于 |
| --- | --- |
| 宿主 | 它的路由、等待计划、查询准入（归属、schema）、内部端口的调用权限 |
| OpenAPI 文档 | 每个路由及其请求和响应的 schema（[OpenAPI 与 springdoc](./openapi)） |
| TypeScript 生成器 | 类型化的命令客户端和查询客户端 |
| 视图引擎 | 视图可以使用的字段和能力 |
| 对端 | 它们与这个上下文之间的契约，经由它的 api 模块的契约 |
| ops 控制台和 migrate-data | 按名字使用命令、订阅和类型 |
