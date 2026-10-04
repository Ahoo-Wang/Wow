---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 把 compile/test/build、resolved dependencies、runtimeClasspath、startup、real HTTP、external/data、deployable 和 production readiness 分成独立证据门，不把 build 成功视为迁移完成
2. 对照目标 tag、BOM、模板、starter 与 artifact 内容，用 runtime dependency tree 和 dependencyInsight 定位缺失类的实际模块，只给出按所选 Mongo 能力和目标平台成立的条件性结论，不固化某个 Boot 模块
3. 把 GC 日志目录和 JMX 端口归为 runtime preflight failure，并将原始启动与临时依赖或隔离参数后的启动分开报告
4. 记录成功 HTTP 证据，同时把缺失参数导致 Kotlin 非空参数接收 null 并返回 500 判为 REST 绑定回归，要求显式参数注解及缺失或非法 query/path/body 的合理 4xx
5. 不读取或打印凭据值，不调用外部 API 或写入数据；将外部集成、数据迁移、对账、切流、回滚和生产状态列为 MISSING EVIDENCE，并提示轮换明文凭据
6. 分别给出迁移代码完成、本地运行通过、可部署和生产就绪状态
