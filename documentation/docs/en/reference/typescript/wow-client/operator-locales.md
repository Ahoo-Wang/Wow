---
title: 'Legacy operator locales'
description: 'Legacy operator locales — @ahoo-wang/wow-client'
---

# Legacy operator locales

The two dictionaries are label dictionaries for the deprecated legacy `Condition` operators, exported by the `@ahoo-wang/wow-client/legacy` subpath. They are not exported by the package root and do not change request serialization, server behavior or UI language. Each dictionary maps every `Operator` member to a string; there is no fallback language resolver or provider.

## en_US {#api-en_US}

```ts
import { en_US } from '@ahoo-wang/wow-client/legacy';
console.log(en_US.EQ); // Equals
```

[typescript/wow-client/src/legacy/locale/en_US.ts:17](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/locale/en_US.ts#L17)

## zh_CN {#api-zh_CN}

```ts
import { zh_CN } from '@ahoo-wang/wow-client/legacy';
console.log(zh_CN.EQ);
```

[typescript/wow-client/src/legacy/locale/zh_CN.ts:17](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/locale/zh_CN.ts#L17)

[Dictionary type OperatorLocale](./filters#api-OperatorLocale) · [Condition builders](./filters)

Both dictionaries are declared as `export const <locale>: OperatorLocale`. The full key set is defined by [Operator](./filters#api-Operator); values are display labels and do not modify condition semantics. Neither has a default export; the former `/query/locale/en_US` and `/query/locale/zh_CN` subpaths no longer exist.
