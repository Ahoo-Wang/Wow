---
title: 生态与资源
description: 核对 Wow 与外部项目的所有权、仓库内集成边界和安装入口。
---

# 生态与资源

本页回答：**某项能力由 Wow、外部项目，还是下游应用负责？**

这里列出的仓库链接已核对可访问。外部项目拥有自己的发布、兼容性和使用文档；Wow 只拥有本仓库中的依赖选择、适配模块和示例。`gradle/libs.versions.toml` 中的固定版本描述当前 checkout 的解析结果，不构成跨版本兼容或支持承诺。

## 所有权与使用边界

| 项目 | 当前仓库中的已验证关系 | 安装与使用边界 | 权威入口 |
|---|---|---|---|
| Wow | 拥有框架 API、运行时、Spring 与基础设施适配模块 | 下游应用通过 Wow BOM、模块或 Starter capability 选择需要的能力 | [Wow](https://github.com/Ahoo-Wang/Wow) |
| wow-project-template | 提供独立演进的首次成功工程 | 从模板创建或克隆；使用前核对模板实际 Wow 版本，不假定与本站同步 | [wow-project-template](https://github.com/Ahoo-Wang/wow-project-template) |
| CosId | `wow-core` 直接使用 `cosid-core`；示例服务可选用 CosId Starter 与存储实现 | Wow 提供 ID 抽象和默认工厂；机器号分配与生产配置由应用/平台负责 | [CosId](https://github.com/Ahoo-Wang/CosId) |
| CoCache | `wow-cocache` 适配 CoCache，并同时依赖 Wow API client/query 边界 | 只有需要该缓存集成时才添加 `wow-cocache`；缓存一致性与后端运营仍由应用负责 | [CoCache](https://github.com/Ahoo-Wang/CoCache) |
| CoSec | `wow-cosec` 与 `cosec-support` 适配请求上下文传播和查询重写 | 选择 Wow 适配不会自动完成认证、授权或租户隔离；应用必须验证实际安全链 | [CoSec](https://github.com/Ahoo-Wang/CoSec) |
| CoApi | `wow-apiclient` 使用 `coapi-api`；示例服务使用 CoApi Starter 物化客户端 | Wow 定义通用客户端契约；服务发现、base URL、认证与重试由下游配置 | [CoApi](https://github.com/Ahoo-Wang/CoApi) |
| Simba | 补偿服务器使用 Redis 版 Simba 承担调度互斥 | 普通 Wow 应用不因使用 Saga 或补偿 API 就需要直接安装 Simba | [Simba](https://github.com/Ahoo-Wang/Simba) |
| FluentAssert | `wow-test` 测试栈使用 FluentAssert `.assert()` 约定 | 应用通过 `wow-test` 获得 Wow 测试 DSL；完整断言 API 由 FluentAssert 文档维护 | [FluentAssert](https://github.com/Ahoo-Wang/FluentAssert) |
| Fetcher | 补偿 Dashboard 使用 Fetcher 包及生成客户端 | 这是 Dashboard/TypeScript 客户端边界，不是 JVM 运行时必需依赖；生成文件应从 OpenAPI/生成器输入更新 | [Fetcher](https://github.com/Ahoo-Wang/Fetcher) |

当前依赖版本与 BOM 以 [`gradle/libs.versions.toml`](https://github.com/Ahoo-Wang/Wow/blob/main/gradle/libs.versions.toml) 和 [`wow-dependencies`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-dependencies/build.gradle.kts) 为准。模块是否存在以 [`settings.gradle.kts`](https://github.com/Ahoo-Wang/Wow/blob/main/settings.gradle.kts) 为准。

## 如何选择

1. 先从[模块依赖](../guide/advanced/module-dependencies.md)确定 Wow 模块或 Starter capability。
2. 再阅读对应扩展页，例如 [CoCache](../guide/extensions/cocache.md)、[CoSec](../guide/extensions/cosec.md) 或 [API Client](../guide/extensions/apiclient.md)。
3. 只有扩展页确认需要外部组件时，才到外部项目入口选择依赖和配置。
4. 升级时重新验证解析依赖、编译、实际后端集成与运行路径；不要从同一作者、同一 BOM 或当前示例推断未来兼容性。

## 不属于本页的内容

- Wow 的概念与采用成本：[简介](../guide/introduction.md)
- 精确配置键和默认值：[核心配置参考](./config/core.md)
- 生产后端选择与恢复责任：[生产最佳实践](../guide/best-practices.md)
- Agent 工作流与分发：[Agent Skills](../guide/skills.md)
