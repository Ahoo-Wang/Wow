# 查询服务

## 操作符

| 操作符         | 描述                                                                                                                                           |
|-------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| AND         | 对提供的条件列表执行逻辑与                                                                                                                                |
| OR          | 对提供的条件列表执行逻辑或                                                                                                                                |
| ID          | 匹配`id`字段值等于指定值的所有文档                                                                                                                          |
| IDS         | 匹配`id`字段值等于指定值列表中的任何值的所有文档                                                                                                                   |
| ALL         | 匹配所有文档                                                                                                                                       |
| EQ          | 匹配字段名称值等于指定值的所有文档                                                                                                                            |
| NE          | 匹配字段名称值不等于指定值的所有文档                                                                                                                           |
| GT          | 匹配给定字段的值大于指定值的所有文档                                                                                                                           |
| LT          | 匹配给定字段的值小于指定值的所有文档                                                                                                                           |
| GTE         | 匹配给定字段的值大于或等于指定值的所有文档                                                                                                                        |
| LTE         | 匹配给定字段的值小于或等于指定值的所有文档                                                                                                                        |
| CONTAINS    | 匹配给定字段的值包含指定值的所有文档                                                                                                                           |
| IN          | 匹配字段值等于指定值列表中的任何值的所有文档                                                                                                                       |
| NOT_IN      | 匹配字段值不等于任何指定值或不存在的所有文档                                                                                                                       |
| BETWEEN     | 匹配字段值在指定值范围区间的所有文档                                                                                                                           |
| ALL_IN      | 匹配所有文档，其中字段值是包含所有指定值的数组                                                                                                                      |
| STARTS_WITH | 匹配字段值以指定字符串开头的文档                                                                                                                             |
| ENDS_WITH   | 匹配字段值以指定字符串结尾的文档                                                                                                                             |
| ELEM_MATCH  | 条件与包含数组字段的所有文档相匹配，其中数组中至少有一个成员与给定的条件匹配。                                                                                                      |
| NULL        | 匹配字段值在指定值为`null`的所有文档                                                                                                                        |
| NOT_NULL    | 匹配字段值在指定值不为`null`的所有文档                                                                                                                       |
| TRUE        | 匹配字段值在指定值为`true`的所有文档                                                                                                                        |
| FALSE       | 匹配字段值在指定值为`false`的所有文档                                                                                                                       |
| RAW         | 原始操作符，将条件值直接作为原始的数据库查询条件                                                                                                                     |
| TODAY       | 匹配*数值类型时间戳*字段在今天范围区间的所有文档。比如：`today` 为 `2024-06-06`，匹配范围 `2024-06-06 00:00:00.000` ~ `2024-06-06 23:59:59.999` 的所有文档                         |
| TOMORROW    | 匹配*数值类型时间戳*字段在昨天范围区间的所有文档。比如：`today` 为 `2024-06-06`，匹配范围 `2024-06-05 00:00:00.000` ~ `2024-06-05 23:59:59.999` 的所有文档                         |
| THIS_WEEK   | 匹配*数值类型时间戳*字段在本周范围区间的所有文档                                                                                                                    |
| NEXT_WEEK   | 匹配*数值类型时间戳*字段在下周范围区间的所有文档                                                                                                                    |
| LAST_WEEK   | 匹配*数值类型时间戳*字段在上周范围区间的所有文档                                                                                                                    |
| THIS_MONTH  | 匹配*数值类型时间戳*字段在本月范围区间的所有文档。比如：`today` : `2024-06-06`，匹配范围 : `2024-06-01 00:00:00.000` ~ `2024-06-30 23:59:59.999` 的所有文档                       |
| LAST_MONTH  | 匹配*数值类型时间戳*字段在上月范围区间的所有文档。比如：`today` : `2024-06-06`，匹配范围 : `2024-05-01 00:00:00.000` ~ `2024-05-31 23:59:59.999` 的所有文档                       |
| RECENT_DAYS | 匹配*数值类型时间戳*字段在指定值最近天数范围区间的所有文档。比如：`today` : `2024-06-06`，近三天，匹配范围 : `2024-06-04 00:00:00.000` ~ `2024-06-06 23:59:59.999` 的所有文档。即 : 今天、昨天、前天 |

## Query DSL

`Query DSL` 旨在提供一种简洁而灵活的方式来构建查询条件。

### ConditionDsl

```kotlin
condition {
    "field1" eq "value1"
    "field2" ne "value2"
    "filed3" gt 1
    "field4" lt 1
    "field5" gte 1
    "field6" lte 1
    "field7" contains "value7"
    "field8" isIn listOf("value8")
    "field9" notIn listOf("value9")
    "field10" between (1 to 2)
    "field11" all listOf("value11")
    "field12" startsWith "value12"
    "field13" elemMatch {
        "field14" eq "value14"
    }
    "field15".isNull()
    "field16".notNull()
    and {
        "field3" eq "value3"
        "field4" eq "value4"
    }
    or {
        "field3" eq "value3"
        "field4" eq "value4"
    }
}
```

### SortDsl

```kotlin
sort {
    "field1".asc()
    "field2".desc()
}
```

### PaginationDsl

```kotlin
pagination {
    index(1)
    size(1)
}
```

### QueryDsl

```kotlin
query {
    limit(1)
    sort {
        "field1".asc()
    }
    condition {
        "field1" eq "value1"
        "field2" eq "value2"
        and {
            "field3" eq "value3"
        }
        or {
            "field4" eq "value4"
        }
    }
}
```

### PagedQueryDsl

```kotlin
pagedQuery {
    pagination {
        index(1)
        size(10)
    }
    sort {
        "field1".asc()
    }
    condition {
        "field1" eq "value1"
        "field2" ne "value2"
        "filed3" gt 1
        "field4" lt 1
        "field5" gte 1
        "field6" lte 1
        "field7" contains "value7"
        "field8" isIn listOf("value8")
        "field9" notIn listOf("value9")
        "field10" between (1 to 2)
        "field11" all listOf("value11")
        "field12" startsWith "value12"
        "field13" elemMatch {
            "field14" eq "value14"
        }
        "field15".isNull()
        "field16".notNull()
        and {
            "field3" eq "value3"
            "field4" eq "value4"
        }
        or {
            "field3" eq "value3"
            "field4" eq "value4"
        }
    }
}
```

## 执行查询

```kotlin
query {
    limit(1)
    sort {
        "field1".asc()
    }
    condition {
        "field1" eq "value1"
        and {
            "field3" eq "value3"
        }
        or {
            "field4" eq "value4"
        }
    }
}.query(queryService)
```

## 执行分页查询

```kotlin
pagedQuery {
    pagination {
        index(1)
        size(10)
    }
    sort {
        "field1".asc()
    }
    condition {
        and {
            "field3" eq "value3"
            "field4" startsWith "value4"
        }
        or {
            "field3" eq "value3"
            "field4" startsWith "value4"
        }
    }
}.query(queryService)
```

## OpenAPI

**Wow** 除了为命令(`Command`)自动生成了 _OpenAPI_ 端点，另外还提供了查询(`Query`) _OpenAPI_ 端点。
这意味着开发人员通常只需专注于编写领域模型，即可完成服务开发，而无需费心处理查询逻辑的实现，极大提升了开发效率。

![Query Service](../public/images/query/open-api-query.png)

### 分页查询

::: code-group

```shell [Curl]
  curl -X 'POST' \
  'http://localhost:8080/execution_failed/snapshot/pagination' \
  -H 'accept: application/json' \
  -H 'Content-Type: application/json' \
  -d '{
  "sort": [
    {
      "field": "_id",
      "direction": "DESC"
    }
  ],
  "pagination": {
    "index": 1,
    "size": 10
  },
  "condition": {
    "field": "",
    "operator": "AND",
    "value": "",
    "children": [
      {
        "field": "state.recoverable",
        "operator": "NE",
        "value": "UNRECOVERABLE",
        "children": []
      },
      {
        "field": "state.status",
        "operator": "NE",
        "value": "SUCCEEDED",
        "children": []
      },
      {
        "field": "state.isBelowRetryThreshold",
        "operator": "EQ",
        "value": false,
        "children": []
      }
    ]
  }
}'
```

```json [响应]
{
  "total": 2,
  "list": [
    {
      "contextName": "compensation-service",
      "aggregateName": "execution_failed",
      "aggregateId": "0TyzQ3jc003Z001",
      "tenantId": "(0)",
      "version": 45,
      "eventId": "0U18eEJG004B009",
      "firstOperator": "",
      "operator": "",
      "firstEventTime": 1703082369272,
      "eventTime": 1705050999170,
      "state": {
        "id": "0TyzQ3jc003Z001",
        "eventId": {
          "id": "0TyzQ3jH003v027",
          "aggregateId": {
            "contextName": "order-service",
            "aggregateName": "order",
            "aggregateId": "0TyzQ2xS003v004",
            "tenantId": "0TfcXPA5003V00A"
          },
          "version": 6,
          "initialized": true,
          "isInitialVersion": false
        },
        "processor": {
          "contextName": "notice-service",
          "processorName": "OrderSendMessageSaga"
        },
        "functionKind": "EVENT",
        "error": {
          "errorCode": "BadRequest",
          "errorMsg": "Collection contains no element matching the predicate.",
          "stackTrace": "java.util.NoSuchElementException: Collection contains no element matching the predicate.\n\tat com.linyeah.notice.domains.sendmessage.saga.OrderSendMessageSaga.onOrderPackageReceived(OrderSendMessageSaga.kt:485)\n\tSuppressed: The stacktrace has been enhanced by Reactor, refer to additional information below: \nError has been observed at the following site(s):\n\t*__checkpoint ⇢ Invoke OrderSendMessageSaga.onOrderPackageReceived(OrderPackageReceived) [DomainEventFunctionFilter]\nOriginal Stack Trace:\n\t\tat com.linyeah.notice.domains.sendmessage.saga.OrderSendMessageSaga.onOrderPackageReceived(OrderSendMessageSaga.kt:485)\n\t\tat java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(Native Method)\n\t\tat java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:77)\n\t\tat java.base/jdk.internal.reflect.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:43)\n\t\tat java.base/java.lang.reflect.Method.invoke(Method.java:568)\n\t\tat me.ahoo.wow.infra.accessor.method.FastInvoke.invoke(FastInvoke.java:44)\n\t\tat me.ahoo.wow.infra.accessor.method.MethodAccessor$Companion.invoke(MethodAccessor.kt:40)\n\t\tat me.ahoo.wow.infra.accessor.method.MethodAccessor.invoke(MethodAccessor.kt:30)\n\t\tat me.ahoo.wow.infra.accessor.method.reactive.SimpleMonoMethodAccessor.invoke$lambda$0(SimpleMonoMethodAccessor.kt:27)\n\t\tat reactor.core.publisher.MonoDefer.subscribe(MonoDefer.java:45)\n\t\tat reactor.core.publisher.Mono.subscribe(Mono.java:4495)\n\t\tat reactor.core.publisher.MonoIgnoreThen$ThenIgnoreMain.subscribeNext(MonoIgnoreThen.java:263)\n\t\tat reactor.core.publisher.MonoIgnoreThen.subscribe(MonoIgnoreThen.java:51)\n\t\tat reactor.core.publisher.InternalMonoOperator.subscribe(InternalMonoOperator.java:64)\n\t\tat me.ahoo.wow.opentelemetry.ExchangeTraceMono.subscribe(ExchangeTraceMono.kt:35)\n\t\tat reactor.core.publisher.FluxRetryWhen.subscribe(FluxRetryWhen.java:77)\n\t\tat reactor.core.publisher.MonoRetryWhen.subscribeOrReturn(MonoRetryWhen.java:46)\n\t\tat reactor.core.publisher.Mono.subscribe(Mono.java:4480)\n\t\tat reactor.core.publisher.MonoIgnoreThen$ThenIgnoreMain.subscribeNext(MonoIgnoreThen.java:263)\n\t\tat reactor.core.publisher.MonoIgnoreThen.subscribe(MonoIgnoreThen.java:51)\n\t\tat reactor.core.publisher.InternalMonoOperator.subscribe(InternalMonoOperator.java:64)\n\t\tat me.ahoo.wow.command.wait.MonoCommandWaitNotifier.subscribe(MonoCommandWaitNotifier.kt:42)\n\t\tat reactor.core.publisher.Mono.subscribe(Mono.java:4495)\n\t\tat reactor.core.publisher.FluxFlatMap$FlatMapMain.onNext(FluxFlatMap.java:427)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.lambda$onNext$1(TracingSubscriber.java:64)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:97)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:91)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.onNext(TracingSubscriber.java:64)\n\t\tat reactor.core.publisher.FluxIterable$IterableSubscription.slowPath(FluxIterable.java:335)\n\t\tat reactor.core.publisher.FluxIterable$IterableSubscription.request(FluxIterable.java:294)\n\t\tat reactor.core.publisher.FluxFlatMap$FlatMapMain.onSubscribe(FluxFlatMap.java:371)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.lambda$onSubscribe$0(TracingSubscriber.java:59)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:97)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:91)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.onSubscribe(TracingSubscriber.java:59)\n\t\tat reactor.core.publisher.FluxIterable.subscribe(FluxIterable.java:201)\n\t\tat reactor.core.publisher.FluxIterable.subscribe(FluxIterable.java:83)\n\t\tat reactor.core.publisher.Mono.subscribe(Mono.java:4495)\n\t\tat reactor.core.publisher.FluxConcatMapNoPrefetch$FluxConcatMapNoPrefetchSubscriber.onNext(FluxConcatMapNoPrefetch.java:206)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.lambda$onNext$1(TracingSubscriber.java:64)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:97)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:91)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.onNext(TracingSubscriber.java:64)\n\t\tat reactor.core.publisher.FluxIterable$IterableSubscription.slowPath(FluxIterable.java:335)\n\t\tat reactor.core.publisher.FluxIterable$IterableSubscription.request(FluxIterable.java:294)\n\t\tat reactor.core.publisher.FluxConcatMapNoPrefetch$FluxConcatMapNoPrefetchSubscriber.request(FluxConcatMapNoPrefetch.java:336)\n\t\tat reactor.core.publisher.Operators$MultiSubscriptionSubscriber.set(Operators.java:2341)\n\t\tat reactor.core.publisher.FluxOnErrorResume$ResumeSubscriber.onSubscribe(FluxOnErrorResume.java:74)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.lambda$onSubscribe$0(TracingSubscriber.java:59)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:97)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:91)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.onSubscribe(TracingSubscriber.java:59)\n\t\tat reactor.core.publisher.FluxConcatMapNoPrefetch$FluxConcatMapNoPrefetchSubscriber.onSubscribe(FluxConcatMapNoPrefetch.java:164)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.lambda$onSubscribe$0(TracingSubscriber.java:59)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:97)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:91)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.onSubscribe(TracingSubscriber.java:59)\n\t\tat reactor.core.publisher.FluxIterable.subscribe(FluxIterable.java:201)\n\t\tat reactor.core.publisher.FluxIterable.subscribe(FluxIterable.java:83)\n\t\tat reactor.core.publisher.Flux.subscribe(Flux.java:8773)\n\t\tat reactor.core.publisher.MonoIgnoreThen$ThenIgnoreMain.subscribeNext(MonoIgnoreThen.java:263)\n\t\tat reactor.core.publisher.MonoIgnoreThen.subscribe(MonoIgnoreThen.java:51)\n\t\tat reactor.core.publisher.Mono.subscribe(Mono.java:4495)\n\t\tat reactor.core.publisher.FluxConcatMapNoPrefetch$FluxConcatMapNoPrefetchSubscriber.onNext(FluxConcatMapNoPrefetch.java:206)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.lambda$onNext$1(TracingSubscriber.java:64)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:97)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:91)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.onNext(TracingSubscriber.java:64)\n\t\tat reactor.core.publisher.FluxPublishOn$PublishOnSubscriber.runAsync(FluxPublishOn.java:440)\n\t\tat reactor.core.publisher.FluxPublishOn$PublishOnSubscriber.run(FluxPublishOn.java:527)\n\t\tat reactor.core.scheduler.WorkerTask.call(WorkerTask.java:84)\n\t\tat reactor.core.scheduler.WorkerTask.call(WorkerTask.java:37)\n\t\tat java.base/java.util.concurrent.FutureTask.run(FutureTask.java:264)\n\t\tat java.base/java.util.concurrent.ScheduledThreadPoolExecutor$ScheduledFutureTask.run(ScheduledThreadPoolExecutor.java:304)\n\t\tat java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136)\n\t\tat java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:635)\n\t\tat java.base/java.lang.Thread.run(Thread.java:833)\n",
          "succeeded": false
        },
        "executeAt": 1705050998891,
        "retrySpec": {
          "maxRetries": 10,
          "minBackoff": 180,
          "executionTimeout": 120
        },
        "retryState": {
          "retries": 16,
          "retryAt": 1705050998728,
          "timeoutAt": 1705051118728,
          "nextRetryAt": 1716847478728
        },
        "status": "FAILED",
        "recoverable": "UNKNOWN",
        "isRetryable": false,
        "isBelowRetryThreshold": false
      },
      "snapshotTime": 1705050999195,
      "deleted": false
    },
    {
      "contextName": "compensation-service",
      "aggregateName": "execution_failed",
      "aggregateId": "0TyzPzLb0049001",
      "tenantId": "(0)",
      "version": 29,
      "eventId": "0U18mMQY004B00E",
      "firstOperator": "",
      "operator": "",
      "firstEventTime": 1703082352417,
      "eventTime": 1705052936998,
      "state": {
        "id": "0TyzPzLb0049001",
        "eventId": {
          "id": "0TyzPzL8003v00l",
          "aggregateId": {
            "contextName": "order-service",
            "aggregateName": "order",
            "aggregateId": "0TyzPwpv003v002",
            "tenantId": "0TfcXPA5003V00A"
          },
          "version": 8,
          "initialized": true,
          "isInitialVersion": false
        },
        "processor": {
          "contextName": "notice-service",
          "processorName": "OrderSendMessageSaga"
        },
        "functionKind": "EVENT",
        "error": {
          "errorCode": "BadRequest",
          "errorMsg": "Collection contains no element matching the predicate.",
          "stackTrace": "java.util.NoSuchElementException: Collection contains no element matching the predicate.\n\tat com.linyeah.notice.domains.sendmessage.saga.OrderSendMessageSaga.onOrderPackageReceived(OrderSendMessageSaga.kt:485)\n\tSuppressed: The stacktrace has been enhanced by Reactor, refer to additional information below: \nError has been observed at the following site(s):\n\t*__checkpoint ⇢ Invoke OrderSendMessageSaga.onOrderPackageReceived(OrderPackageReceived) [DomainEventFunctionFilter]\nOriginal Stack Trace:\n\t\tat com.linyeah.notice.domains.sendmessage.saga.OrderSendMessageSaga.onOrderPackageReceived(OrderSendMessageSaga.kt:485)\n\t\tat java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(Native Method)\n\t\tat java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:77)\n\t\tat java.base/jdk.internal.reflect.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:43)\n\t\tat java.base/java.lang.reflect.Method.invoke(Method.java:568)\n\t\tat me.ahoo.wow.infra.accessor.method.FastInvoke.invoke(FastInvoke.java:44)\n\t\tat me.ahoo.wow.infra.accessor.method.MethodAccessor$Companion.invoke(MethodAccessor.kt:40)\n\t\tat me.ahoo.wow.infra.accessor.method.MethodAccessor.invoke(MethodAccessor.kt:30)\n\t\tat me.ahoo.wow.infra.accessor.method.reactive.SimpleMonoMethodAccessor.invoke$lambda$0(SimpleMonoMethodAccessor.kt:27)\n\t\tat reactor.core.publisher.MonoDefer.subscribe(MonoDefer.java:45)\n\t\tat reactor.core.publisher.Mono.subscribe(Mono.java:4495)\n\t\tat reactor.core.publisher.MonoIgnoreThen$ThenIgnoreMain.subscribeNext(MonoIgnoreThen.java:263)\n\t\tat reactor.core.publisher.MonoIgnoreThen.subscribe(MonoIgnoreThen.java:51)\n\t\tat reactor.core.publisher.InternalMonoOperator.subscribe(InternalMonoOperator.java:64)\n\t\tat me.ahoo.wow.opentelemetry.ExchangeTraceMono.subscribe(ExchangeTraceMono.kt:35)\n\t\tat reactor.core.publisher.FluxRetryWhen.subscribe(FluxRetryWhen.java:77)\n\t\tat reactor.core.publisher.MonoRetryWhen.subscribeOrReturn(MonoRetryWhen.java:46)\n\t\tat reactor.core.publisher.Mono.subscribe(Mono.java:4480)\n\t\tat reactor.core.publisher.MonoIgnoreThen$ThenIgnoreMain.subscribeNext(MonoIgnoreThen.java:263)\n\t\tat reactor.core.publisher.MonoIgnoreThen.subscribe(MonoIgnoreThen.java:51)\n\t\tat reactor.core.publisher.InternalMonoOperator.subscribe(InternalMonoOperator.java:64)\n\t\tat me.ahoo.wow.command.wait.MonoCommandWaitNotifier.subscribe(MonoCommandWaitNotifier.kt:42)\n\t\tat reactor.core.publisher.Mono.subscribe(Mono.java:4495)\n\t\tat reactor.core.publisher.FluxFlatMap$FlatMapMain.onNext(FluxFlatMap.java:427)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.lambda$onNext$1(TracingSubscriber.java:64)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:97)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:91)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.onNext(TracingSubscriber.java:64)\n\t\tat reactor.core.publisher.FluxIterable$IterableSubscription.slowPath(FluxIterable.java:335)\n\t\tat reactor.core.publisher.FluxIterable$IterableSubscription.request(FluxIterable.java:294)\n\t\tat reactor.core.publisher.FluxFlatMap$FlatMapMain.onSubscribe(FluxFlatMap.java:371)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.lambda$onSubscribe$0(TracingSubscriber.java:59)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:97)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:91)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.onSubscribe(TracingSubscriber.java:59)\n\t\tat reactor.core.publisher.FluxIterable.subscribe(FluxIterable.java:201)\n\t\tat reactor.core.publisher.FluxIterable.subscribe(FluxIterable.java:83)\n\t\tat reactor.core.publisher.Mono.subscribe(Mono.java:4495)\n\t\tat reactor.core.publisher.FluxConcatMapNoPrefetch$FluxConcatMapNoPrefetchSubscriber.onNext(FluxConcatMapNoPrefetch.java:206)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.lambda$onNext$1(TracingSubscriber.java:64)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:97)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:91)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.onNext(TracingSubscriber.java:64)\n\t\tat reactor.core.publisher.FluxIterable$IterableSubscription.slowPath(FluxIterable.java:335)\n\t\tat reactor.core.publisher.FluxIterable$IterableSubscription.request(FluxIterable.java:294)\n\t\tat reactor.core.publisher.FluxConcatMapNoPrefetch$FluxConcatMapNoPrefetchSubscriber.request(FluxConcatMapNoPrefetch.java:336)\n\t\tat reactor.core.publisher.Operators$MultiSubscriptionSubscriber.set(Operators.java:2341)\n\t\tat reactor.core.publisher.FluxOnErrorResume$ResumeSubscriber.onSubscribe(FluxOnErrorResume.java:74)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.lambda$onSubscribe$0(TracingSubscriber.java:59)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:97)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:91)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.onSubscribe(TracingSubscriber.java:59)\n\t\tat reactor.core.publisher.FluxConcatMapNoPrefetch$FluxConcatMapNoPrefetchSubscriber.onSubscribe(FluxConcatMapNoPrefetch.java:164)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.lambda$onSubscribe$0(TracingSubscriber.java:59)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:97)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:91)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.onSubscribe(TracingSubscriber.java:59)\n\t\tat reactor.core.publisher.FluxIterable.subscribe(FluxIterable.java:201)\n\t\tat reactor.core.publisher.FluxIterable.subscribe(FluxIterable.java:83)\n\t\tat reactor.core.publisher.Flux.subscribe(Flux.java:8773)\n\t\tat reactor.core.publisher.MonoIgnoreThen$ThenIgnoreMain.subscribeNext(MonoIgnoreThen.java:263)\n\t\tat reactor.core.publisher.MonoIgnoreThen.subscribe(MonoIgnoreThen.java:51)\n\t\tat reactor.core.publisher.Mono.subscribe(Mono.java:4495)\n\t\tat reactor.core.publisher.FluxConcatMapNoPrefetch$FluxConcatMapNoPrefetchSubscriber.onNext(FluxConcatMapNoPrefetch.java:206)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.lambda$onNext$1(TracingSubscriber.java:64)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:97)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:91)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.onNext(TracingSubscriber.java:64)\n\t\tat reactor.core.publisher.FluxPublishOn$PublishOnSubscriber.runAsync(FluxPublishOn.java:440)\n\t\tat reactor.core.publisher.FluxPublishOn$PublishOnSubscriber.run(FluxPublishOn.java:527)\n\t\tat reactor.core.scheduler.WorkerTask.call(WorkerTask.java:84)\n\t\tat reactor.core.scheduler.WorkerTask.call(WorkerTask.java:37)\n\t\tat java.base/java.util.concurrent.FutureTask.run(FutureTask.java:264)\n\t\tat java.base/java.util.concurrent.ScheduledThreadPoolExecutor$ScheduledFutureTask.run(ScheduledThreadPoolExecutor.java:304)\n\t\tat java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136)\n\t\tat java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:635)\n\t\tat java.base/java.lang.Thread.run(Thread.java:833)\n",
          "succeeded": false
        },
        "executeAt": 1705052936969,
        "retrySpec": {
          "maxRetries": 10,
          "minBackoff": 180,
          "executionTimeout": 120
        },
        "retryState": {
          "retries": 13,
          "retryAt": 1705052936815,
          "timeoutAt": 1705053056815,
          "nextRetryAt": 1706527496815
        },
        "status": "FAILED",
        "recoverable": "UNKNOWN",
        "isRetryable": false,
        "isBelowRetryThreshold": false
      },
      "snapshotTime": 1705052937019,
      "deleted": false
    }
  ]
}
```

```typescript [Typescript]
Conditions.and(
    [
        Conditions.ne(RECOVERABLE, UNRECOVERABLE),
        Conditions.ne(STATUS, SUCCEEDED),
        Conditions.eq(IS_BELOW_RETRY_THRESHOLD, false)
    ]
)
```

:::

### 查询

::: code-group

```shell [Curl]
  curl -X 'POST' \
  'http://localhost:8080/execution_failed/snapshot/query' \
  -H 'accept: application/json' \
  -H 'Content-Type: application/json' \
  -d '{
  "sort": [
    {
      "field": "_id",
      "direction": "DESC"
    }
  ],
  "limit": 1,
  "condition": {
    "field": "",
    "operator": "AND",
    "value": "",
    "children": [
      {
        "field": "state.recoverable",
        "operator": "NE",
        "value": "UNRECOVERABLE",
        "children": []
      },
      {
        "field": "state.status",
        "operator": "NE",
        "value": "SUCCEEDED",
        "children": []
      },
      {
        "field": "state.isBelowRetryThreshold",
        "operator": "EQ",
        "value": false,
        "children": []
      }
    ]
  }
}'
```

```json [响应]
[
  {
    "contextName": "compensation-service",
    "aggregateName": "execution_failed",
    "aggregateId": "0TyzQ3jc003Z001",
    "tenantId": "(0)",
    "version": 45,
    "eventId": "0U18eEJG004B009",
    "firstOperator": "",
    "operator": "",
    "firstEventTime": 1703082369272,
    "eventTime": 1705050999170,
    "state": {
      "id": "0TyzQ3jc003Z001",
      "eventId": {
        "id": "0TyzQ3jH003v027",
        "aggregateId": {
          "contextName": "order-service",
          "aggregateName": "order",
          "aggregateId": "0TyzQ2xS003v004",
          "tenantId": "0TfcXPA5003V00A"
        },
        "version": 6,
        "initialized": true,
        "isInitialVersion": false
      },
      "processor": {
        "contextName": "notice-service",
        "processorName": "OrderSendMessageSaga"
      },
      "functionKind": "EVENT",
      "error": {
        "errorCode": "BadRequest",
        "errorMsg": "Collection contains no element matching the predicate.",
        "stackTrace": "java.util.NoSuchElementException: Collection contains no element matching the predicate.\n\tat com.linyeah.notice.domains.sendmessage.saga.OrderSendMessageSaga.onOrderPackageReceived(OrderSendMessageSaga.kt:485)\n\tSuppressed: The stacktrace has been enhanced by Reactor, refer to additional information below: \nError has been observed at the following site(s):\n\t*__checkpoint ⇢ Invoke OrderSendMessageSaga.onOrderPackageReceived(OrderPackageReceived) [DomainEventFunctionFilter]\nOriginal Stack Trace:\n\t\tat com.linyeah.notice.domains.sendmessage.saga.OrderSendMessageSaga.onOrderPackageReceived(OrderSendMessageSaga.kt:485)\n\t\tat java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(Native Method)\n\t\tat java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:77)\n\t\tat java.base/jdk.internal.reflect.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:43)\n\t\tat java.base/java.lang.reflect.Method.invoke(Method.java:568)\n\t\tat me.ahoo.wow.infra.accessor.method.FastInvoke.invoke(FastInvoke.java:44)\n\t\tat me.ahoo.wow.infra.accessor.method.MethodAccessor$Companion.invoke(MethodAccessor.kt:40)\n\t\tat me.ahoo.wow.infra.accessor.method.MethodAccessor.invoke(MethodAccessor.kt:30)\n\t\tat me.ahoo.wow.infra.accessor.method.reactive.SimpleMonoMethodAccessor.invoke$lambda$0(SimpleMonoMethodAccessor.kt:27)\n\t\tat reactor.core.publisher.MonoDefer.subscribe(MonoDefer.java:45)\n\t\tat reactor.core.publisher.Mono.subscribe(Mono.java:4495)\n\t\tat reactor.core.publisher.MonoIgnoreThen$ThenIgnoreMain.subscribeNext(MonoIgnoreThen.java:263)\n\t\tat reactor.core.publisher.MonoIgnoreThen.subscribe(MonoIgnoreThen.java:51)\n\t\tat reactor.core.publisher.InternalMonoOperator.subscribe(InternalMonoOperator.java:64)\n\t\tat me.ahoo.wow.opentelemetry.ExchangeTraceMono.subscribe(ExchangeTraceMono.kt:35)\n\t\tat reactor.core.publisher.FluxRetryWhen.subscribe(FluxRetryWhen.java:77)\n\t\tat reactor.core.publisher.MonoRetryWhen.subscribeOrReturn(MonoRetryWhen.java:46)\n\t\tat reactor.core.publisher.Mono.subscribe(Mono.java:4480)\n\t\tat reactor.core.publisher.MonoIgnoreThen$ThenIgnoreMain.subscribeNext(MonoIgnoreThen.java:263)\n\t\tat reactor.core.publisher.MonoIgnoreThen.subscribe(MonoIgnoreThen.java:51)\n\t\tat reactor.core.publisher.InternalMonoOperator.subscribe(InternalMonoOperator.java:64)\n\t\tat me.ahoo.wow.command.wait.MonoCommandWaitNotifier.subscribe(MonoCommandWaitNotifier.kt:42)\n\t\tat reactor.core.publisher.Mono.subscribe(Mono.java:4495)\n\t\tat reactor.core.publisher.FluxFlatMap$FlatMapMain.onNext(FluxFlatMap.java:427)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.lambda$onNext$1(TracingSubscriber.java:64)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:97)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:91)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.onNext(TracingSubscriber.java:64)\n\t\tat reactor.core.publisher.FluxIterable$IterableSubscription.slowPath(FluxIterable.java:335)\n\t\tat reactor.core.publisher.FluxIterable$IterableSubscription.request(FluxIterable.java:294)\n\t\tat reactor.core.publisher.FluxFlatMap$FlatMapMain.onSubscribe(FluxFlatMap.java:371)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.lambda$onSubscribe$0(TracingSubscriber.java:59)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:97)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:91)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.onSubscribe(TracingSubscriber.java:59)\n\t\tat reactor.core.publisher.FluxIterable.subscribe(FluxIterable.java:201)\n\t\tat reactor.core.publisher.FluxIterable.subscribe(FluxIterable.java:83)\n\t\tat reactor.core.publisher.Mono.subscribe(Mono.java:4495)\n\t\tat reactor.core.publisher.FluxConcatMapNoPrefetch$FluxConcatMapNoPrefetchSubscriber.onNext(FluxConcatMapNoPrefetch.java:206)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.lambda$onNext$1(TracingSubscriber.java:64)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:97)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:91)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.onNext(TracingSubscriber.java:64)\n\t\tat reactor.core.publisher.FluxIterable$IterableSubscription.slowPath(FluxIterable.java:335)\n\t\tat reactor.core.publisher.FluxIterable$IterableSubscription.request(FluxIterable.java:294)\n\t\tat reactor.core.publisher.FluxConcatMapNoPrefetch$FluxConcatMapNoPrefetchSubscriber.request(FluxConcatMapNoPrefetch.java:336)\n\t\tat reactor.core.publisher.Operators$MultiSubscriptionSubscriber.set(Operators.java:2341)\n\t\tat reactor.core.publisher.FluxOnErrorResume$ResumeSubscriber.onSubscribe(FluxOnErrorResume.java:74)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.lambda$onSubscribe$0(TracingSubscriber.java:59)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:97)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:91)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.onSubscribe(TracingSubscriber.java:59)\n\t\tat reactor.core.publisher.FluxConcatMapNoPrefetch$FluxConcatMapNoPrefetchSubscriber.onSubscribe(FluxConcatMapNoPrefetch.java:164)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.lambda$onSubscribe$0(TracingSubscriber.java:59)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:97)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:91)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.onSubscribe(TracingSubscriber.java:59)\n\t\tat reactor.core.publisher.FluxIterable.subscribe(FluxIterable.java:201)\n\t\tat reactor.core.publisher.FluxIterable.subscribe(FluxIterable.java:83)\n\t\tat reactor.core.publisher.Flux.subscribe(Flux.java:8773)\n\t\tat reactor.core.publisher.MonoIgnoreThen$ThenIgnoreMain.subscribeNext(MonoIgnoreThen.java:263)\n\t\tat reactor.core.publisher.MonoIgnoreThen.subscribe(MonoIgnoreThen.java:51)\n\t\tat reactor.core.publisher.Mono.subscribe(Mono.java:4495)\n\t\tat reactor.core.publisher.FluxConcatMapNoPrefetch$FluxConcatMapNoPrefetchSubscriber.onNext(FluxConcatMapNoPrefetch.java:206)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.lambda$onNext$1(TracingSubscriber.java:64)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:97)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:91)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.onNext(TracingSubscriber.java:64)\n\t\tat reactor.core.publisher.FluxPublishOn$PublishOnSubscriber.runAsync(FluxPublishOn.java:440)\n\t\tat reactor.core.publisher.FluxPublishOn$PublishOnSubscriber.run(FluxPublishOn.java:527)\n\t\tat reactor.core.scheduler.WorkerTask.call(WorkerTask.java:84)\n\t\tat reactor.core.scheduler.WorkerTask.call(WorkerTask.java:37)\n\t\tat java.base/java.util.concurrent.FutureTask.run(FutureTask.java:264)\n\t\tat java.base/java.util.concurrent.ScheduledThreadPoolExecutor$ScheduledFutureTask.run(ScheduledThreadPoolExecutor.java:304)\n\t\tat java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136)\n\t\tat java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:635)\n\t\tat java.base/java.lang.Thread.run(Thread.java:833)\n",
        "succeeded": false
      },
      "executeAt": 1705050998891,
      "retrySpec": {
        "maxRetries": 10,
        "minBackoff": 180,
        "executionTimeout": 120
      },
      "retryState": {
        "retries": 16,
        "retryAt": 1705050998728,
        "timeoutAt": 1705051118728,
        "nextRetryAt": 1716847478728
      },
      "status": "FAILED",
      "recoverable": "UNKNOWN",
      "isRetryable": false,
      "isBelowRetryThreshold": false
    },
    "snapshotTime": 1705050999195,
    "deleted": false
  }
]
```

:::

### 计数(`Count`)

::: code-group

```shell [Curl]
  curl -X 'POST' \
  'http://localhost:8080/execution_failed/snapshot/count' \
  -H 'accept: application/json' \
  -H 'Content-Type: application/json' \
  -d '{
    "field": "",
    "operator": "AND",
    "value": "",
    "children": [
      {
        "field": "state.recoverable",
        "operator": "NE",
        "value": "UNRECOVERABLE",
        "children": []
      },
      {
        "field": "state.status",
        "operator": "NE",
        "value": "SUCCEEDED",
        "children": []
      },
      {
        "field": "state.isBelowRetryThreshold",
        "operator": "EQ",
        "value": false,
        "children": []
      }
    ]
  }'
```

```json [响应]
2
```

:::

### 获取单个模型

::: code-group

```shell [Curl]
  curl -X 'POST' \
  'http://localhost:8080/execution_failed/snapshot/single' \
  -H 'accept: application/json' \
  -H 'Content-Type: application/json' \
  -d '{
    "field": "_id",
    "operator": "EQ",
    "value": "0TyzQ3jc003Z001",
    "children": []
  }'
```

```json [响应]
{
  "contextName": "compensation-service",
  "aggregateName": "execution_failed",
  "aggregateId": "0TyzQ3jc003Z001",
  "tenantId": "(0)",
  "version": 45,
  "eventId": "0U18eEJG004B009",
  "firstOperator": "",
  "operator": "",
  "firstEventTime": 1703082369272,
  "eventTime": 1705050999170,
  "state": {
    "id": "0TyzQ3jc003Z001",
    "eventId": {
      "id": "0TyzQ3jH003v027",
      "aggregateId": {
        "contextName": "order-service",
        "aggregateName": "order",
        "aggregateId": "0TyzQ2xS003v004",
        "tenantId": "0TfcXPA5003V00A"
      },
      "version": 6,
      "initialized": true,
      "isInitialVersion": false
    },
    "processor": {
      "contextName": "notice-service",
      "processorName": "OrderSendMessageSaga"
    },
    "functionKind": "EVENT",
    "error": {
      "errorCode": "BadRequest",
      "errorMsg": "Collection contains no element matching the predicate.",
      "stackTrace": "java.util.NoSuchElementException: Collection contains no element matching the predicate.\n\tat com.linyeah.notice.domains.sendmessage.saga.OrderSendMessageSaga.onOrderPackageReceived(OrderSendMessageSaga.kt:485)\n\tSuppressed: The stacktrace has been enhanced by Reactor, refer to additional information below: \nError has been observed at the following site(s):\n\t*__checkpoint ⇢ Invoke OrderSendMessageSaga.onOrderPackageReceived(OrderPackageReceived) [DomainEventFunctionFilter]\nOriginal Stack Trace:\n\t\tat com.linyeah.notice.domains.sendmessage.saga.OrderSendMessageSaga.onOrderPackageReceived(OrderSendMessageSaga.kt:485)\n\t\tat java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(Native Method)\n\t\tat java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:77)\n\t\tat java.base/jdk.internal.reflect.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:43)\n\t\tat java.base/java.lang.reflect.Method.invoke(Method.java:568)\n\t\tat me.ahoo.wow.infra.accessor.method.FastInvoke.invoke(FastInvoke.java:44)\n\t\tat me.ahoo.wow.infra.accessor.method.MethodAccessor$Companion.invoke(MethodAccessor.kt:40)\n\t\tat me.ahoo.wow.infra.accessor.method.MethodAccessor.invoke(MethodAccessor.kt:30)\n\t\tat me.ahoo.wow.infra.accessor.method.reactive.SimpleMonoMethodAccessor.invoke$lambda$0(SimpleMonoMethodAccessor.kt:27)\n\t\tat reactor.core.publisher.MonoDefer.subscribe(MonoDefer.java:45)\n\t\tat reactor.core.publisher.Mono.subscribe(Mono.java:4495)\n\t\tat reactor.core.publisher.MonoIgnoreThen$ThenIgnoreMain.subscribeNext(MonoIgnoreThen.java:263)\n\t\tat reactor.core.publisher.MonoIgnoreThen.subscribe(MonoIgnoreThen.java:51)\n\t\tat reactor.core.publisher.InternalMonoOperator.subscribe(InternalMonoOperator.java:64)\n\t\tat me.ahoo.wow.opentelemetry.ExchangeTraceMono.subscribe(ExchangeTraceMono.kt:35)\n\t\tat reactor.core.publisher.FluxRetryWhen.subscribe(FluxRetryWhen.java:77)\n\t\tat reactor.core.publisher.MonoRetryWhen.subscribeOrReturn(MonoRetryWhen.java:46)\n\t\tat reactor.core.publisher.Mono.subscribe(Mono.java:4480)\n\t\tat reactor.core.publisher.MonoIgnoreThen$ThenIgnoreMain.subscribeNext(MonoIgnoreThen.java:263)\n\t\tat reactor.core.publisher.MonoIgnoreThen.subscribe(MonoIgnoreThen.java:51)\n\t\tat reactor.core.publisher.InternalMonoOperator.subscribe(InternalMonoOperator.java:64)\n\t\tat me.ahoo.wow.command.wait.MonoCommandWaitNotifier.subscribe(MonoCommandWaitNotifier.kt:42)\n\t\tat reactor.core.publisher.Mono.subscribe(Mono.java:4495)\n\t\tat reactor.core.publisher.FluxFlatMap$FlatMapMain.onNext(FluxFlatMap.java:427)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.lambda$onNext$1(TracingSubscriber.java:64)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:97)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:91)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.onNext(TracingSubscriber.java:64)\n\t\tat reactor.core.publisher.FluxIterable$IterableSubscription.slowPath(FluxIterable.java:335)\n\t\tat reactor.core.publisher.FluxIterable$IterableSubscription.request(FluxIterable.java:294)\n\t\tat reactor.core.publisher.FluxFlatMap$FlatMapMain.onSubscribe(FluxFlatMap.java:371)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.lambda$onSubscribe$0(TracingSubscriber.java:59)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:97)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:91)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.onSubscribe(TracingSubscriber.java:59)\n\t\tat reactor.core.publisher.FluxIterable.subscribe(FluxIterable.java:201)\n\t\tat reactor.core.publisher.FluxIterable.subscribe(FluxIterable.java:83)\n\t\tat reactor.core.publisher.Mono.subscribe(Mono.java:4495)\n\t\tat reactor.core.publisher.FluxConcatMapNoPrefetch$FluxConcatMapNoPrefetchSubscriber.onNext(FluxConcatMapNoPrefetch.java:206)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.lambda$onNext$1(TracingSubscriber.java:64)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:97)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:91)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.onNext(TracingSubscriber.java:64)\n\t\tat reactor.core.publisher.FluxIterable$IterableSubscription.slowPath(FluxIterable.java:335)\n\t\tat reactor.core.publisher.FluxIterable$IterableSubscription.request(FluxIterable.java:294)\n\t\tat reactor.core.publisher.FluxConcatMapNoPrefetch$FluxConcatMapNoPrefetchSubscriber.request(FluxConcatMapNoPrefetch.java:336)\n\t\tat reactor.core.publisher.Operators$MultiSubscriptionSubscriber.set(Operators.java:2341)\n\t\tat reactor.core.publisher.FluxOnErrorResume$ResumeSubscriber.onSubscribe(FluxOnErrorResume.java:74)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.lambda$onSubscribe$0(TracingSubscriber.java:59)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:97)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:91)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.onSubscribe(TracingSubscriber.java:59)\n\t\tat reactor.core.publisher.FluxConcatMapNoPrefetch$FluxConcatMapNoPrefetchSubscriber.onSubscribe(FluxConcatMapNoPrefetch.java:164)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.lambda$onSubscribe$0(TracingSubscriber.java:59)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:97)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:91)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.onSubscribe(TracingSubscriber.java:59)\n\t\tat reactor.core.publisher.FluxIterable.subscribe(FluxIterable.java:201)\n\t\tat reactor.core.publisher.FluxIterable.subscribe(FluxIterable.java:83)\n\t\tat reactor.core.publisher.Flux.subscribe(Flux.java:8773)\n\t\tat reactor.core.publisher.MonoIgnoreThen$ThenIgnoreMain.subscribeNext(MonoIgnoreThen.java:263)\n\t\tat reactor.core.publisher.MonoIgnoreThen.subscribe(MonoIgnoreThen.java:51)\n\t\tat reactor.core.publisher.Mono.subscribe(Mono.java:4495)\n\t\tat reactor.core.publisher.FluxConcatMapNoPrefetch$FluxConcatMapNoPrefetchSubscriber.onNext(FluxConcatMapNoPrefetch.java:206)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.lambda$onNext$1(TracingSubscriber.java:64)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:97)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.withActiveSpan(TracingSubscriber.java:91)\n\t\tat io.opentelemetry.javaagent.shaded.instrumentation.reactor.v3_1.TracingSubscriber.onNext(TracingSubscriber.java:64)\n\t\tat reactor.core.publisher.FluxPublishOn$PublishOnSubscriber.runAsync(FluxPublishOn.java:440)\n\t\tat reactor.core.publisher.FluxPublishOn$PublishOnSubscriber.run(FluxPublishOn.java:527)\n\t\tat reactor.core.scheduler.WorkerTask.call(WorkerTask.java:84)\n\t\tat reactor.core.scheduler.WorkerTask.call(WorkerTask.java:37)\n\t\tat java.base/java.util.concurrent.FutureTask.run(FutureTask.java:264)\n\t\tat java.base/java.util.concurrent.ScheduledThreadPoolExecutor$ScheduledFutureTask.run(ScheduledThreadPoolExecutor.java:304)\n\t\tat java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136)\n\t\tat java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:635)\n\t\tat java.base/java.lang.Thread.run(Thread.java:833)\n",
      "succeeded": false
    },
    "executeAt": 1705050998891,
    "retrySpec": {
      "maxRetries": 10,
      "minBackoff": 180,
      "executionTimeout": 120
    },
    "retryState": {
      "retries": 16,
      "retryAt": 1705050998728,
      "timeoutAt": 1705051118728,
      "nextRetryAt": 1716847478728
    },
    "status": "FAILED",
    "recoverable": "UNKNOWN",
    "isRetryable": false,
    "isBelowRetryThreshold": false
  },
  "snapshotTime": 1705050999195,
  "deleted": false
}
```

:::

