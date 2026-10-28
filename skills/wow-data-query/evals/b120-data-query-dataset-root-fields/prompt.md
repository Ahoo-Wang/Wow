---
name: b120-data-query-dataset-root-fields
tags: [behavior, dataset]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

dev 环境的 Wow 服务有一个数据集 analytics.order_wide（订单 × 客户宽表）。帮我算一下各客户等级的订单数和 GMV。在回答里给出代码和命令。

Files from the user's project, shown inline:

`order-wide-descriptor.json` (from `GET /wow/dataset/analytics/order_wide/query`):

```json
{
  "model" : "dataset",
  "version" : "sha256:7098b693d8f9be8980fa4e49a7c9587e415cbbec9dec4a6d56b0308c68b1a9ec",
  "timeZone" : "Z",
  "record" : {
    "identity" : "order_id",
    "paging" : [ "list", "paged", "cursor" ],
    "rootOperators" : [ "id", "ids", "tenant-id", "expression" ],
    "stream" : {
      "resumable" : true
    }
  },
  "limits" : {
    "maxListSize" : 1000,
    "defaultListSize" : 20,
    "maxPageSize" : 100,
    "maxPageWindow" : 10000,
    "maxFilterNodes" : 128,
    "maxFilterValues" : 1000,
    "maxSortFields" : 32,
    "aggregation" : {
      "maxGroups" : 32,
      "maxMetrics" : 64,
      "maxElements" : 5,
      "maxLimit" : 1000,
      "maxExpressionDepth" : 8,
      "maxExpressionNodes" : 256,
      "maxResidualGroups" : 10000
    }
  },
  "analysis" : {
    "metrics" : [ "count", "numeric", "any", "distinct-count", "percentile", "derived", "first", "last" ],
    "approximate" : [ ],
    "expressions" : true,
    "having" : {
      "metrics" : [ "count", "numeric", "distinct-count", "percentile", "derived" ]
    },
    "sort" : {
      "groups" : true,
      "metrics" : true
    },
    "dense" : true,
    "dateUnits" : [ "year", "quarter", "month", "week", "day", "hour", "minute", "second" ],
    "dateParts" : [ "day-of-week", "day-of-month", "hour-of-day", "month-of-year" ],
    "dateDiffUnits" : [ "second", "minute", "hour", "day" ],
    "windows" : [ "total", "running", "previous", "rank" ],
    "rootGroups" : true
  },
  "fields" : [ {
    "path" : "amount",
    "types" : [ "decimal" ],
    "kind" : "scalar",
    "nullable" : false,
    "project" : true,
    "filter" : {
      "operators" : [ "eq", "ne", "gt", "gte", "lt", "lte", "in", "not-in", "between", "is-null", "is-not-null", "exists", "not-exists" ]
    },
    "sort" : {
      "paged" : true,
      "cursor" : true
    },
    "aggregate" : {
      "groups" : [ "terms", "histogram" ],
      "missingKey" : false,
      "functions" : [ "sum", "avg", "min", "max", "stddev", "variance" ],
      "distinctCount" : true,
      "percentile" : true,
      "any" : true,
      "firstLast" : true,
      "expressionInput" : true,
      "inMetricFilter" : true
    },
    "aliases" : [ ]
  }, {
    "path" : "customer_level",
    "types" : [ "string" ],
    "kind" : "scalar",
    "nullable" : false,
    "project" : true,
    "filter" : {
      "operators" : [ "eq", "ne", "gt", "gte", "lt", "lte", "contains", "starts-with", "ends-with", "in", "not-in", "between", "is-empty-string", "is-not-empty-string", "is-null", "is-not-null", "exists", "not-exists" ]
    },
    "sort" : {
      "paged" : true,
      "cursor" : true
    },
    "aggregate" : {
      "groups" : [ "terms" ],
      "missingKey" : true,
      "functions" : [ ],
      "distinctCount" : true,
      "percentile" : false,
      "any" : true,
      "firstLast" : true,
      "expressionInput" : false,
      "inMetricFilter" : true
    },
    "aliases" : [ ]
  }, {
    "path" : "order_id",
    "role" : "identity",
    "types" : [ "string" ],
    "kind" : "scalar",
    "nullable" : false,
    "project" : true,
    "filter" : {
      "operators" : [ "eq", "ne", "gt", "gte", "lt", "lte", "contains", "starts-with", "ends-with", "in", "not-in", "between", "is-empty-string", "is-not-empty-string", "is-null", "is-not-null", "exists", "not-exists" ]
    },
    "sort" : {
      "paged" : true,
      "cursor" : true
    },
    "aggregate" : {
      "groups" : [ "terms" ],
      "missingKey" : true,
      "functions" : [ ],
      "distinctCount" : true,
      "percentile" : false,
      "any" : true,
      "firstLast" : true,
      "expressionInput" : false,
      "inMetricFilter" : true
    },
    "aliases" : [ ]
  }, {
    "path" : "tenant_id",
    "role" : "tenant-id",
    "types" : [ "string" ],
    "kind" : "scalar",
    "nullable" : false,
    "project" : true,
    "filter" : {
      "operators" : [ "eq", "ne", "gt", "gte", "lt", "lte", "contains", "starts-with", "ends-with", "in", "not-in", "between", "is-empty-string", "is-not-empty-string", "is-null", "is-not-null", "exists", "not-exists" ]
    },
    "sort" : {
      "paged" : true,
      "cursor" : true
    },
    "aggregate" : {
      "groups" : [ "terms" ],
      "missingKey" : true,
      "functions" : [ ],
      "distinctCount" : true,
      "percentile" : false,
      "any" : true,
      "firstLast" : true,
      "expressionInput" : false,
      "inMetricFilter" : true
    },
    "aliases" : [ ]
  } ],
  "elements" : [ ],
  "dynamic" : [ ],
  "constraints" : [ {
    "type" : "cursor-unique-sort",
    "appended" : "order_id",
    "fields" : [ "order_id" ]
  } ]
}
```
