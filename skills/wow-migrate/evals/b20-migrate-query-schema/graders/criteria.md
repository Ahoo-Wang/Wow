---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 固定源和目标版本并清点 system、JSON、classpath、working-directory 与 Bean QuerySchemaSource 以及两套后端 mapping
2. 说明默认 COMPATIBLE 接受 EXACT 与 COMPATIBLE，STRICT 只接受 EXACT，且声明冲突在 validation mode 前失败
3. 区分 OpenAPI 静态 x-wow-query-fields 与运行时 QueryModelSchema 的后端已证明能力，并从实际 OpenAPI 获取 Schema GET 和 refresh 路径
4. 说明 refresh 只更新接收请求实例的缓存且失败保留旧缓存，不广播、不修改 mapping 或历史数据
5. 验证新旧请求、Schema 合并、Mongo/Elasticsearch 物理 binding、启动和实际查询，不把编译或单后端成功当作完成
6. 若没有存储格式或写入变化则不发明数据转换，并分别报告源码适配、运行时、部署和生产证据
