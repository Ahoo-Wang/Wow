---
title: '旧条件操作符语言包'
description: '旧条件操作符语言包 — @ahoo-wang/wow-client'
---

# 旧条件操作符语言包

两个字典是已弃用旧 `Condition` 操作符的标签字典，由 `@ahoo-wang/wow-client/legacy` 子路径导出，不从包根入口导出，不改变请求序列化、服务端行为或 UI 语言。每个字典把所有 `Operator` 成员映射为字符串；不提供回退语言解析器或 Provider。

## en_US {#api-en_US}

```ts
import { en_US } from '@ahoo-wang/wow-client/legacy';
console.log(en_US.EQ); // Equals
```

[typescript/wow-client/src/legacy/locale/en_US.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/locale/en_US.ts)

## zh_CN {#api-zh_CN}

```ts
import { zh_CN } from '@ahoo-wang/wow-client/legacy';
console.log(zh_CN.EQ);
```

[typescript/wow-client/src/legacy/locale/zh_CN.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/locale/zh_CN.ts)

[字典类型 OperatorLocale](./filters#api-OperatorLocale) · [条件构建器](./filters)

字典声明均为 `export const <语言名>: OperatorLocale`。完整键集合由 [Operator](./filters#api-Operator) 定义；每个值只用于展示，可读取但不会修改条件语义。两者都没有默认导出；原来的 `/query/locale/en_US` 和 `/query/locale/zh_CN` 子路径已不存在。
