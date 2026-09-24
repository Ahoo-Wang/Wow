---
title: 'Identity and resource attribution'
description: 'Identity and resource attribution — @ahoo-wang/wow-client'
---

# Identity and resource attribution

Use these types when constructing an aggregate identity or an attribution path. They describe the wire fields; they do not infer identity from an authentication token or enforce permission checks. All fields below are required unless the exact declaration marks them optional. Interfaces supply no runtime defaults.

| Contract                                      | Meaning and constraint                                                                                                                            |
| --------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| `Identifier.id` / `Version.version`           | Generic string identity / numeric version. Neither generates an ID nor validates a version range.                                                 |
| `AggregateId`                                 | Flat `{aggregateId, tenantId, contextName, aggregateName}`. Match all four when correlating results; an ID string alone is not the full identity. |
| `AggregateIdCapable.aggregateId`              | Nests that **whole object**. Do not confuse this with the string field of `AggregateId`.                                                          |
| `NamedBoundedContext` / `AliasBoundedContext` | `contextName` is the model name; `contextAlias` is the routing alias. Named/AliasAggregate additionally require `aggregateName`.                  |
| `OwnerId`, `TenantId`, `SpaceIdCapable`       | Explicit string attribution fields.                                          |
| `UrlPathParams`                               | Optional tenant/owner/id plus custom string path slots. Supply the slots required by the chosen template.                                         |
| `ResourceAttributionPathSpec`                 | Empty, tenant, owner and tenant+owner route prefixes; templates do not establish authorization.                                                   |
| `AbacTags` / `AbacTaggable.tags`              | Map each tag key to a string array; empty tags `{}`, wildcard values `['*']`. The server defines how they affect policy.                          |
| `ApplyAbacTags` / `AbacTagsApplied`           | Command/event payloads containing `tags`; no local policy engine or automatic mutation.                                                           |
| `Named` / `DescriptionCapable`                | Required `name` / `description` strings, without formatting or nonempty validation.                                                               |

For an HTTP command combine the identity/path fields with [command options](./commands). Never substitute UI tenant filtering for server authorization.

## Exact contracts

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

Frozen: copy it before adding a tag.

[typescript/wow-client/src/types/abac.ts:17](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/abac.ts#L17)

### WILDCARD_ABAC_TAG_VALUES {#api-WILDCARD_ABAC_TAG_VALUES}

```ts
declare const WILDCARD_ABAC_TAG_VALUES: readonly string[];
```

Frozen: copy it before changing it.

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

[Complete symbol index](./symbols)
