---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 固定精确源版本与目标提交或 tag，从两端源码验证 QueryService 到聚合绑定 QueryGateway/ObjectNode Backend、Condition 到 FilterExpression 的实际映射
2. 区分 isEmptyString、isNotEmptyString 与旧 EQ/NE 空串在 null、不存在、空白和后端上的语义，要求 MongoDB/Elasticsearch 及 HTTP guard 合同测试而不是机械替换
3. 说明 PagedQuery 仍适合 total 与跳页，CursorQuery 是无 total 的新选择而非自动替代；若采用 cursor，要求稳定唯一、独立 CURSOR_SORT 能力、单值且无 Mask 或受保护别名的排序，每页重新执行 scope/QueryPolicy/公共准入与结果 Mask
4. 拒绝旧客户端兼容 token、应用 token codec 或跨 Backend token；token 首次为空、后续原样回传且不携带授权状态
5. 分别报告源码、Spring Bean、HTTP/OpenAPI、Backend、wire、存储数据与发布边界；只有目标证据证明时才确认无需数据转换，并把运行时、双后端、部署与生产列为 MISSING EVIDENCE
6. 不添加旧 QueryService/Bean alias、代理、分页包装器或发布动作
