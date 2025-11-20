# Event Compensation

> The role of *event compensation* in event-driven architecture is to handle and recover from data inconsistencies or error states caused by event processing failures.
> When an event processing fails in the system, the _event compensation_ mechanism intervenes and executes corresponding compensation operations to ensure system state is repaired and consistency is maintained.
> This mechanism helps ensure system reliability and stability, especially in complex distributed systems where events may be passed between multiple components or services.
> Through _event compensation_, systems can better handle faults and exceptions, prevent the propagation of error states, and ultimately ensure the system can recover from failures and continue normal operation.

The event compensation module provides a visual event compensation console and automatic compensation mechanism to ensure eventual consistency of system data.

## Use Case Scenarios

- Subscriber Service:
  - Subscribe to domain events and execute business logic.
  - Send execution failure records when execution fails.
  - Send execution success records when execution succeeds.
- Console:
  - Distributed compensation scheduler: Periodically check pending compensation events and execute prepare compensation operations.
  - Notifications: Send notification messages when execution failure exceptions occur.
- Developers:
  - Monitoring: Monitor compensation events, execute retry operations to re-trigger compensation logic, or mark compensation events that are no longer needed.
  - Receive notifications: Receive execution failure notifications to quickly locate exceptions and fix bugs.
  - Fix bugs: When unfixable exceptions occur, developers can quickly locate exceptions through the console, fix bugs, and re-initiate retries.

![Event-Compensation-UserCase](../../public/images/compensation/usercase.svg)

## State Diagram

![Event-Compensation](../../public/images/compensation/state-diagram.svg)

## Execution Sequence Diagram

![Event-Compensation](../../public/images/compensation/process-sequence-diagram.svg)

## Subscriber Service

> By default, the *subscriber service* module has event compensation functionality enabled.
>
> If you want to globally disable this feature, simply set `wow.compensation.enabled=false` in the configuration file.
>
> This simple configuration option provides developers with an easy way to manage event compensation functionality across the entire system.

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

### Custom Retry Mechanism

The flexibility of the _Wow_ framework is not only reflected in the design of the event compensation console, but also allows for custom compensation mechanisms through the `@Retry` annotation.

```kotlin
@Target(AnnotationTarget.FUNCTION)
annotation class Retry(
    val enabled: Boolean = true,
    /**
     * Maximum retry count
     */
    val maxRetries: Int = DEFAULT_MAX_RETRIES,

    /**
     * the minimum Duration for the first backoff
     *
     * @see java.time.temporal.ChronoUnit.SECONDS
     */
    val minBackoff: Int = DEFAULT_MIN_BACKOFF,

    /**
     * Execution timeout
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

If a specific handler function does not need compensation, simply set `@Retry(false)` to disable the compensation functionality for that function.

This fine-grained control allows developers to configure system behavior more precisely, ensuring that compensation mechanisms work only where needed, further improving the system's customizability and adaptability.

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
`@Retry` simultaneously supports `recoverable` and `unrecoverable` parameters to mark the recoverability when exceptions occur in execution functions:
- `recoverable`: Exceptions that can be fixed through retries.
- `unrecoverable`: Exceptions that cannot be fixed through retries.
:::

## Console

The powerful features of the *event compensation console* include not only distributed scheduled automatic compensation, but also intuitive visual compensation event management functions, event compensation notifications (WeChat Work), and _OpenAPI_ interfaces.

Through distributed scheduled automatic compensation, the _Wow_ framework intelligently solves the problem of eventual consistency of system data, eliminating the tedious process of manual compensation.
The visual compensation event management function provides great convenience for developers to easily monitor and handle compensation events.

On the console, developers can easily query compensation events of specific states, execute retry operations to re-trigger compensation logic, or delete compensation events that are no longer needed, providing flexible and intuitive operation methods.

This design not only enhances the robustness and maintainability of the system, but also makes it easier for developers to handle complex distributed transaction processes, ensuring that the system can perform compensation operations correctly and controllably in abnormal situations.

:::tip
The [event compensation console](https://github.com/Ahoo-Wang/Wow/tree/main/compensation) is also developed based on the _Wow_ framework. It can be used as a reference implementation for learning the _Wow_ framework.
:::

### UI

![Event-Compensation-Dashboard](../../public/images/compensation/dashboard.png)

![Event-Compensation-Dashboard](../../public/images/compensation/dashboard-apply-retry-spec.png)

![Event-Compensation-Dashboard](../../public/images/compensation/dashboard-succeeded.png)

![Event-Compensation-Dashboard](../../public/images/compensation/dashboard-error.png)

### Notifications (WeChat Work)

By configuring the *WeChat Work group robot* *WebHook address*, you can enable *WeChat Work* notification functionality.
At the same time, by configuring the `events` property, you can choose to receive notifications for specific events only.
`events` defaults to include: `[execution_failed_created ,execution_failed_applied, execution_success_applied]`

```yaml{3,6,7}
wow:
  compensation:
    host:  # Optional, after configuring this option, developers can quickly navigate to the exception through notification messages
    webhook:
      weixin:
        url: # Group robot Webhook address
        events:
          - execution_failed_created
          - execution_failed_applied
          - execution_success_applied
```

| Failed                                                                     | Success                                                                      |
|----------------------------------------------------------------------------|------------------------------------------------------------------------------|
| ![Execution Failed](../../public/images/compensation/execution-failed.png) | ![Execution Success](../../public/images/compensation/execution-success.png) |

### OpenAPI

The _event compensation console_ provides developer-friendly [RESTful OpenAPI](https://wow-compensation.apifox.cn/) for easy integration and invocation of event compensation functions.
Through this interface, developers can implement management and control of compensation events in their own systems. This enhances the system's scalability and customizability, allowing developers to respond more flexibly to various application scenarios.

![Event-Compensation-OpenAPI](../../public/images/compensation/open-api.png)

### Deployment (Kubernetes)

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