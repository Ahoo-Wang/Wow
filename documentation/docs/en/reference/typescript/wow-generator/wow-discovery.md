---
title: 'Wow aggregate discovery'
description: 'Wow aggregate discovery — @ahoo-wang/wow-generator'
---

# Wow aggregate discovery

Discovery is a generator behavior, not a public AggregateResolver API. A dotted operation name alone is insufficient. The table reflects the current implementation, including component request-body references and response-reference aliases.

## Recognition matrix

| Input               | Recognized                                                                                                                                            | Missing/wrong shape                                                              |
| ------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------- |
| Root tags           | A tag named exactly `context.aggregate`, with two nonempty parts                                                                                      | Operation tags alone do not seed aggregates                                      |
| Command operationId | Exactly three dot-separated parts; last part is command name                                                                                          | Missing/wrong part count ignored; `wow.command.send` excluded                    |
| Command response    | `responses['200'].$ref` directly or by response-component alias chain reaches `#/components/responses/wow.CommandOk`                                  | Inline equivalent or 201-only response does not identify a command; cycles throw |
| Command body        | Inline RequestBody **or resolved component requestBody**, with application/json schema `$ref` to a resolvable model                                   | Missing/non-reference JSON schema is skipped                                     |
| State operation     | operationId ends `.snapshot_state.single`; 200 application/json schema is a reference                                                                 | No state means aggregate excluded from final set                                 |
| Fields operation    | operationId ends `.snapshot.count`; request body carries `x-wow-query-fields: { $ref: ... }`, or JSON condition schema has properties.field reference | No fields means aggregate excluded; malformed expected shape may throw           |
| Operation tags      | Must match the seeded root tag for command/state/fields to attach                                                                                     | Unmatched tags ignored                                                           |
| Events (optional)   | `.event.list_query`; response array items reference stream schema with properties.body.items.anyOf; variants contain name.const and body reference    | Absence produces empty event union; malformed nested structure can throw         |

The resolver requires both state and fields; commands and events may be empty. The two-part root tag determines the context/aggregate; operationId text and tags are not a semantic consistency validator. `info['x-wow-context-alias']` affects ordinary client base paths separately.

## Complete minimal input

Save this as `wow.json`, then use the same CLI invocation with `-i ./wow.json`. It deliberately uses an inline command RequestBody, a 200 CommandOk reference, and both required query operations. The result should contain commandClient.ts and queryClient.ts beneath the shop/order namespace.

```json
{
  "openapi": "3.0.3",
  "info": {
    "title": "Orders",
    "version": "1"
  },
  "tags": [
    {
      "name": "shop.order"
    }
  ],
  "paths": {
    "/orders": {
      "post": {
        "operationId": "shop.order.create",
        "tags": ["shop.order"],
        "requestBody": {
          "content": {
            "application/json": {
              "schema": {
                "$ref": "#/components/schemas/shop.CreateOrder"
              }
            }
          }
        },
        "responses": {
          "200": {
            "$ref": "#/components/responses/wow.CommandOk"
          }
        }
      }
    },
    "/orders/state": {
      "post": {
        "operationId": "shop.order.snapshot_state.single",
        "tags": ["shop.order"],
        "responses": {
          "200": {
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/shop.OrderState"
                }
              }
            }
          }
        }
      }
    },
    "/orders/count": {
      "post": {
        "operationId": "shop.order.snapshot.count",
        "tags": ["shop.order"],
        "requestBody": {
          "content": {
            "application/json": {
              "schema": {
                "$ref": "#/components/schemas/shop.Condition"
              }
            }
          },
          "x-wow-query-fields": {
            "$ref": "#/components/schemas/shop.OrderFields"
          }
        },
        "responses": {
          "200": {
            "description": "Count",
            "content": {
              "application/json": {
                "schema": {
                  "type": "integer"
                }
              }
            }
          }
        }
      }
    }
  },
  "components": {
    "schemas": {
      "shop.CreateOrder": {
        "type": "object",
        "properties": {
          "name": {
            "type": "string"
          }
        },
        "required": ["name"]
      },
      "shop.OrderState": {
        "type": "object",
        "properties": {
          "name": {
            "type": "string"
          }
        }
      },
      "shop.OrderFields": {
        "type": "string",
        "enum": ["name"]
      },
      "shop.Condition": {
        "type": "object",
        "properties": {
          "field": {
            "$ref": "#/components/schemas/shop.OrderFields"
          }
        }
      }
    },
    "responses": {
      "wow.CommandOk": {
        "description": "Accepted"
      }
    }
  }
}
```

## Implementation sources

[typescript/wow-generator/src/aggregate/aggregateResolver.ts:51](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/aggregate/aggregateResolver.ts#L51)

[typescript/wow-generator/src/aggregate/utils.ts:27](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/aggregate/utils.ts#L27)

[typescript/wow-generator/src/utils/components.ts:25](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/utils/components.ts#L25)
