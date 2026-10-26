---
title: Configure a Service
description: "How a service's configuration takes effect: Spring Boot's sources and relaxed names, map keys, durations, what must match across nodes, development defaults, and the checks that catch a mistyped key."
outline: deep
---

# Configure a Service

A service is configured as any Spring Boot application is: `application.yaml`, profiles, environment variables and command-line arguments, under the prefix `wow`. Most keys have defaults that fit production. This page explains how the configuration takes effect and what the host checks. [Configuration Keys](../reference/configuration) lists every key, with its type and default.

## Where the values come from

The host reads Spring Boot's environment, so every source Spring Boot knows works, in its order of precedence: command-line arguments over environment variables over profile-specific files over `application.yaml`. Names bind relaxed: `wow.log.max-poll-interval`, `wow.log.maxPollInterval` and the environment variable `WOW_LOG_MAXPOLLINTERVAL` are one key.

Keep secrets out of the files. A deployment sets each one as an environment variable from its secret store, such as the database password, `SPRING_DATASOURCE_PASSWORD`, which the eShop's Kubernetes manifests take from a Secret.

## Write the values

| Value | Written as |
| --- | --- |
| A duration | `500ms`, `30s`, `5m`, or ISO-8601 `PT30S` |
| A list | A YAML list, or a comma-separated string from an environment variable: `WOW_HTTP_REQUESTFACTS=X-Region,X-Channel` |
| A key of a map that holds dots | In brackets: `wow.aggregates.[eshop-ordering.order].tenant`, `wow.subscriptions.[OrderSummaryProjection.onEvent].start` |

In YAML, quote a bracketed key so the brackets stay part of it:

```yaml
wow:
  subscriptions:
    "[ShipmentNotifier.onOrderShipped]":
      retry:
        max-attempts: 8
```

## Names are checked

The host refuses to start on a name that matches nothing it serves, rather than ignoring it: a subscription under `wow.subscriptions` that no handler is, an aggregate type under `wow.aggregates` the node does not serve, a context under `wow.contexts` that is on no class path, an id generator nothing provides.

Such a failure, and any other value the host cannot start with, is a `WowConfigurationException`, which Spring Boot reports at the end of the log as a failure analysis instead of a stack of bean creation errors: what is wrong, the key and its value, and the action to take.

## Keys are checked

Spring ignores a key no property binds, so a misspelled key would leave its default in place without a word. The host compares every `wow.*` key with the configuration metadata on the class path when it starts:

- An unknown key is logged as a warning that names it.
- An unknown key under `wow.cosec.`, `wow.http.services.`, `wow.calls.` or `wow.internal.` stops the start. Under `wow.http.services` and `wow.calls.contexts`, each entry's own keys are checked too: a mistyped `commands` would otherwise leave a service allowed to call every command.

Environment variables are not checked, because their names cannot be listed back. Check a deployment's variables against the reference when you set them.

## What must match across nodes

The nodes of one service form one cluster, so some settings must be the same on every node, and should change only as the reference describes:

- `wow.service`, the service's name, `spring.application.name` by default.
- `wow.log.partitions`: a power of two that divides 4,096 (64 by default), fixed once the log holds entries. Choose it before the first deployment.
- The stores and the placement of each aggregate type: `wow.backend`, `wow.stores`, `wow.log.store`, `wow.snapshot.*`, `wow.aggregates.*.log`.

## Development and production

A few defaults relax in a development profile, `dev`, `local`, `test` or `demo` (`wow.internal.dev-profiles`), or when the internal port binds the loopback interface alone:

| Without | In development | In production |
| --- | --- | --- |
| A `DataSource` or `MongoClient` | Runs in memory, with a warning | Refuses to start |

So a laptop needs no database, and a production node cannot start without one. `wow.ddl: validate` keeps a production node from creating tables ([PostgreSQL](./postgresql#manage-the-schema)).

## See what the node took

Spring Boot Actuator's `configprops` and `env` endpoints, when the application exposes them, show the values each key is bound to. Expose them on a management port only: they show the configuration of the node.
