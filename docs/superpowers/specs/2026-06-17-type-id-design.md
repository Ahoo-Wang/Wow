# TypeId 设计

## 背景

Wow 当前在领域事件序列化时，会把 `bodyType` 写成事件体的 JVM 全限定类名。反序列化时再通过 `Class.forName()` 解析这个值，并把 JSON `body` 转换成解析到的运行时类型。这在类名和包结构稳定的单 JVM 代码库里可以工作，但会把长期保存的事件记录绑定到包名、类名和模块结构上。

现有模型其实已经有更稳定的领域元数据：

- `contextName`
- `aggregateName`
- `name`
- `revision`

`TypeId` 的目标，是把这类稳定身份显式化为框架内部概念和使用规约。第一阶段不新增 `typeId` 持久化字段，而是从现有事件记录字段派生 TypeId。

## 决策

引入 `TypeId`，把它作为消息契约的稳定身份，但不直接写入事件 JSON。

`TypeId` 不是 Java 类别名。它标识的是载荷遵循的领域消息契约；运行时类只是在契约身份解析之后用于对象转换的实现细节。

对领域事件来说，TypeId 应从以下字段派生：

```text
EventTypeId = event://{contextName}/{aggregateName}/{name}
```

`revision` 保持独立，用来标识该契约的结构版本。运行时解析使用：

```text
EventTypeKey = EventTypeId + revision
```

## 概念

| 概念 | 含义 | 稳定性 |
| --- | --- | --- |
| `EventTypeId` | 由 `contextName + aggregateName + name` 派生的稳定事件契约身份，例如 `event://sales/order/order_created` | 稳定 |
| `EventTypeKey` | `EventTypeId + revision`，用于定位具体契约版本 | 稳定身份 + 有意演进版本 |
| `revision` | 契约结构版本，例如 `1.0.0` | 有意演进 |
| `bodyType` | 运行时 JVM 类名，例如 `me.ahoo.example.OrderCreated` | 历史兼容/运行时提示 |
| `name` | Wow 现有消息名，通常来自 `@Name` 或类名转换 | 作为事件契约身份的一部分，进入事件日志后应稳定 |

## 范围

第一阶段实现应聚焦领域事件和事件流，因为事件记录生命周期长，并且直接影响事件重放。

命令后续也可以采用同一个概念，但命令消息通常生命周期更短。第一阶段支持命令是可选项，不应阻塞事件溯源侧的解耦。

## 序列化形态

第一阶段不改变事件 JSON 形态，不新增 `typeId` 字段。新的事件记录继续使用现有字段：

```json
{
  "id": "event-id",
  "contextName": "sales",
  "aggregateName": "order",
  "name": "order_created",
  "revision": "1.0.0",
  "bodyType": "me.ahoo.example.OrderCreated",
  "body": {}
}
```

运行时根据 `contextName`、`aggregateName`、`name` 派生：

```text
event://sales/order/order_created
```

`bodyType` 继续保留。它仍可帮助旧消费者，也可以在新的注册表无法通过派生 TypeId 解析事件时作为 fallback。

## 解析顺序

领域事件反序列化应按以下顺序解析事件体类型：

1. 从 `contextName + aggregateName + name` 派生 `EventTypeId`，并结合 `revision` 形成 `EventTypeKey`。
2. 优先通过注册表解析 `EventTypeKey`。
3. 如果没有精确的 `revision` 匹配，则按 `EventTypeId` 解析，并允许事件升级器在对象转换前迁移 `body`。
4. 如果派生 TypeId 无法解析，则 fallback 到历史 `bodyType`。
5. 如果两条路径都失败，则保留为 `JsonDomainEvent`。

这样既能继续读取旧事件记录，也能让新记录走稳定的契约优先路径。

## 注册表

引入一个注册表，把稳定身份映射到运行时类型。

对事件来说：

```text
EventTypeId(contextName, aggregateName, name) -> EventTypeDescriptor
```

`EventTypeDescriptor` 至少应包含当前运行时类和当前 `revision`。如果 Wow 后续需要更严格的版本协商，可以再扩展支持的 revision 元数据。

注册表不应要求业务代码逐个事件手动注册。框架运行时应从现有 `WowMetadata` 的事件类型集合和事件元数据自动构建注册表；手动 `register` 只作为测试、扩展 SPI 或特殊集成入口。

注册表必须检测同一个 `EventTypeId` 的重复映射并快速失败，因为两个运行时类声明同一个契约身份会让事件重放变得歧义。

## 兼容性

第一阶段不新增 `typeId` 字段，因此不会改变事件存储、Schema、OpenAPI 或 BI 的字段形态。

现有记录必须继续可读：

- 能派生 `EventTypeId` 的记录，优先走契约身份注册表。
- 无法通过契约身份解析的记录，继续通过 `bodyType` 反序列化。

第一阶段不应从现有 API 和 schema 中移除 `bodyType`，也不应要求 BI 脚本新增 `type_id`。如果未来跨语言或跨系统交换需要显式暴露契约身份，再考虑把派生值序列化为字段。

## 事件升级

事件升级器当前在对象转换前运行，并且可以修改 `bodyType`、`name`、`revision` 和 `body`。

引入派生 TypeId 后，升级器无需依赖额外字段。它仍通过修改 `name`、`revision`、`bodyType` 或 `body` 来完成迁移。这可以支持：

- 重命名事件契约
- 把旧事件契约拆分为 dropped 事件或替代契约
- 从历史类名身份迁移到稳定契约身份

升级器查找应继续使用现有稳定元组：

```text
contextName + aggregateName + name
```

这样可以避免要求旧记录必须先拥有新增字段才能运行升级器。

## 错误处理

未知派生 TypeId 不应导致查询或传输场景崩溃。应生成 `JsonDomainEvent`，并保留：

- 原始 `contextName`
- 原始 `aggregateName`
- 原始 `name`
- 原始 `bodyType`，如果存在
- `revision`
- JSON 形态的 `body`

重放敏感路径应继续遵循当前语义：如果 `body` 仍是 `JsonNode`，按运行时事件类索引的普通 sourcing 函数不会执行。只有在注册表和升级器都无法解析事件时，这种行为才是可接受的。

## 测试

需要覆盖的聚焦测试：

- 新事件序列化不新增 `typeId` 字段，并继续保留 `bodyType`
- 事件流反序列化能从 `contextName + aggregateName + name` 派生 `EventTypeId`
- 反序列化在派生 TypeId 和 `bodyType` 指向不同类型时优先使用派生 TypeId
- 无法通过派生 TypeId 解析的历史记录仍通过 `bodyType` 反序列化
- 未知派生 TypeId 且未知 `bodyType` 时保留为 `JsonDomainEvent`
- 事件升级器可以通过修改 `name` 或 `revision` 影响派生 TypeId/TypeKey
- 重复注册表条目快速失败
- schema/OpenAPI/BI 在第一阶段不需要新增 `typeId` 字段

## 迁移路径

1. 定义派生 TypeId 规约，不新增 `typeId` 字段。
2. 框架从 `WowMetadata` 自动注册事件类。
3. 让反序列化优先使用派生 TypeId。
4. 派生 TypeId 无法解析时继续 fallback 到 `bodyType`。
5. 明确约束：`contextName + aggregateName + name` 一旦进入事件日志，就视为事件契约身份，不能随意改名。
6. 如果未来需要显式跨语言契约 ID，再单独设计 `typeId` 字段迁移。

## 非目标

- 第一阶段不移除 `bodyType`。
- 第一阶段不新增 `typeId` 持久化字段。
- 不重写已经持久化的事件。
- 不把 TypeId 做成 JVM FQCN 的直接别名。
- 第一阶段不强制命令一起迁移。
