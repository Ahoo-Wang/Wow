---
title: 'Wow aggregate discovery'
description: 'Wow aggregate discovery — @ahoo-wang/wow-generator'
---

# Wow aggregate discovery

Discovery is a generator behavior, not a public API. A dotted operation name alone is insufficient. The table reflects the current implementation, including component request-body references and response-reference aliases.

## Recognition matrix

| Input               | Recognized                                                                                                                                            | Missing/wrong shape                                                              |
| ------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------- |
| Aggregate tags      | A tag named exactly `context.aggregate`, with two nonempty parts, declared in the root `tags` or only on operations                                   | Other tag names do not seed aggregates                                           |
| Command operationId | Exactly three dot-separated parts; last part is command name                                                                                          | Missing/wrong part count ignored; `wow.command.send` excluded                    |
| Command response    | The success response (`200`, else the lowest other 2xx) `$ref` directly or by response-component alias chain reaches `#/components/responses/wow.CommandOk` | Inline equivalent does not identify a command; cycles throw                  |
| Command body        | Inline RequestBody **or resolved component requestBody**, with application/json schema `$ref` to a resolvable model                                   | Missing/non-reference JSON schema: command skipped with a warning               |
| State operation     | operationId ends `.snapshot_state.single`; success application/json schema is a reference                                                             | Non-reference schema fails (exit 4); no state operation skips the aggregate with a warning |
| Fields operation    | operationId ends `.snapshot.count`; request body carries `x-wow-query-fields: { $ref: ... }`, or JSON condition schema has properties.field reference | No body, non-reference `x-wow-query-fields` or no field reference fails (exit 4); no fields operation skips the aggregate with a warning |
| Operation tags      | Must match the aggregate tag for command/state/fields to attach                                                                                       | Unmatched tags ignored                                                           |
| Route segment       | The segment before `/snapshot` in the state or fields route (`/tenant/{tenantId}/sales-order/snapshot/count` → `sales-order`)                          | Defaults to the aggregate name; becomes the query factory's `aggregateName`      |
| Events (optional)   | `.event.list_query`; response array items reference stream schema with properties.body.items.anyOf; variants contain name.const and body reference    | Absence produces empty event union; a malformed stream schema fails (exit 4)    |

The resolver requires both state and fields; commands and events may be empty. An aggregate that exposes Wow routes but lacks either is skipped with a warning naming the missing operation, and its operations do not fall back to an ordinary API client. Malformed Wow metadata fails with exit code 4, naming the operation and noting that documents from Wow 8.10 or later are read. The two-part root tag determines the context/aggregate; operationId text and tags are not a semantic consistency validator. `info['x-wow-context-alias']` affects ordinary client base paths separately, and makes the document a Wow document for the `ignorePathParameters` default.

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

[typescript/wow-generator/src/wow/resolveWowModel.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/wow/resolveWowModel.ts)

[typescript/wow-generator/src/wow/conventions.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/wow/conventions.ts)

[typescript/wow-generator/src/openapi/components.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/openapi/components.ts)
