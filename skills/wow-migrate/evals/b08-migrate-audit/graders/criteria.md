---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 读取 build.gradle.kts、application.yml 与 LegacyOrder.kt 建立基线
2. 核对 v6.21.5、目标 v8.9.6、平台版本、Java 17 和注解用法
3. 目标 tag 可访问时基于其证据判断 store/snapshot 配置；不可访问时明确标记 MISSING EVIDENCE
4. 保持只读，并将依赖解析、编译、运行时、数据与生产状态列为 MISSING EVIDENCE
