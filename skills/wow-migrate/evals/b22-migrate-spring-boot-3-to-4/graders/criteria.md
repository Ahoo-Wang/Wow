---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 固定 wow-project-template commit 并读取 gradle/libs.versions.toml，以目标 Wow tag/BOM、模板 catalog 和 Boot 迁移资料建立 Java、Kotlin/KSP、Jackson、Spring portfolio、Gradle、Wow 生态 BOM及第三方 starter 矩阵；浮动 main 或声明版本不作为运行时证据
2. 保留基础 wow-spring-boot-starter 并只选择应用实际使用的 capability，分别证明 compileClasspath、runtimeClasspath 和 Boot 4 模块中的关键类归属
3. 清点本地配置、profile、环境变量、远程配置、ConfigMap/Secret 键名、启动参数与 auto-configuration exclusions，不读取或打印秘密值
4. 说明 Mongo 连接属性从 spring.data.mongodb.* 迁移到 spring.mongodb.*，但 auto-index-creation、field-naming-strategy、gridfs、repositories 等 Spring Data 专属属性仍保留旧 namespace；wow.mongo.* 不随之改名
5. 只把 spring-boot-properties-migrator 或 classic starter 作为有退出条件的临时诊断桥，不能用本地迁移成功替代远程配置生效证据
6. 说明 accept-case-insensitive-enums 仍在 spring.jackson.mapper，而 write-durations-as-timestamps 与 write-dates-as-timestamps 从 spring.jackson.serialization 移到 spring.jackson.datatype.datetime；核对 Boot 4 默认值和实际 JSON wire shape
7. 审计 Jackson 3 package/group、annotation 例外、自建 Mapper 的 Kotlin/WowModule 注册，并用代表性旧 command/event/snapshot 及重新生成的 OpenAPI/schema/client 验证 wire contract
8. 核对 Boot 4 模块化后的 package import、auto-configuration、condition、Bean override 与第三方 starter，不构建同时兼容 Boot 3/4 的推测性桥接层
9. 分开报告依赖解析、编译测试、原始/隔离启动、真实 HTTP或消息链、外部集成/数据、可部署与生产就绪；未授权范围标记 MISSING EVIDENCE
