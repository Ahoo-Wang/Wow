---
name: b120-data-query-dataset-root-fields
tags: [behavior, dataset]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

dev 环境的 Wow 服务有一个数据集 analytics.order_wide（订单 × 客户宽表）。帮我算一下各客户等级的订单数和 GMV。

Files from the user's project, shown inline:

`order-wide-descriptor.json` (from `GET /wow/dataset/analytics/order_wide/query`):

```json
{
  "model": "DATASET",
  "version": "fixture",
  "timeZone": "UTC",
  "record": { "identity": "order_id", "paging": ["LIST", "PAGED", "CURSOR"], "rootOperators": ["ID", "IDS", "TENANT_ID"], "search": null },
  "limits": { "maxListSize": 1000, "defaultListSize": 100, "maxPageSize": 100, "maxPageWindow": 10000, "maxFilterNodes": 128, "maxFilterValues": 1000, "maxSortFields": 8,
    "aggregation": { "maxGroups": 4, "maxMetrics": 16, "maxElements": 0, "maxLimit": 1000, "maxExpressionDepth": 8, "maxExpressionNodes": 64 } },
  "analysis": { "metrics": ["COUNT", "NUMERIC", "DISTINCT_COUNT", "PERCENTILE", "DERIVED"], "approximate": ["PERCENTILE"], "expressions": true,
    "having": { "metrics": ["COUNT", "NUMERIC"] }, "sort": { "groups": true, "metrics": true }, "dense": true,
    "dateUnits": ["MONTH", "DAY"], "dateParts": [], "dateDiffUnits": [] },
  "fields": [
    { "path": "order_id", "role": "IDENTITY", "types": ["STRING"], "kind": "SCALAR", "nullable": false, "project": true,
      "filter": { "operators": ["EQ", "IN"] }, "sort": { "paged": true, "cursor": true }, "aliases": [] },
    { "path": "customer_level", "types": ["STRING"], "kind": "SCALAR", "nullable": false, "description": "客户等级", "project": true,
      "filter": { "operators": ["EQ", "NE", "IN", "NOT_IN"] }, "sort": { "paged": true, "cursor": true },
      "aggregate": { "groups": ["TERMS"], "missingKey": true, "functions": [], "distinctCount": true, "percentile": false, "any": true, "firstLast": true, "expressionInput": false, "inMetricFilter": true }, "aliases": [] },
    { "path": "amount", "types": ["DECIMAL"], "kind": "SCALAR", "nullable": false, "description": "订单金额", "project": true,
      "filter": { "operators": ["EQ", "GT", "GTE", "LT", "LTE", "BETWEEN"] }, "sort": { "paged": true, "cursor": true },
      "aggregate": { "groups": ["HISTOGRAM"], "missingKey": false, "functions": ["SUM", "AVG", "MIN", "MAX"], "distinctCount": true, "percentile": true, "any": true, "firstLast": true, "expressionInput": true, "inMetricFilter": true }, "aliases": [] }
  ],
  "elements": [],
  "dynamic": [],
  "constraints": []
}
```
