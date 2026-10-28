---
name: b64-view-definition-event-element-scope
tags: [behavior, event-stream, element-scope]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

给订单事件流写一个系统视图「大额付款」：付款金额超过 1000 的付款事件，并在分析里加一个只数这类事件的指标。在回答里给出代码和命令。

Files from the user's project, shown inline:

`order-events-descriptor.json`:

```json
{
  "model" : "events",
  "version" : "sha256:9c32ad08ed154cc3e8c663c3c659e87f0a7376706d13baa2da97a7a57dcf4389",
  "timeZone" : "Z",
  "record" : {
    "identity" : "aggregateId",
    "paging" : [ "list", "paged", "cursor" ],
    "rootOperators" : [ "id", "ids", "aggregate-id", "aggregate-ids", "tenant-id", "owner-id", "space-id", "expression" ],
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
    "firstLastOrderBy" : "commitTime",
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
    "path" : "commandId",
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
    "path" : "commandType",
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
    "path" : "commitTime",
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
    "path" : "events",
    "types" : [ "object" ],
    "kind" : "array",
    "nullable" : false,
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
    "path" : "events.body",
    "types" : [ "object" ],
    "kind" : "union",
    "nullable" : false,
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
    "scope" : "events",
    "aliases" : [ ]
  }, {
    "path" : "events.body.channel",
    "types" : [ "string" ],
    "kind" : "scalar",
    "nullable" : false,
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
    "scope" : "events",
    "aliases" : [ ]
  }, {
    "path" : "events.body.paidAmount",
    "types" : [ "decimal" ],
    "kind" : "scalar",
    "nullable" : false,
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
    "scope" : "events",
    "aliases" : [ ]
  }, {
    "path" : "events.body.payerCard",
    "types" : [ "string" ],
    "kind" : "scalar",
    "nullable" : false,
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
    "scope" : "events",
    "aliases" : [ ]
  }, {
    "path" : "events.body.paymentMethod",
    "types" : [ "string" ],
    "kind" : "scalar",
    "nullable" : false,
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
    "scope" : "events",
    "aliases" : [ ]
  }, {
    "path" : "events.body.reason",
    "types" : [ "string" ],
    "kind" : "scalar",
    "nullable" : false,
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
    "scope" : "events",
    "aliases" : [ ]
  }, {
    "path" : "events.body.totalAmount",
    "types" : [ "decimal" ],
    "kind" : "scalar",
    "nullable" : false,
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
    "scope" : "events",
    "aliases" : [ ]
  }, {
    "path" : "events.name",
    "types" : [ "string" ],
    "kind" : "union",
    "nullable" : false,
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
    "scope" : "events",
    "aliases" : [ ]
  }, {
    "path" : "events.revision",
    "types" : [ "integer" ],
    "kind" : "union",
    "nullable" : false,
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
    "scope" : "events",
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
    "path" : "requestId",
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
    "path" : "tags",
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
    "path" : "events",
    "filter" : true,
    "aggregate" : true
  } ],
  "dynamic" : [ {
    "pattern" : "tags.{key}",
    "types" : [ "string" ],
    "kind" : "array",
    "filter" : {
      "operators" : [ "eq", "ne", "in", "not-in", "contains-all", "is-empty", "is-null", "is-not-null", "exists", "not-exists" ]
    },
    "sort" : {
      "paged" : false,
      "cursor" : false
    }
  } ],
  "constraints" : [ {
    "type" : "null-or-empty-as-missing",
    "fields" : [ "operator", "ownerId", "spaceId", "tags" ]
  }, {
    "type" : "array-equality"
  }, {
    "type" : "cursor-unique-sort",
    "appended" : "aggregateId",
    "fields" : [ "tenantId", "aggregateId", "version" ]
  } ]
}
```

`manifest.json` (from `GET /wow/manifest`; the excerpt about the order events):

```json
{
  "contexts" : [ {
    "name" : "sales",
    "aggregates" : [ {
      "name" : "order",
      "events" : [ {
        "name" : "OrderCreated",
        "aliases" : [ ],
        "revision" : 1,
        "schema" : "#/$defs/OrderCreated"
      }, {
        "name" : "OrderPaid",
        "aliases" : [ ],
        "revision" : 1,
        "schema" : "#/$defs/OrderPaid"
      }, {
        "name" : "OrderCancelled",
        "aliases" : [ ],
        "revision" : 1,
        "schema" : "#/$defs/OrderCancelled"
      } ]
    } ]
  } ],
  "$defs" : {
    "OrderCreated" : {
      "type" : "object",
      "properties" : {
        "totalAmount" : {
          "type" : "number"
        },
        "channel" : {
          "type" : "string"
        }
      }
    },
    "OrderPaid" : {
      "type" : "object",
      "properties" : {
        "paidAmount" : {
          "type" : "number"
        },
        "paymentMethod" : {
          "type" : "string"
        },
        "payerCard" : {
          "type" : "string",
          "x-wow-sensitive" : {
            "level" : "confidential"
          }
        }
      }
    },
    "OrderCancelled" : {
      "type" : "object",
      "properties" : {
        "reason" : {
          "type" : "string"
        }
      }
    }
  }
}
```
