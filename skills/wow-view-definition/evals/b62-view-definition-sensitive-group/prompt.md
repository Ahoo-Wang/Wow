---
name: b62-view-definition-sensitive-group
tags: [behavior, sensitive-field, group]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

在订单分析里加一个按买家手机号分组的维度，找出下单最多的手机号。在回答里给出代码和命令。

Files from the user's project, shown inline:

`order-snapshot-descriptor.json`:

```json
{
  "model" : "snapshot",
  "version" : "sha256:9ede0aed77762b63c2fa961f09052287329b70699d4d6455c855e068b666e63c",
  "timeZone" : "Z",
  "record" : {
    "identity" : "aggregateId",
    "paging" : [ "list", "paged", "cursor" ],
    "defaultScope" : "active",
    "rootOperators" : [ "id", "ids", "aggregate-id", "aggregate-ids", "tenant-id", "owner-id", "space-id", "deletion", "expression" ],
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
    "approximate" : [ "distinct-count", "percentile" ],
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
    "firstLastOrderBy" : "lastEventTime",
    "windows" : [ "total", "running", "previous", "rank" ],
    "rootGroups" : true
  },
  "fields" : [ {
    "path" : "aggregateId",
    "role" : "aggregate-id",
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
    "path" : "deleted",
    "role" : "deleted",
    "types" : [ "boolean" ],
    "kind" : "scalar",
    "nullable" : false,
    "project" : true,
    "filter" : {
      "operators" : [ "eq", "ne", "in", "not-in", "is-null", "is-not-null", "exists", "not-exists" ]
    },
    "sort" : {
      "paged" : true,
      "cursor" : true
    },
    "aggregate" : {
      "groups" : [ "terms" ],
      "missingKey" : false,
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
    "path" : "firstEventTime",
    "role" : "first-event-time",
    "types" : [ "integer" ],
    "kind" : "scalar",
    "nullable" : false,
    "semantic" : {
      "type" : "temporal-epoch",
      "timeUnit" : "MILLISECONDS"
    },
    "project" : true,
    "filter" : {
      "operators" : [ "eq", "ne", "gt", "gte", "lt", "lte", "in", "not-in", "between", "is-null", "is-not-null", "exists", "not-exists", "today", "before-today", "tomorrow", "this-week", "next-week", "last-week", "this-month", "last-month", "recent-days", "earlier-days", "yesterday", "next-month", "last-year", "this-year", "next-year", "before-now", "after-now" ]
    },
    "sort" : {
      "paged" : true,
      "cursor" : true
    },
    "aggregate" : {
      "groups" : [ "terms", "histogram", "date-histogram", "date-part" ],
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
    "path" : "firstOperator",
    "types" : [ "object" ],
    "kind" : "object",
    "nullable" : true,
    "project" : true,
    "filter" : {
      "operators" : [ "is-null", "is-not-null", "exists", "not-exists" ]
    },
    "sort" : {
      "paged" : false,
      "cursor" : false
    },
    "aliases" : [ ]
  }, {
    "path" : "firstOperator.crossScope",
    "types" : [ "boolean" ],
    "kind" : "scalar",
    "nullable" : false,
    "project" : true,
    "filter" : {
      "operators" : [ "eq", "ne", "in", "not-in", "is-null", "is-not-null", "exists", "not-exists" ]
    },
    "sort" : {
      "paged" : true,
      "cursor" : true
    },
    "aggregate" : {
      "groups" : [ "terms" ],
      "missingKey" : false,
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
    "path" : "firstOperator.id",
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
    "path" : "firstOperator.tenantId",
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
    "path" : "lastEventTime",
    "role" : "event-time",
    "types" : [ "integer" ],
    "kind" : "scalar",
    "nullable" : false,
    "semantic" : {
      "type" : "temporal-epoch",
      "timeUnit" : "MILLISECONDS"
    },
    "project" : true,
    "filter" : {
      "operators" : [ "eq", "ne", "gt", "gte", "lt", "lte", "in", "not-in", "between", "is-null", "is-not-null", "exists", "not-exists", "today", "before-today", "tomorrow", "this-week", "next-week", "last-week", "this-month", "last-month", "recent-days", "earlier-days", "yesterday", "next-month", "last-year", "this-year", "next-year", "before-now", "after-now" ]
    },
    "sort" : {
      "paged" : true,
      "cursor" : true
    },
    "aggregate" : {
      "groups" : [ "terms", "histogram", "date-histogram", "date-part" ],
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
    "path" : "operator",
    "types" : [ "object" ],
    "kind" : "object",
    "nullable" : true,
    "project" : true,
    "filter" : {
      "operators" : [ "is-null", "is-not-null", "exists", "not-exists" ]
    },
    "sort" : {
      "paged" : false,
      "cursor" : false
    },
    "aliases" : [ ]
  }, {
    "path" : "operator.crossScope",
    "types" : [ "boolean" ],
    "kind" : "scalar",
    "nullable" : false,
    "project" : true,
    "filter" : {
      "operators" : [ "eq", "ne", "in", "not-in", "is-null", "is-not-null", "exists", "not-exists" ]
    },
    "sort" : {
      "paged" : true,
      "cursor" : true
    },
    "aggregate" : {
      "groups" : [ "terms" ],
      "missingKey" : false,
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
    "path" : "operator.id",
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
    "path" : "operator.tenantId",
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
    "path" : "ownerId",
    "role" : "owner-id",
    "types" : [ "string" ],
    "kind" : "scalar",
    "nullable" : true,
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
    "path" : "schedules",
    "types" : [ "object" ],
    "kind" : "object",
    "nullable" : false,
    "project" : true,
    "filter" : {
      "operators" : [ "is-null", "is-not-null", "exists", "not-exists" ]
    },
    "sort" : {
      "paged" : false,
      "cursor" : false
    },
    "aliases" : [ ]
  }, {
    "path" : "spaceId",
    "role" : "space-id",
    "types" : [ "string" ],
    "kind" : "scalar",
    "nullable" : true,
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
    "path" : "state",
    "types" : [ "object" ],
    "kind" : "object",
    "nullable" : false,
    "project" : true,
    "filter" : {
      "operators" : [ ]
    },
    "sort" : {
      "paged" : false,
      "cursor" : false
    },
    "aliases" : [ ]
  }, {
    "path" : "state.buyer",
    "types" : [ "object" ],
    "kind" : "object",
    "nullable" : true,
    "project" : true,
    "filter" : {
      "operators" : [ ]
    },
    "sort" : {
      "paged" : false,
      "cursor" : false
    },
    "aliases" : [ ]
  }, {
    "path" : "state.buyer.annualIncome",
    "types" : [ "string" ],
    "kind" : "scalar",
    "nullable" : true,
    "sensitivity" : {
      "level" : "confidential",
      "comparable" : false
    },
    "project" : true,
    "filter" : {
      "operators" : [ ]
    },
    "sort" : {
      "paged" : false,
      "cursor" : false
    },
    "aliases" : [ ]
  }, {
    "path" : "state.buyer.idCard",
    "types" : [ "string" ],
    "kind" : "scalar",
    "nullable" : true,
    "sensitivity" : {
      "level" : "confidential",
      "comparable" : false
    },
    "project" : true,
    "filter" : {
      "operators" : [ ]
    },
    "sort" : {
      "paged" : false,
      "cursor" : false
    },
    "aliases" : [ ]
  }, {
    "path" : "state.buyer.level",
    "types" : [ "string" ],
    "kind" : "scalar",
    "nullable" : true,
    "enum" : [ {
      "value" : "REGULAR"
    }, {
      "value" : "SILVER"
    }, {
      "value" : "GOLD"
    } ],
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
    "path" : "state.buyer.nick",
    "types" : [ "string" ],
    "kind" : "scalar",
    "nullable" : true,
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
    "path" : "state.buyer.phone",
    "types" : [ "string" ],
    "kind" : "scalar",
    "nullable" : true,
    "sensitivity" : {
      "level" : "display",
      "comparable" : true
    },
    "project" : true,
    "filter" : {
      "operators" : [ "eq", "ne", "gt", "gte", "lt", "lte", "contains", "starts-with", "ends-with", "in", "not-in", "between", "is-empty-string", "is-not-empty-string", "is-null", "is-not-null", "exists", "not-exists" ]
    },
    "sort" : {
      "paged" : true,
      "cursor" : false
    },
    "aliases" : [ ]
  }, {
    "path" : "state.channel",
    "types" : [ "string" ],
    "kind" : "scalar",
    "nullable" : true,
    "enum" : [ {
      "value" : "APP"
    }, {
      "value" : "MINI_PROGRAM"
    }, {
      "value" : "WEB"
    } ],
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
    "path" : "state.createdAt",
    "types" : [ "integer" ],
    "kind" : "scalar",
    "nullable" : false,
    "semantic" : {
      "type" : "temporal-epoch",
      "timeUnit" : "MILLISECONDS"
    },
    "project" : true,
    "filter" : {
      "operators" : [ "eq", "ne", "gt", "gte", "lt", "lte", "in", "not-in", "between", "is-null", "is-not-null", "exists", "not-exists", "today", "before-today", "tomorrow", "this-week", "next-week", "last-week", "this-month", "last-month", "recent-days", "earlier-days", "yesterday", "next-month", "last-year", "this-year", "next-year", "before-now", "after-now" ]
    },
    "sort" : {
      "paged" : true,
      "cursor" : true
    },
    "aggregate" : {
      "groups" : [ "terms", "histogram", "date-histogram", "date-part" ],
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
    "path" : "state.items",
    "types" : [ "object" ],
    "kind" : "array",
    "nullable" : true,
    "project" : true,
    "filter" : {
      "operators" : [ ]
    },
    "sort" : {
      "paged" : false,
      "cursor" : false
    },
    "aliases" : [ ]
  }, {
    "path" : "state.items.qty",
    "types" : [ "integer" ],
    "kind" : "scalar",
    "nullable" : true,
    "project" : true,
    "filter" : {
      "operators" : [ "eq", "ne", "gt", "gte", "lt", "lte", "in", "not-in", "between", "is-null", "is-not-null", "exists", "not-exists" ]
    },
    "sort" : {
      "paged" : true,
      "cursor" : false
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
    "scope" : "state.items",
    "aliases" : [ ]
  }, {
    "path" : "state.items.sku",
    "types" : [ "string" ],
    "kind" : "scalar",
    "nullable" : true,
    "project" : true,
    "filter" : {
      "operators" : [ "eq", "ne", "gt", "gte", "lt", "lte", "contains", "starts-with", "ends-with", "in", "not-in", "between", "is-empty-string", "is-not-empty-string", "is-null", "is-not-null", "exists", "not-exists" ]
    },
    "sort" : {
      "paged" : true,
      "cursor" : false
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
    "scope" : "state.items",
    "aliases" : [ ]
  }, {
    "path" : "state.items.title",
    "types" : [ "string" ],
    "kind" : "scalar",
    "nullable" : true,
    "project" : true,
    "filter" : {
      "operators" : [ "eq", "ne", "gt", "gte", "lt", "lte", "contains", "starts-with", "ends-with", "in", "not-in", "between", "is-empty-string", "is-not-empty-string", "is-null", "is-not-null", "exists", "not-exists" ]
    },
    "sort" : {
      "paged" : true,
      "cursor" : false
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
    "scope" : "state.items",
    "aliases" : [ ]
  }, {
    "path" : "state.oldStatus",
    "types" : [ "string" ],
    "kind" : "scalar",
    "nullable" : true,
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
    "deprecated" : {
      "message" : "Use state.status."
    },
    "aliases" : [ ]
  }, {
    "path" : "state.orderNo",
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
    "path" : "state.paidAmount",
    "types" : [ "decimal" ],
    "kind" : "scalar",
    "nullable" : true,
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
    "path" : "state.paidAt",
    "types" : [ "integer" ],
    "kind" : "scalar",
    "nullable" : true,
    "semantic" : {
      "type" : "temporal-epoch",
      "timeUnit" : "MILLISECONDS"
    },
    "project" : true,
    "filter" : {
      "operators" : [ "eq", "ne", "gt", "gte", "lt", "lte", "in", "not-in", "between", "is-null", "is-not-null", "exists", "not-exists", "today", "before-today", "tomorrow", "this-week", "next-week", "last-week", "this-month", "last-month", "recent-days", "earlier-days", "yesterday", "next-month", "last-year", "this-year", "next-year", "before-now", "after-now" ]
    },
    "sort" : {
      "paged" : true,
      "cursor" : true
    },
    "aggregate" : {
      "groups" : [ "terms", "histogram", "date-histogram", "date-part" ],
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
    "path" : "state.remark",
    "types" : [ "string" ],
    "kind" : "scalar",
    "nullable" : true,
    "description" : "Free text the buyer wrote; stored unindexed.",
    "project" : true,
    "filter" : {
      "operators" : [ ]
    },
    "sort" : {
      "paged" : false,
      "cursor" : false
    },
    "aliases" : [ ]
  }, {
    "path" : "state.status",
    "types" : [ "string" ],
    "kind" : "scalar",
    "nullable" : false,
    "enum" : [ {
      "value" : "PENDING_PAYMENT"
    }, {
      "value" : "PAID"
    }, {
      "value" : "SHIPPED"
    }, {
      "value" : "SIGNED"
    }, {
      "value" : "CANCELLED"
    } ],
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
    "path" : "state.warehouse",
    "types" : [ "string" ],
    "kind" : "scalar",
    "nullable" : true,
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
    "path" : "tags",
    "types" : [ "object" ],
    "kind" : "object",
    "nullable" : false,
    "project" : true,
    "filter" : {
      "operators" : [ "is-null", "is-not-null", "exists", "not-exists" ]
    },
    "sort" : {
      "paged" : false,
      "cursor" : false
    },
    "aliases" : [ ]
  }, {
    "path" : "tenantId",
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
  }, {
    "path" : "version",
    "types" : [ "integer" ],
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
  } ],
  "elements" : [ {
    "path" : "state.items",
    "filter" : true,
    "aggregate" : true
  } ],
  "dynamic" : [ ],
  "constraints" : [ {
    "type" : "null-or-empty-as-missing",
    "fields" : [ "firstOperator", "operator", "ownerId", "spaceId", "state.buyer.level", "state.buyer.nick", "state.buyer.phone", "state.channel", "state.items.qty", "state.items.sku", "state.items.title", "state.oldStatus", "state.paidAmount", "state.paidAt", "state.warehouse" ]
  }, {
    "type" : "array-equality"
  }, {
    "type" : "cursor-unique-sort",
    "appended" : "aggregateId",
    "fields" : [ "tenantId", "aggregateId" ]
  } ]
}
```
