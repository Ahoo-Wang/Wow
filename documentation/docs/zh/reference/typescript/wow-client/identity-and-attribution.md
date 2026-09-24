---
title: '身份与资源归属'
description: '身份与资源归属 — @ahoo-wang/wow-client'
---

# 身份与资源归属

构造聚合身份或资源归属路径时使用这些类型。它们描述传输字段，不从认证令牌推断身份，也不执行权限检查。下表字段除精确声明标记为可选外均必填；接口不提供运行时默认值。

| 契约                                          | 含义与约束                                                                                                     |
| --------------------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| `Identifier.id` / `Version.version`           | 通用字符串身份 / 数字版本；不会生成 ID 或验证版本范围。                                                        |
| `AggregateId`                                 | 平铺的 `{aggregateId, tenantId, contextName, aggregateName}`。关联结果时应匹配四项，仅 ID 字符串不是完整身份。 |
| `AggregateIdCapable.aggregateId`              | 嵌套上述**完整对象**，不要与 `AggregateId` 中同名的字符串字段混淆。                                            |
| `NamedBoundedContext` / `AliasBoundedContext` | `contextName` 是模型名；`contextAlias` 是路由别名。Named/AliasAggregate 还要求 `aggregateName`。               |
| `OwnerId`、`TenantId`、`SpaceIdCapable`       | 显式字符串归属字段。                              |
| `UrlPathParams`                               | 可选 tenant/owner/id 及自定义字符串路径槽位；必须提供所选模板所需的槽位。                                      |
| `ResourceAttributionPathSpec`                 | 无归属、租户、所有者及租户+所有者路径前缀；模板不构成授权。                                                    |
| `AbacTags` / `AbacTaggable.tags`              | 每个标签键对应字符串数组；空标签 `{}`，通配值 `['*']`；策略含义由服务端定义。                                  |
| `ApplyAbacTags` / `AbacTagsApplied`           | 含 `tags` 的命令/事件载荷；不包含本地策略引擎或自动修改行为。                                                  |
| `Named` / `DescriptionCapable`                | 必填 `name` / `description` 字符串，不验证格式或非空。                                                         |

发送 HTTP 命令时将身份/路径字段与[命令选项](./commands)组合。不能用 UI 租户过滤代替服务端授权。

## 精确契约

### AbacTagKey {#api-AbacTagKey}

```ts
export type AbacTagKey = string;
```

[typescript/wow-client/src/types/abac.ts:13](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/abac.ts#L13)

### AbacTagValue {#api-AbacTagValue}

```ts
export type AbacTagValue = string[];
```

[typescript/wow-client/src/types/abac.ts:14](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/abac.ts#L14)

### AbacTags {#api-AbacTags}

```ts
export type AbacTags = Record<AbacTagKey, AbacTagValue>;
```

[typescript/wow-client/src/types/abac.ts:15](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/abac.ts#L15)

### EMPTY_ABAC_TAGS {#api-EMPTY_ABAC_TAGS}

```ts
declare const EMPTY_ABAC_TAGS: Readonly<AbacTags>;
```

已冻结：添加标签前先复制。

[typescript/wow-client/src/types/abac.ts:17](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/abac.ts#L17)

### WILDCARD_ABAC_TAG_VALUES {#api-WILDCARD_ABAC_TAG_VALUES}

```ts
declare const WILDCARD_ABAC_TAG_VALUES: readonly string[];
```

已冻结：修改前先复制。

[typescript/wow-client/src/types/abac.ts:19](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/abac.ts#L19)

### AbacTaggable {#api-AbacTaggable}

```ts
export interface AbacTaggable {
  tags: AbacTags;
}
```

[typescript/wow-client/src/types/abac.ts:21](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/abac.ts#L21)

### ApplyAbacTags {#api-ApplyAbacTags}

```ts
export interface ApplyAbacTags extends AbacTaggable {}
```

[typescript/wow-client/src/types/abac.ts:26](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/abac.ts#L26)

### AbacTagsApplied {#api-AbacTagsApplied}

```ts
export interface AbacTagsApplied extends AbacTaggable {}
```

[typescript/wow-client/src/types/abac.ts:29](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/abac.ts#L29)

### Identifier {#api-Identifier}

```ts
export interface Identifier {
  id: string;
}
```

[typescript/wow-client/src/types/common.ts:17](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/common.ts#L17)

### Version {#api-Version}

```ts
export interface Version {
  version: number;
}
```

[typescript/wow-client/src/types/common.ts:31](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/common.ts#L31)

### UrlPathParams {#api-UrlPathParams}

```ts
export interface UrlPathParams {
  tenantId?: string;
  ownerId?: string;
  id?: string;
  [key: string]: string | undefined;
}
```

[typescript/wow-client/src/types/endpoints.ts:19](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/endpoints.ts#L19)

### ResourceAttributionPathSpec {#api-ResourceAttributionPathSpec}

```ts
export enum ResourceAttributionPathSpec {
  NONE = '',
  TENANT = '/tenant/{tenantId}',
  OWNER = '/owner/{ownerId}',
  TENANT_OWNER = '/tenant/{tenantId}/owner/{ownerId}',
}
```

[typescript/wow-client/src/types/endpoints.ts:57](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/endpoints.ts#L57)

### AggregateNameCapable {#api-AggregateNameCapable}

```ts
export interface AggregateNameCapable {
  aggregateName: string;
}
```

[typescript/wow-client/src/types/modeling.ts:79](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/modeling.ts#L79)

### NamedAggregate {#api-NamedAggregate}

```ts
export interface NamedAggregate
  extends NamedBoundedContext, AggregateNameCapable {}
```

[typescript/wow-client/src/types/modeling.ts:89](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/modeling.ts#L89)

### AliasAggregate {#api-AliasAggregate}

```ts
export interface AliasAggregate
  extends AliasBoundedContext, AggregateNameCapable {}
```

[typescript/wow-client/src/types/modeling.ts:92](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/modeling.ts#L92)

### AggregateId {#api-AggregateId}

```ts
export interface AggregateId extends TenantId, NamedAggregate {
  aggregateId: string;
}
```

[typescript/wow-client/src/types/modeling.ts:98](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/modeling.ts#L98)

### AggregateIdCapable {#api-AggregateIdCapable}

```ts
export interface AggregateIdCapable {
  aggregateId: AggregateId;
}
```

[typescript/wow-client/src/types/modeling.ts:109](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/modeling.ts#L109)

### OwnerId {#api-OwnerId}

```ts
export interface OwnerId {
  ownerId: string;
}
```

[typescript/wow-client/src/types/modeling.ts:132](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/modeling.ts#L132)

### SpaceIdCapable {#api-SpaceIdCapable}

```ts
export interface SpaceIdCapable {
  spaceId: string;
}
```

[typescript/wow-client/src/types/modeling.ts:139](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/modeling.ts#L139)

### TenantId {#api-TenantId}

```ts
export interface TenantId {
  tenantId: string;
}
```

[typescript/wow-client/src/types/modeling.ts:156](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/modeling.ts#L156)

### NamedBoundedContext {#api-NamedBoundedContext}

```ts
export interface NamedBoundedContext {
  contextName: string;
}
```

[typescript/wow-client/src/types/naming.ts:17](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/naming.ts#L17)

### AliasBoundedContext {#api-AliasBoundedContext}

```ts
export interface AliasBoundedContext {
  contextAlias: string;
}
```

[typescript/wow-client/src/types/naming.ts:21](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/naming.ts#L21)

### Named {#api-Named}

```ts
export interface Named {
  name: string;
}
```

[typescript/wow-client/src/types/naming.ts:28](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/naming.ts#L28)

### DescriptionCapable {#api-DescriptionCapable}

```ts
export interface DescriptionCapable {
  description: string;
}
```

[typescript/wow-client/src/types/naming.ts:39](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/naming.ts#L39)

[完整符号索引](./symbols)
