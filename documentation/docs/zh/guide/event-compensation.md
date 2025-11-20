# 事件补偿

> *事件补偿*在事件驱动架构中的作用是处理和恢复因为事件处理失败而导致的数据不一致性或错误状态。
> 当系统中的某个事件处理失败时，_event compensation_ 机制会介入并执行相应的补偿操作，以确保系统状态得以修复并保持一致性。
> 这个机制有助于保障系统的可靠性和稳定性，尤其是在复杂的分布式系统中，其中事件可能在多个组件或服务之间传递。
> 通过 _event compensation_，系统可以更好地处理故障和异常情况，防止错误状态的传播，并最终确保系统能够从失败中恢复并继续正常运行。

事件补偿模块提供了可视化的事件补偿控制台和自动补偿机制，确保系统数据的最终一致性。

## 用例场景

- 订阅者服务：
  - 订阅领域事件，执行业务逻辑。
  - 在执行失败时，发送执行失败记录。
  - 在执行成功时，发送执行成功记录。
- 控制台：
  - 分布式补偿调度器： 定时检查待补偿事件并执行准备补偿操作。
  - 通知： 在发生执行失败异常时，发送通知消息。
- 开发者：
  - 监控： 监控补偿事件，执行重试操作以重新触发补偿逻辑，或标记不再需要的补偿事件。
  - 接收通知： 接收执行失败通知，快速定位到异常，修复 BUG。
  - 修复 BUG： 当发生无法自动修复的异常时，开发者可以通过控制台快速定位到异常，修复 BUG，并重新发起重试。

![Event-Compensation-UserCase](../../public/images/compensation/usercase.svg)

## 状态图

![Event-Compensation](../../public/images/compensation/state-diagram.svg)

## 执行时序图

![Event-Compensation](../../public/images/compensation/process-sequence-diagram.svg)

## 订阅者服务

> 默认情况下，*订阅者服务* 模块已经启用了事件补偿功能。
> 
> 如果你希望全局关闭该功能，只需在配置文件中设置 `wow.compensation.enabled=false` 即可。
> 
> 这一简便的配置选项为开发者提供了在整个系统范围内轻松管理事件补偿功能的途径。

::: code-group
```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-compensation-core")
```
```groovy [Gradle(Groovy)]
implementation 'me.ahoo.wow:wow-compensation-core'
```
```xml [Maven]
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-compensation-core</artifactId>
</dependency>
```
:::

### 自定义重试机制

_Wow_ 框架的灵活性不仅体现在事件补偿控制台的设计上，还可以通过 `@Retry` 注解进行自定义补偿机制。

```kotlin
@Target(AnnotationTarget.FUNCTION)
annotation class Retry(
    val enabled: Boolean = true,
    /**
     * 最大重试次数
     */
    val maxRetries: Int = DEFAULT_MAX_RETRIES,

    /**
     * the minimum Duration for the first backoff
     *
     * @see java.time.temporal.ChronoUnit.SECONDS
     */
    val minBackoff: Int = DEFAULT_MIN_BACKOFF,

    /**
     * 执行超时时间
     *
     * @see java.time.temporal.ChronoUnit.SECONDS
     */
    val executionTimeout: Int = DEFAULT_EXECUTION_TIMEOUT,
    val recoverable: Array<KClass<out Throwable>> = [],
    val unrecoverable: Array<KClass<out Throwable>> = []
) {
    companion object {
        const val DEFAULT_MAX_RETRIES = 10
        const val DEFAULT_MIN_BACKOFF = 180
        const val DEFAULT_EXECUTION_TIMEOUT = 120
    }
}
```

如果有特定处理函数不需要补偿，只需简单设置 `@Retry(false)` 即可关闭该函数的补偿功能。

这种细粒度的控制让开发者能够更加精准地配置系统的行为，确保补偿机制仅在需要的地方发挥作用，进一步提升系统的可定制性和适应性。

```kotlin{1}
    @Retry(maxRetries = 5, minBackoff = 60, executionTimeout = 10)
    @OnEvent
    fun onOrderCreated(orderCreated: OrderCreated): RemoveCartItem? {
        if (!orderCreated.fromCart) {
            return null
        }
        return RemoveCartItem(
            id = orderCreated.customerId,
            productIds = orderCreated.items.map { it.productId }.toSet(),
        )
    }
```

:::tip
`@Retry` 同时支持 `recoverable` 和 `unrecoverable` 两类参数，用于标记执行函数发生异常时的可恢复性：
- `recoverable`：表示可以通过重试来修复的异常。
- `unrecoverable`：表示无法通过重试来修复的异常。
:::

## 控制台

*事件补偿控制台*的强大功能不仅包括分布式定时调度自动补偿，还搭载直观可视化的补偿事件管理功能、事件补偿通知（企业微信）以及 _OpenAPI_ 接口。

通过分布式定时自动补偿，_Wow_ 框架智能地解决了系统数据最终一致性的难题，摆脱了手动补偿的繁琐过程。
而可视化的补偿事件管理功能为开发者提供了巨大便利，轻松监控和处理补偿事件。

在控制台上，开发者能轻松进行特定状态的补偿事件查询，执行重试操作以重新触发补偿逻辑，或删除不再需要的补偿事件，提供了灵活而直观的操作手段。

这一设计不仅增强了系统的稳健性和可维护性，同时也让开发者更容易处理复杂的分布式事务流程，确保系统在异常情况下能够正确而可控地进行补偿操作。

:::tip
[事件补偿控制台](https://github.com/Ahoo-Wang/Wow/tree/main/compensation) 也是基于 _Wow_ 框架设计开发的。可以作为 _Wow_ 框架的参考实现来学习。
::: 

### UI

![Event-Compensation-Dashboard](../../public/images/compensation/dashboard.png)

![Event-Compensation-Dashboard](../../public/images/compensation/dashboard-apply-retry-spec.png)

![Event-Compensation-Dashboard](../../public/images/compensation/dashboard-succeeded.png)

![Event-Compensation-Dashboard](../../public/images/compensation/dashboard-error.png)

### 通知（企业微信）

通过配置 *企业微信群机器人* *WebHook地址* 即可开启*企业微信*通知功能。
同时，通过配置 `events` 属性，可选择只接收特定事件的通知。
`events` 默认包括：`[execution_failed_created ,execution_failed_applied, execution_success_applied]`

```yaml{3,6,7}
wow:
  compensation:
    host:  # 可选，配置该选项后，开发者可以通过通知消息快速导航到该异常
    webhook:
      weixin:
        url: # 群机器人 Webhook 地址
        events:
          - execution_failed_created
          - execution_failed_applied
          - execution_success_applied
```

| 失败                                                             | 成功                                                              |
|----------------------------------------------------------------|-----------------------------------------------------------------|
| ![执行失败](../../public/images/compensation/execution-failed.png) | ![执行成功](../../public/images/compensation/execution-success.png) |

### OpenAPI

_事件补偿控制台_ 提供了开发者友好的 [RESTful OpenAPI](https://wow-compensation.apifox.cn/)，方便集成和调用事件补偿功能。
通过这个接口，开发者可以在自己的系统中实现对补偿事件的管理和控制。增强了系统的扩展性和定制性，使开发者能够更灵活地应对各种应用场景。

![Event-Compensation-OpenAPI](../../public/images/compensation/open-api.png)

### 部署 (Kubernetes)

::: code-group
```yaml [ConfigMap]
apiVersion: v1
kind: ConfigMap
metadata:
  name: compensation-service-config
data:
  application.yaml: >-
    management:
      endpoint:
        health:
          show-details: always
          probes:
            enabled: true
      endpoints:
        web:
          exposure:
            include:
              - health
              - cosid
              - cosidGenerator
              - cosidStringGenerator
    springdoc:
      show-actuator: true
    logging:
      level:
        me.ahoo.wow: debug
    spring:
      application:
        name: compensation-service
      web:
        resources:
          static-locations: file:./browser/
      data:
        mongodb:
          uri: mongodb://root:root@localhost:27017/compensation_db?authSource=admin&maxIdleTimeMS=60000
        redis:
          cluster:
            max-redirects: 3
            nodes:
                - redis-test-redis-cluster-0.redis-test-redis-cluster-headless.test.svc.cluster.local:6379
          password: VPI7MsrrF7beIg
    cosid:
      machine:
        enabled: true
        distributor:
          type: redis
      generator:
        enabled: true
    wow:
      kafka:
        bootstrap-servers: 'kafka-test-0.kafka-test-headless.test.svc.cluster.local:9093'
```

```yaml [Deployment]
apiVersion: apps/v1
kind: Deployment
metadata:
  name: compensation-service
  labels:
    app: compensation-service
spec:
  replicas: 1
  strategy:
    rollingUpdate:
      maxSurge: 25%
      maxUnavailable: 25%
    type: RollingUpdate
  selector:
    matchLabels:
      app: compensation-service
  template:
    metadata:
      labels:
        app: compensation-service
      annotations:
        instrumentation.opentelemetry.io/inject-java: "true"
    spec:
      containers:
        - name: compensation-service
          image: registry.cn-shanghai.aliyuncs.com/ahoo/wow-compensation-server:2.10.4
          env:
            - name: LANG
              value: C.utf8
            - name: TZ
              value: Asia/Shanghai
            - name: JAVA_OPTS
              value: -Xms1792M  -Xmx1792M
          ports:
            - name: http
              protocol: TCP
              containerPort: 8080
          startupProbe:
            failureThreshold: 15
            httpGet:
              path: /actuator/health
              port: http
              scheme: HTTP
            periodSeconds: 10
            successThreshold: 1
            timeoutSeconds: 1
          readinessProbe:
            failureThreshold: 6
            httpGet:
              path: /actuator/health
              port: http
              scheme: HTTP
            periodSeconds: 10
            successThreshold: 1
            initialDelaySeconds: 30
            timeoutSeconds: 1
          livenessProbe:
            failureThreshold: 6
            httpGet:
              path: /actuator/health
              port: http
              scheme: HTTP
            periodSeconds: 10
            successThreshold: 1
            initialDelaySeconds: 30
            timeoutSeconds: 1
          resources:
            limits:
              cpu: "4"
              memory: 2560Mi
            requests:
              cpu: '2'
              memory: 1792Mi
          volumeMounts:
            - mountPath: /etc/localtime
              name: volume-localtime
            - mountPath: /opt/wow-compensation-server/config/
              name: compensation-service-config
      volumes:
        - hostPath:
            path: /etc/localtime
            type: ""
          name: volume-localtime
        - name: compensation-service-config
          configMap:
            name: compensation-service-config
```

```yaml [HPA]
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: compensation-service-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: compensation-service
  minReplicas: 2
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 80
```

```yaml [Service]
apiVersion: v1
kind: Service
metadata:
  name: compensation-service
spec:
  selector:
    app: compensation-service
  ports:
    - protocol: TCP
      port: 80
      targetPort: http
```
:::