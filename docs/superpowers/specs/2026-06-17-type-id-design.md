# TypeId 设计

## 背景

Wow 当前在领域事件序列化时，会把 `bodyType` 写成事件体的 JVM 全限定类名。反序列化时再通过 `Class.forName()` 解析这个值，并把 JSON `body` 转换成解析到的运行时类型。这在类名和包结构稳定的单 JVM 代码库里可以工作，但会把长期保存的事件记录绑定到包名、类名和模块结构上。

现有模型其实已经有更稳定的领域元数据：

- `contextName`
- `aggregateName`
- `name`
- `revision`

`TypeId` 的目标，是把这类稳定身份显式化，同时继续兼容只包含 `bodyType` 的历史记录。

## 决策

引入 `TypeId`，把它作为消息契约的稳定身份。

`TypeId` 不是 Java 类别名。它标识的是载荷遵循的领域消息契约；运行时类只是在契约身份解析之后用于对象转换的实现细节。

对领域事件来说，规范身份应从以下字段派生：

```text
contextName + aggregateName + name
```

`revision` 保持独立，用来标识该契约的结构版本。

## 概念

| 概念 | 含义 | 稳定性 |
| --- | --- | --- |
| `typeId` | 稳定的消息契约身份，例如 `event://sales/order/order_created` | 稳定 |
| `revision` | 契约结构版本，例如 `1.0.0` | 有意演进 |
| `bodyType` | 运行时 JVM 类名，例如 `me.ahoo.example.OrderCreated` | 历史兼容/运行时提示 |
| `name` | Wow 现有消息名，通常来自 `@Name` 或类名转换 | 用户将其视为契约名时稳定 |

## 范围

第一阶段实现应聚焦领域事件和事件流，因为事件记录生命周期长，并且直接影响事件重放。

命令后续也可以采用同一个概念，但命令消息通常生命周期更短。第一阶段支持命令是可选项，不应阻塞事件溯源侧的解耦。

## 序列化形态

新的事件记录应在现有字段之外写入 `typeId`：

```json
{
  "id": "event-id",
  "name": "order_created",
  "revision": "1.0.0",
  "typeId": "event://sales/order/order_created",
  "bodyType": "me.ahoo.example.OrderCreated",
  "body": {}
}
```

兼容窗口内应保留 `bodyType`。它仍可帮助旧消费者，也可以在新的注册表无法解析 `typeId` 时作为 fallback。

## 解析顺序

领域事件反序列化应按以下顺序解析事件体类型：

1. 如果存在 `typeId`，优先通过注册表解析 `typeId + revision`。
2. 如果没有精确的 `revision` 匹配，则按 `typeId` 解析，并允许事件升级器在对象转换前迁移 `body`。
3. 如果 `typeId` 缺失或无法解析，则 fallback 到历史 `bodyType`。
4. 如果两条路径都失败，则保留为 `JsonDomainEvent`。

这样既能继续读取旧事件记录，也能让新记录走稳定的契约优先路径。

## 注册表

引入一个注册表，把稳定身份映射到运行时类型。

对事件来说：

```text
EventTypeId(contextName, aggregateName, name) -> EventTypeDescriptor
```

`EventTypeDescriptor` 至少应包含当前运行时类和当前 `revision`。如果 Wow 后续需要更严格的版本协商，可以再扩展支持的 revision 元数据。

注册表可以从现有事件元数据扫描构建。后续 KSP 生成的 `WowMetadata` 可以包含显式的 type-id 条目，从而减少运行时反射查找。

注册表必须检测同一个 `typeId` 的重复映射并快速失败，因为两个运行时类声明同一个契约身份会让事件重放变得歧义。

## 兼容性

不包含 `typeId` 的历史记录必须继续通过 `bodyType` 反序列化。

第一阶段不应从现有 API 和 schema 中移除 `bodyType`。Schema 和 OpenAPI 生成可以在兼容策略明确之后，针对新的 typed message schema 把 `typeId` 加为 required 字段。更稳妥的第一步，是生成的 schema 先把 `typeId` 暴露为 optional，同时序列化器对新记录写入该字段。

BI 脚本应继续提取 `bodyType`，并可在后续迁移中新增 `type_id`。

## 事件升级

事件升级器当前在对象转换前运行，并且可以修改 `bodyType`、`name`、`revision` 和 `body`。

引入 `TypeId` 后，升级器也应能修改 `typeId`。这可以支持：

- 重命名事件契约
- 把旧事件契约拆分为 dropped 事件或替代契约
- 从历史类名身份迁移到稳定契约身份

升级器查找应继续使用现有稳定元组：

```text
contextName + aggregateName + name
```

这样可以避免要求旧记录必须先拥有 `typeId` 才能运行升级器。

## 错误处理

未知 `typeId` 不应导致查询或传输场景崩溃。应生成 `JsonDomainEvent`，并保留：

- 原始 `typeId`，如果存在
- 原始 `bodyType`，如果存在
- `name`
- `revision`
- JSON 形态的 `body`

重放敏感路径应继续遵循当前语义：如果 `body` 仍是 `JsonNode`，按运行时事件类索引的普通 sourcing 函数不会执行。只有在注册表和升级器都无法解析事件时，这种行为才是可接受的。

## 测试

需要覆盖的聚焦测试：

- 新事件序列化写入 `typeId`，同时保留 `bodyType`
- 事件流序列化为每个事件写入 `typeId`
- 反序列化在 `typeId` 和 `bodyType` 不一致时优先使用 `typeId`
- 不包含 `typeId` 的历史记录仍通过 `bodyType` 反序列化
- 未知 `typeId` 且未知 `bodyType` 时保留为 `JsonDomainEvent`
- 事件升级器可以修改 `typeId`
- 重复注册表条目快速失败
- schema 生成要么保持兼容，要么显式更新快照以包含新字段

## 迁移路径

1. 增加 `typeId` 支持，但不移除 `bodyType`。
2. 按稳定事件身份注册事件类。
3. 让反序列化优先使用 `typeId`。
4. 按兼容策略更新 schema/OpenAPI 快照，使其包含 `typeId`。
5. 为 BI 新增 `type_id` 提取，同时保留 `body_type`。
6. 只有在外部消费者完成迁移后，才考虑废弃 `bodyType`。

## 非目标

- 第一阶段不移除 `bodyType`。
- 不重写已经持久化的事件。
- 不把 TypeId 做成 JVM FQCN 的直接别名。
- 第一阶段不强制命令一起迁移。
