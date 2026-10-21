---
name: b70-view-definition-wow-storybook
tags: [behavior, positive, wow-repository, storybook]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

在 Wow 仓库 Storybook 的零售场景里，为订单事件流加一个「付款事件」视图和故事。在回答里给出代码和命令。

Files from the user's project, shown inline:

`order-events-descriptor.json`:

```json
{
  "model": "EVENTS",
  "version": "sha256:order-events-fixture-v1",
  "timeZone": "Asia/Shanghai",
  "record": {
    "identity": "aggregateId",
    "paging": [
      "LIST",
      "PAGED",
      "CURSOR"
    ],
    "rootOperators": [
      "AGGREGATE_ID",
      "AGGREGATE_IDS",
      "TENANT_ID",
      "OWNER_ID",
      "EXPRESSION"
    ],
    "stream": {
      "resumable": true
    }
  },
  "limits": {
    "maxListSize": 1000,
    "defaultListSize": 100,
    "maxPageSize": 100,
    "maxPageWindow": 10000,
    "maxFilterNodes": 128,
    "maxFilterValues": 1000,
    "maxSortFields": 8,
    "aggregation": {
      "maxGroups": 4,
      "maxMetrics": 16,
      "maxElements": 4,
      "maxLimit": 1000,
      "maxExpressionDepth": 8,
      "maxExpressionNodes": 64
    }
  },
  "analysis": {
    "metrics": [
      "COUNT",
      "NUMERIC",
      "ANY",
      "DISTINCT_COUNT",
      "PERCENTILE",
      "FIRST",
      "LAST",
      "DERIVED"
    ],
    "approximate": [
      "PERCENTILE"
    ],
    "expressions": true,
    "having": {
      "metrics": [
        "COUNT",
        "NUMERIC",
        "DISTINCT_COUNT",
        "PERCENTILE",
        "DERIVED"
      ]
    },
    "sort": {
      "groups": true,
      "metrics": true
    },
    "dense": true,
    "dateUnits": [
      "YEAR",
      "QUARTER",
      "MONTH",
      "WEEK",
      "DAY",
      "HOUR"
    ],
    "dateParts": [
      "DAY_OF_WEEK",
      "DAY_OF_MONTH",
      "HOUR_OF_DAY",
      "MONTH_OF_YEAR"
    ],
    "dateDiffUnits": [
      "SECOND",
      "MINUTE",
      "HOUR",
      "DAY"
    ],
    "firstLastOrderBy": "commitTime"
  },
  "fields": [
    {
      "path": "aggregateId",
      "types": [
        "STRING"
      ],
      "kind": "SCALAR",
      "nullable": false,
      "project": true,
      "filter": {
        "operators": [
          "EQ",
          "NE",
          "GT",
          "GTE",
          "LT",
          "LTE",
          "CONTAINS",
          "STARTS_WITH",
          "ENDS_WITH",
          "IN",
          "NOT_IN",
          "BETWEEN",
          "IS_EMPTY_STRING",
          "IS_NOT_EMPTY_STRING",
          "IS_NULL",
          "IS_NOT_NULL",
          "EXISTS",
          "NOT_EXISTS"
        ]
      },
      "sort": {
        "paged": true,
        "cursor": true
      },
      "aggregate": {
        "groups": [
          "TERMS"
        ],
        "missingKey": true,
        "functions": [],
        "distinctCount": true,
        "percentile": false,
        "any": true,
        "firstLast": true,
        "expressionInput": false,
        "inMetricFilter": true
      },
      "role": "AGGREGATE_ID",
      "aliases": []
    },
    {
      "path": "version",
      "types": [
        "INTEGER"
      ],
      "kind": "SCALAR",
      "nullable": false,
      "project": true,
      "filter": {
        "operators": [
          "EQ",
          "NE",
          "GT",
          "GTE",
          "LT",
          "LTE",
          "IN",
          "NOT_IN",
          "BETWEEN",
          "IS_NULL",
          "IS_NOT_NULL",
          "EXISTS",
          "NOT_EXISTS"
        ]
      },
      "sort": {
        "paged": true,
        "cursor": true
      },
      "aggregate": {
        "groups": [
          "TERMS",
          "HISTOGRAM"
        ],
        "missingKey": false,
        "functions": [
          "AVG",
          "MAX"
        ],
        "distinctCount": false,
        "percentile": false,
        "any": false,
        "firstLast": true,
        "expressionInput": false,
        "inMetricFilter": true
      },
      "aliases": []
    },
    {
      "path": "commitTime",
      "types": [
        "INTEGER"
      ],
      "kind": "SCALAR",
      "nullable": false,
      "project": true,
      "filter": {
        "operators": [
          "EQ",
          "NE",
          "GT",
          "GTE",
          "LT",
          "LTE",
          "IN",
          "NOT_IN",
          "BETWEEN",
          "IS_NULL",
          "IS_NOT_NULL",
          "EXISTS",
          "NOT_EXISTS",
          "TODAY",
          "BEFORE_TODAY",
          "TOMORROW",
          "THIS_WEEK",
          "NEXT_WEEK",
          "LAST_WEEK",
          "THIS_MONTH",
          "LAST_MONTH",
          "RECENT_DAYS",
          "EARLIER_DAYS",
          "YESTERDAY",
          "NEXT_MONTH",
          "LAST_YEAR",
          "THIS_YEAR",
          "NEXT_YEAR",
          "BEFORE_NOW",
          "AFTER_NOW"
        ]
      },
      "sort": {
        "paged": true,
        "cursor": true
      },
      "aggregate": {
        "groups": [
          "DATE_HISTOGRAM",
          "DATE_PART"
        ],
        "missingKey": false,
        "functions": [
          "MIN",
          "MAX"
        ],
        "distinctCount": false,
        "percentile": false,
        "any": false,
        "firstLast": true,
        "expressionInput": false,
        "inMetricFilter": true
      },
      "semantic": {
        "type": "TEMPORAL_EPOCH",
        "timeUnit": "MILLISECONDS"
      },
      "aliases": []
    },
    {
      "path": "commandId",
      "types": [
        "STRING"
      ],
      "kind": "SCALAR",
      "nullable": true,
      "project": true,
      "filter": {
        "operators": [
          "EQ",
          "NE",
          "GT",
          "GTE",
          "LT",
          "LTE",
          "CONTAINS",
          "STARTS_WITH",
          "ENDS_WITH",
          "IN",
          "NOT_IN",
          "BETWEEN",
          "IS_EMPTY_STRING",
          "IS_NOT_EMPTY_STRING",
          "IS_NULL",
          "IS_NOT_NULL",
          "EXISTS",
          "NOT_EXISTS"
        ]
      },
      "sort": {
        "paged": true,
        "cursor": false
      },
      "aliases": []
    },
    {
      "path": "ownerId",
      "types": [
        "STRING"
      ],
      "kind": "SCALAR",
      "nullable": true,
      "project": true,
      "filter": {
        "operators": [
          "EQ",
          "NE",
          "GT",
          "GTE",
          "LT",
          "LTE",
          "CONTAINS",
          "STARTS_WITH",
          "ENDS_WITH",
          "IN",
          "NOT_IN",
          "BETWEEN",
          "IS_EMPTY_STRING",
          "IS_NOT_EMPTY_STRING",
          "IS_NULL",
          "IS_NOT_NULL",
          "EXISTS",
          "NOT_EXISTS"
        ]
      },
      "sort": {
        "paged": true,
        "cursor": false
      },
      "role": "OWNER_ID",
      "aliases": []
    },
    {
      "path": "events",
      "types": [
        "OBJECT"
      ],
      "kind": "ARRAY",
      "nullable": false,
      "project": true,
      "filter": {
        "operators": [
          "IS_EMPTY",
          "IS_NULL",
          "IS_NOT_NULL",
          "EXISTS",
          "NOT_EXISTS"
        ]
      },
      "sort": {
        "paged": false,
        "cursor": false
      },
      "aliases": []
    },
    {
      "path": "events.name",
      "types": [
        "STRING"
      ],
      "kind": "SCALAR",
      "nullable": false,
      "project": true,
      "filter": {
        "operators": [
          "EQ",
          "NE",
          "GT",
          "GTE",
          "LT",
          "LTE",
          "CONTAINS",
          "STARTS_WITH",
          "ENDS_WITH",
          "IN",
          "NOT_IN",
          "BETWEEN",
          "IS_EMPTY_STRING",
          "IS_NOT_EMPTY_STRING",
          "IS_NULL",
          "IS_NOT_NULL",
          "EXISTS",
          "NOT_EXISTS"
        ]
      },
      "sort": {
        "paged": false,
        "cursor": false
      },
      "aggregate": {
        "groups": [
          "TERMS"
        ],
        "missingKey": true,
        "functions": [],
        "distinctCount": true,
        "percentile": false,
        "any": true,
        "firstLast": true,
        "expressionInput": false,
        "inMetricFilter": true
      },
      "scope": "events",
      "aliases": [],
      "description": "The event's name, <context>.<aggregate>.<Name>"
    },
    {
      "path": "events.revision",
      "types": [
        "STRING"
      ],
      "kind": "SCALAR",
      "nullable": true,
      "project": true,
      "filter": {
        "operators": [
          "EQ",
          "NE",
          "GT",
          "GTE",
          "LT",
          "LTE",
          "CONTAINS",
          "STARTS_WITH",
          "ENDS_WITH",
          "IN",
          "NOT_IN",
          "BETWEEN",
          "IS_EMPTY_STRING",
          "IS_NOT_EMPTY_STRING",
          "IS_NULL",
          "IS_NOT_NULL",
          "EXISTS",
          "NOT_EXISTS"
        ]
      },
      "sort": {
        "paged": false,
        "cursor": false
      },
      "scope": "events",
      "aliases": []
    },
    {
      "path": "events.body.totalAmount",
      "types": [
        "DECIMAL"
      ],
      "kind": "SCALAR",
      "nullable": true,
      "project": true,
      "filter": {
        "operators": [
          "EQ",
          "NE",
          "GT",
          "GTE",
          "LT",
          "LTE",
          "IN",
          "NOT_IN",
          "BETWEEN",
          "IS_NULL",
          "IS_NOT_NULL",
          "EXISTS",
          "NOT_EXISTS"
        ]
      },
      "sort": {
        "paged": false,
        "cursor": false
      },
      "aggregate": {
        "groups": [
          "HISTOGRAM"
        ],
        "missingKey": false,
        "functions": [
          "SUM",
          "AVG",
          "MIN",
          "MAX"
        ],
        "distinctCount": false,
        "percentile": true,
        "any": false,
        "firstLast": true,
        "expressionInput": true,
        "inMetricFilter": true
      },
      "aliases": []
    },
    {
      "path": "events.body.channel",
      "types": [
        "STRING"
      ],
      "kind": "SCALAR",
      "nullable": true,
      "project": true,
      "filter": {
        "operators": [
          "EQ",
          "NE",
          "GT",
          "GTE",
          "LT",
          "LTE",
          "CONTAINS",
          "STARTS_WITH",
          "ENDS_WITH",
          "IN",
          "NOT_IN",
          "BETWEEN",
          "IS_EMPTY_STRING",
          "IS_NOT_EMPTY_STRING",
          "IS_NULL",
          "IS_NOT_NULL",
          "EXISTS",
          "NOT_EXISTS"
        ]
      },
      "sort": {
        "paged": false,
        "cursor": false
      },
      "aggregate": {
        "groups": [
          "TERMS"
        ],
        "missingKey": true,
        "functions": [],
        "distinctCount": false,
        "percentile": false,
        "any": true,
        "firstLast": true,
        "expressionInput": false,
        "inMetricFilter": true
      },
      "aliases": []
    },
    {
      "path": "events.body.paidAmount",
      "types": [
        "DECIMAL"
      ],
      "kind": "SCALAR",
      "nullable": true,
      "project": true,
      "filter": {
        "operators": [
          "EQ",
          "NE",
          "GT",
          "GTE",
          "LT",
          "LTE",
          "IN",
          "NOT_IN",
          "BETWEEN",
          "IS_NULL",
          "IS_NOT_NULL",
          "EXISTS",
          "NOT_EXISTS"
        ]
      },
      "sort": {
        "paged": false,
        "cursor": false
      },
      "aggregate": {
        "groups": [
          "HISTOGRAM"
        ],
        "missingKey": false,
        "functions": [
          "SUM",
          "AVG",
          "MIN",
          "MAX"
        ],
        "distinctCount": false,
        "percentile": true,
        "any": false,
        "firstLast": true,
        "expressionInput": true,
        "inMetricFilter": true
      },
      "aliases": []
    },
    {
      "path": "events.body.paymentMethod",
      "types": [
        "STRING"
      ],
      "kind": "SCALAR",
      "nullable": true,
      "project": true,
      "filter": {
        "operators": [
          "EQ",
          "NE",
          "GT",
          "GTE",
          "LT",
          "LTE",
          "CONTAINS",
          "STARTS_WITH",
          "ENDS_WITH",
          "IN",
          "NOT_IN",
          "BETWEEN",
          "IS_EMPTY_STRING",
          "IS_NOT_EMPTY_STRING",
          "IS_NULL",
          "IS_NOT_NULL",
          "EXISTS",
          "NOT_EXISTS"
        ]
      },
      "sort": {
        "paged": false,
        "cursor": false
      },
      "aggregate": {
        "groups": [
          "TERMS"
        ],
        "missingKey": true,
        "functions": [],
        "distinctCount": false,
        "percentile": false,
        "any": true,
        "firstLast": true,
        "expressionInput": false,
        "inMetricFilter": true
      },
      "aliases": []
    },
    {
      "path": "events.body.payerCard",
      "types": [
        "STRING"
      ],
      "kind": "SCALAR",
      "nullable": true,
      "project": true,
      "filter": {
        "operators": []
      },
      "sort": {
        "paged": false,
        "cursor": false
      },
      "sensitivity": {
        "level": "CONFIDENTIAL",
        "comparable": false
      },
      "aliases": []
    },
    {
      "path": "events.body.reason",
      "types": [
        "STRING"
      ],
      "kind": "SCALAR",
      "nullable": true,
      "project": true,
      "filter": {
        "operators": [
          "EQ",
          "NE",
          "GT",
          "GTE",
          "LT",
          "LTE",
          "CONTAINS",
          "STARTS_WITH",
          "ENDS_WITH",
          "IN",
          "NOT_IN",
          "BETWEEN",
          "IS_EMPTY_STRING",
          "IS_NOT_EMPTY_STRING",
          "IS_NULL",
          "IS_NOT_NULL",
          "EXISTS",
          "NOT_EXISTS"
        ]
      },
      "sort": {
        "paged": false,
        "cursor": false
      },
      "aggregate": {
        "groups": [
          "TERMS"
        ],
        "missingKey": true,
        "functions": [],
        "distinctCount": false,
        "percentile": false,
        "any": true,
        "firstLast": true,
        "expressionInput": false,
        "inMetricFilter": true
      },
      "aliases": []
    }
  ],
  "elements": [
    {
      "path": "events",
      "filter": true,
      "aggregate": true
    }
  ],
  "dynamic": [],
  "constraints": [
    {
      "type": "CURSOR_UNIQUE_SORT",
      "appended": "aggregateId"
    }
  ]
}
```
