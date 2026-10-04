---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 先从 fixture 建立版本与配置基线
2. 根据 Mongo store/snapshot 配置与目标 tag 的官方 Gradle 示例，同时保留基础 starter 并选择 mongo-support capability，再分别验证 compileClasspath 与 runtimeClasspath
3. 仅修改 build.gradle.kts 中必要的平台版本与目标 release 已证明的 capability 选择
4. 保持 Java 17、application.yml 与示例源码不变并检查精确 diff
5. 不声称启动、真实数据或生产迁移完成；明确备份、对账、切流和回滚仍未执行
