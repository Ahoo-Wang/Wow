---
title: '旧条件操作符语言包'
description: '旧条件操作符语言包 — @ahoo-wang/wow-client'
---

# 旧条件操作符语言包

两个子路径导出是已弃用旧 `Condition` 操作符的标签字典，不从包根入口导出，不改变请求序列化、服务端行为或 UI 语言。每个字典把所有 `Operator` 成员映射为字符串；不提供回退语言解析器或 Provider。

## en_US {#api-en_US}

```ts
import { en_US } from '@ahoo-wang/wow-client/query/locale/en_US';
console.log(en_US.EQ); // Equals
```

[typescript/wow-client/src/query/locale/en_US.ts:17](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/locale/en_US.ts#L17)

## zh_CN {#api-zh_CN}

```ts
import { zh_CN } from '@ahoo-wang/wow-client/query/locale/zh_CN';
console.log(zh_CN.EQ);
```

[typescript/wow-client/src/query/locale/zh_CN.ts:17](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/locale/zh_CN.ts#L17)

[字典类型 OperatorLocale](./filters#api-OperatorLocale) · [条件构建器](./filters)

字典声明均为 `export const <语言名>: OperatorLocale`。完整键集合由 [Operator](./filters#api-Operator) 定义；每个值只用于展示，可读取但不会修改条件语义。两个入口没有默认导出。
