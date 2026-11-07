---
title: Kubernetes
description: 用 Wow 的 Helm chart 在 Kubernetes 上运行 Wow 服务，它的 values 由构建生成；chart 渲染的对象、节点的身份和地址、其他命名空间的对端、探针、优雅关闭、伸缩、监控、中断预算与升级。
outline: deep
---

# Kubernetes

Wow 服务在 Kubernetes 上以一个 Deployment 运行：它的 Pod 就是服务的节点，它们通过服务的数据库互相发现，其他服务通过一个 headless Service 访问它们。别的都不需要：没有 Operator，没有 ZooKeeper，也没有 Kafka 消费组。Wow 的 Helm chart `wow-service` 为一个服务渲染出这些对象，随 Wow 发布，版本即 Wow 的版本。平台为每个服务各安装一次。本页用它安装 eShop 的 ordering 服务；背后的思路见[部署模型](./topology)。

## 安装一个服务 {#install-a-service}

一个 release 取两个 values 文件，后一个覆盖前一个：

1. **构建知道的 values。** `./gradlew :<server>:generateWowHelmValues` 按服务的契约把它们写进 `build/wow/helm/values.yaml`：服务名，chart 以它命名每个对象；类路径上有契约的对端，各带托管它的服务；以及节点提供 Manifest 的路由。它们随代码变化，不要手抄。basket 的：

   ```yaml
   service:
     name: "eshop-basket-service"
     peers:
       - context: "eshop-ordering"
         service: "eshop-ordering-service"
     manifest:
       route: "/wow/manifest"
   ```

2. **部署自己的 values。** 镜像、端口、副本数、所属平台、profile、存储及其密钥、资源。ordering 服务的，在 [`deploy/eshop-ordering`](https://github.com/Ahoo-Wang/Wow/tree/main/deploy/eshop-ordering) 中：

<!-- sample: deploy/eshop-ordering/values.yaml -->

```yaml
# The eshop-ordering service's own values for the wow-service chart (deploy/helm/wow-service), on MongoDB and Kafka:
# what the build does not know. Pass them after the values its build generates, so they override them:
#
#   ./gradlew :eshop-ordering-server:generateWowHelmValues
#   helm install eshop-ordering deploy/helm/wow-service \
#     -f examples/eshop/ordering/ordering-server/build/wow/helm/values.yaml -f deploy/eshop-ordering/values.yaml
#
# values-postgres.yaml after these puts the service on PostgreSQL instead.
image:
  # The image built from this repository (README.md).
  repository: wow-eshop-ordering-server
  tag: local
# The ports of the image's own configuration (application.yaml), which the chart sets on the pod.
ports:
  http: 8083
  internal: 9093
# The nodes share the partitions through leases in the service's database: no ZooKeeper, no consumer group.
replicas: 2
# The internal port admits the pods of the platform, labelled app.kubernetes.io/part-of: eshop.
partOf: eshop
config:
  # The mongo profile keeps the service's data on MongoDB, the replica set at secret.yaml's URI (deploy/mongodb), in a
  # database of its own; the kafka profile connects it to the other contexts through Kafka; the cosec profile takes the
  # callers from the CoSec gateway in front of the service.
  SPRING_PROFILES_ACTIVE: mongo,kafka,cosec
  WOW_KAFKA_BOOTSTRAPSERVERS: kafka:9092
  # The topics the service owns are created by the operator; the node only validates them. The kafka profile's auto
  # is for trying the service out.
  WOW_KAFKA_DDL: validate
  # Fixed once the context's log holds entries; the command topic wow.eshop-ordering.commands has as many partitions.
  WOW_LOG_PARTITIONS: "64"
  # Traces and metrics: the node's spans and Micrometer meters go through the OpenTelemetry Java agent, which the
  # OpenTelemetry Operator injects (podAnnotations, with an Instrumentation resource in the namespace). Without the
  # agent they are no-ops. It exports to the collector below over OTLP, and bridges Micrometer's global registry.
  OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-collector:4318
  OTEL_EXPORTER_OTLP_PROTOCOL: http/protobuf
  OTEL_INSTRUMENTATION_MICROMETER_ENABLED: "true"
# The database's connection: secret.yaml, an example to replace.
envFrom:
  - secretRef:
      name: eshop-ordering-service-mongo
podAnnotations:
  instrumentation.opentelemetry.io/inject-java: "true"
resources:
  requests:
    cpu: 500m
    memory: 768Mi
  limits:
    memory: 1Gi
```

然后从 OCI 仓库按服务构建所用的 Wow 版本安装，或从仓库的检出（`deploy/helm/wow-service`）安装：

```bash
helm upgrade --install eshop-ordering oci://ghcr.io/ahoo-wang/wow-service --version <wow-version> \
  -f examples/eshop/ordering/ordering-server/build/wow/helm/values.yaml \
  -f deploy/eshop-ordering/values.yaml --wait
```

chart 也在 `oci://registry-1.docker.io/ahoowang/wow-service` 和 `oci://registry.cn-shanghai.aliyuncs.com/ahoo/wow-service`。它的 `values.schema.json` 只接受 chart 声明的键：拼错的键会让安装失败，正如节点拒绝它不认识的 `wow.*` 键。chart 的 [README](https://github.com/Ahoo-Wang/Wow/tree/main/deploy/helm/wow-service) 列出每个 value。

### 安装之前 {#before-you-install}

1. 构建镜像并推送，或者把它加载到本地集群。
2. 准备数据库；服务用到 Kafka 时，也准备 Kafka。`deploy/mongodb` 里有一个可作起点的 MongoDB 副本集；要用 PostgreSQL，就在 `values.yaml` 之后再传 `values-postgres.yaml`，并按 [PostgreSQL](./postgresql#server-settings) 设置服务端。
3. 创建服务拥有的 Kafka 主题，因为 `values.yaml` 设置了 `WOW_KAFKA_DDL=validate`：命令主题的分区数必须与日志完全相同（[Kafka](./kafka#creating-topics)）。
4. 创建 `envFrom` 指定的密钥，可以从 values 旁边的示例（`secret.yaml`、`postgres-secret.yaml`）创建，也可以来自你自己的存储。

[`deploy/eshop`](https://github.com/Ahoo-Wang/Wow/tree/main/deploy/eshop) 安装整个 eShop，每个服务一个 release。

## 渲染出的对象 {#what-it-renders}

每个对象都以 `service.name`，即服务的 `spring.application.name` 命名，从不以 release 命名：

| 对象 | 名字 | 内容 |
| --- | --- | --- |
| Deployment | `<name>` | 节点 |
| ConfigMap | `<name>-config` | `config` 作为环境变量，`settings` 作为 `SPRING_APPLICATION_JSON`，`javaOpts`，关闭的时限 |
| Service | `<name>` | 公开的 HTTP 端口 |
| Service | `<name>-cluster` | 内部端口，headless |
| NetworkPolicy | `<name>-public`、`<name>-internal` | 谁可以访问每个端口 |
| PodDisruptionBudget | `<name>` | 一次自愿中断可以带走几个节点 |
| ServiceAccount | `<name>` | 不挂载令牌：节点不调用 Kubernetes API |
| HorizontalPodAutoscaler 或 ScaledObject | `<name>` | 可选：[伸缩](#scale) |
| ServiceMonitor 或 PodMonitor | `<name>` | 可选：[监控](#monitor) |

## 镜像 {#the-image}

镜像在 JDK 25 基础镜像（按摘要钉住）上以数字非 root 用户运行服务的 Gradle 发行包（`./gradlew :eshop-ordering-server:installDist`），并暴露 HTTP 端口和内部端口。chart 在 Pod 上设置这两个端口（`SERVER_PORT`、`WOW_INTERNAL_PORT`，取自 `ports`），因此 Service、探针和镜像自己的配置不会不一致。

## JDK {#the-jdk}

Wow 为 JDK 21 构建，也能在 JDK 21 上运行，但生产环境请用 JDK 25。各后端在虚拟线程上执行阻塞的驱动调用（JDBC、MongoDB 和 Kafka 客户端）。JDK 24 之前，在 `synchronized` 代码中阻塞的虚拟线程（驱动和 Java agent 常常如此）会钉住它所在的载体线程。这样的线程一多，别的线程就没有载体可用：节点的每个存储和主题调用都不再返回，它的租约失效，其他节点接管它的分区。节点的存活检查随后在 `wow.health.liveness.stall-timeout` 之后失败，Kubernetes 重启它（[健康](./observability#health)）。JDK 24 及以后，这些线程可以卸载。运行在更早 JDK 上的节点启动时会就此警告一次。

## 内存 {#memory}

chart 默认的 `javaOpts` 是 `-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError`：堆是容器内存上限的四分之三，`OutOfMemoryError` 会结束进程，于是 Kubernetes 重新启动这个 Pod，其间其他节点接手它的分区。Wow 构建的镜像也设置了 `-XX:+ExitOnOutOfMemoryError`。你自己的 `javaOpts` 会替换默认值：请在其中保留 `-XX:+ExitOnOutOfMemoryError`。没有它时，节点自身工作遇到 `OutOfMemoryError` 仍会退出它的租约组，其存活检查一直失败直到进程结束（[致命错误](./observability#fatal-errors)），但进程中其他地方的错误（例如某个请求中的）就交给 JVM 了。

按节点高峰时持有的东西确定堆的大小，而不是按平均值：状态缓存，除非 `wow.state-cache.capacity` 限定了它，否则是堆的十分之一（[Wow 推导的值](./configuration#what-wow-derives)）；在途的命令，最多 `wow.executor.max-in-flight` 个，每个都带着它的聚合加载的状态，对于历史很长且近期没有快照的聚合，这意味着重放它的事件；以及每个订阅在它运行的每个分区中持有的条目，最多到它的在途上限。如果内存上限在负载下装不下这些，过载就会变成重启。

## 共用的设置 {#shared-settings}

服务的每个节点都从 ConfigMap 获得相同的配置，以环境变量形式提供，由 Spring Boot 按宽松规则绑定：即 values 中的 `config`，如上面 ordering 的那样，写成规范形式（`wow.log.partitions` 写作 `WOW_LOG_PARTITIONS`）。映射键是环境变量表达不了的（带短横线或点）的键，以树的形式写在 `settings` 下，chart 把它渲染为 `SPRING_APPLICATION_JSON`。数据库的连接来自一个 Secret，经 `envFrom` 提供。内部端口不需要密钥：网络就是它的边界。

## 节点 {#the-nodes}

<!-- sample: deploy/helm/wow-service/templates/deployment.yaml#rollout -->

```yaml
# A new node joins before an old one leaves: the partitions move to it through the leases, and none is left without
# an owner while the rollout runs.
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxSurge: {{ .Values.rollout.maxSurge }}
    maxUnavailable: 0
```

两个副本就是两个节点，它们通过数据库中的租约分担日志的分区。滚动更新先启动一个新 Pod，再停掉一个旧 Pod（`maxUnavailable: 0`），因此分区始终有属主。每个节点都需要一个身份和一个对端可达的地址，chart 从 Pod 给出：

<!-- sample: deploy/helm/wow-service/templates/deployment.yaml#node -->

```yaml
env:
  - name: POD_NAME
    valueFrom:
      fieldRef:
        fieldPath: metadata.name
  - name: POD_IP
    valueFrom:
      fieldRef:
        fieldPath: status.podIP
  # The ports the Services and the probes name: the chart's, whatever the image's configuration says.
  - name: SERVER_PORT
    value: {{ .Values.ports.http | quote }}
  - name: WOW_INTERNAL_PORT
    value: {{ .Values.ports.internal | quote }}
  # The node id is the pod's name: a replaced pod is a new member.
  - name: WOW_NODE_ID
    value: $(POD_NAME)
  # The internal port binds the pod's private address only, and the node advertises that address: a
  # Deployment's pods have no DNS name of their own.
  - name: WOW_INTERNAL_HOST
    value: $(POD_IP)
  # The namespace a peer's SRV name is in, unless wow.peer.dns.namespaces places the peer elsewhere.
  - name: WOW_PEER_DNS_NAMESPACE
    valueFrom:
      fieldRef:
        fieldPath: metadata.namespace
  {{- with .Values.env }}
  {{- toYaml . | nindent 12 }}
  {{- end }}
```

- **节点 ID** 是 Pod 的名字。被替换的 Pod 作为新成员加入；旧 Pod 的租约到期失效。
- **内部端口**绑定 Pod 自己的地址，节点对外公布这个地址，即 `<pod IP>:<内部端口>`：这是宿主的默认值，所以 chart 不设置 `wow.node.address`。Deployment 的 Pod 没有属于自己的 DNS 名字：headless Service 只为设置了 `spec.hostname` 的 Pod 发布 `<pod>.<service>` 记录，而 Deployment 无法为每个 Pod 设置它。只有在 Pod 自己的地址不是对端所能访问的地址时才设置 `wow.node.address`，例如 Pod 有固定名字（`serviceName` 和 `hostname`）的 StatefulSet，或有多块网卡的主机。
- **命名空间**来自 Pod（`WOW_PEER_DNS_NAMESPACE`）：节点在这个命名空间里查找它的对端。

## 两个 Service {#two-services}

<!-- sample: deploy/helm/wow-service/templates/service.yaml#cluster -->

```yaml
# The internal port's headless Service, named after the service with -cluster: its SRV records,
# _internal._tcp.<service>-cluster.<namespace>.svc, name every node's address, the address each node advertises (its pod
# IP), and the other services find the nodes through them. It publishes the nodes that are not ready, so a node that is
# starting or draining is still found. Never expose it outside the cluster: it takes no token, each node and service
# names itself on each request (Wow-Service), and the internal NetworkPolicy admits the platform's own pods alone.
apiVersion: v1
kind: Service
metadata:
  name: {{ $name }}-cluster
  labels:
    {{- include "wow-service.labels" . | nindent 4 }}
spec:
  clusterIP: None
  publishNotReadyAddresses: true
  selector:
    {{- include "wow-service.selectorLabels" . | nindent 4 }}
  ports:
    - name: internal
      port: {{ .Values.ports.internal }}
      targetPort: internal
      protocol: TCP
```

两者都以服务命名。`eshop-ordering-service` 把公开端口提供给网关，客户端经网关访问它。`eshop-ordering-service-cluster` 是 headless 的：它的 SRV 记录 `_internal._tcp.eshop-ordering-service-cluster.<namespace>.svc` 列出每个节点的地址，其他服务只凭服务名就能通过它们找到这些节点（[调用其他服务](../guide/calling-services)）。它也发布尚未就绪的节点，所以正在启动或排空的节点仍能被找到。

### 网络策略 {#network-policies}

绝不要把内部端口暴露到集群之外。它不用令牌：每个请求都声明自己的服务（`Wow-Service`），网络策略就是边界（[保护部署](./security#close-the-internal-port)）：

<!-- sample: deploy/helm/wow-service/values.yaml#network -->

```yaml
networkPolicy:
  enabled: true
  public:
    # Who reaches the public port: the gateway's pods by default, in any namespace.
    from:
      - namespaceSelector: {}
        podSelector:
          matchLabels:
            app.kubernetes.io/component: gateway
  internal:
    # More pods that reach the internal port, besides the service's own, its platform's and its peers'.
    from: []
  metrics:
    # Who scrapes the metrics port, when it is one of its own: Prometheus' pods, as kube-prometheus-stack labels them.
    from:
      - namespaceSelector: {}
        podSelector:
          matchLabels:
            app.kubernetes.io/name: prometheus
```

- `<name>-public` 只放行网关的 Pod（任意命名空间中带 `app.kubernetes.io/component: gateway` 标签的）访问 HTTP 端口。给你的网关 Pod 打上这个标签，或者设置 `networkPolicy.public.from` 去匹配它们；空列表会放行所有 Pod，因此被拒绝。
- `<name>-internal` 放行以下 Pod 访问内部端口：本服务的节点、它所属平台的 Pod（`partOf`，它给每个对象打上 `app.kubernetes.io/part-of` 标签）、它调用的对端的 Pod（对端会回送等待信号），以及 `networkPolicy.internal.from`。

网络插件不执行 NetworkPolicy 时，这些策略不起作用；这时要用别的办法，让内部端口只在平台自己的网络上可达。

### 其他命名空间的对端 {#peers-in-other-namespaces}

在其他命名空间的对端，在你的 values 文件中给 `service.peers` 写上它的 `namespace`：

```yaml
service:
  peers:
    - context: eshop-payment
      service: eshop-payment-service
      namespace: payments
```

chart 随后给节点设置 `wow.peer.dns.namespaces.eshop-payment-service: payments`，节点便到 `_internal._tcp.eshop-payment-service-cluster.payments.svc` 查找对端，内部策略也放行来自那个命名空间的对端 Pod。对端自己的内部策略也须放行本服务的 Pod：在它的 `networkPolicy.internal.from` 中加一个带 `namespaceSelector` 的条目。

## 探针 {#probes}

宿主把节点的健康状况交给 Spring Boot Actuator 的就绪与存活分组，在 HTTP 端口上：

<!-- sample: deploy/helm/wow-service/templates/deployment.yaml#probes -->

```yaml
startupProbe:
  httpGet:
    path: /actuator/health/readiness
    port: http
  periodSeconds: {{ .Values.probes.startup.periodSeconds }}
  failureThreshold: {{ .Values.probes.startup.failureThreshold }}
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: http
  periodSeconds: {{ .Values.probes.readiness.periodSeconds }}
  failureThreshold: {{ .Values.probes.readiness.failureThreshold }}
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: http
  periodSeconds: {{ .Values.probes.liveness.periodSeconds }}
  failureThreshold: {{ .Values.probes.liveness.failureThreshold }}
```

就绪检查在节点无法提供服务时失败：节点运行之前；它的日志、写入所需的存储（某个类型的主快照存储）或租约存储宕机时；交接宽限期过后它仍不拥有自己应得的分区时；或者它读取的某个主题仍在加入时。存活检查只在节点卡住或遇到致命错误时失败（[致命错误](./observability#fatal-errors)），从不因存储宕机而失败，排空期间也不会失败，所以一次宕机不会让所有 Pod 同时重启。启动探针给节点三分钟启动（`probes.startup`）。

## 优雅关闭 {#graceful-shutdown}

Kubernetes 停止一个 Pod 时，节点先完成仍未结束的等待，然后在进行中的命令完成后交出它的分区，保存检查点，最后关闭内部端口。宽限期必须覆盖这一切，所以 chart 用它交给节点的同一组 value 推导宽限期：

<!-- sample: deploy/helm/wow-service/values.yaml#shutdown -->

```yaml
# The node's shutdown: the grace period is their sum, and the chart sets wow.wait.max-timeout and
# wow.cluster.drain-timeout from them.
shutdown:
  # Time for the endpoints to drop the pod before Spring Boot stops taking requests.
  preStopSeconds: 5
  # The longest wait the public server finishes before it stops (wow.wait.max-timeout).
  waitMaxTimeoutSeconds: 60
  # How long a hand-off waits for its partition's commands in flight (wow.cluster.drain-timeout).
  drainTimeoutSeconds: 10
  # Room for the checkpoints to be saved and the JVM to exit.
  marginSeconds: 15
```

宽限期是它们的和，默认 90 秒，chart 也用它们设置 `wow.wait.max-timeout` 和 `wow.cluster.drain-timeout`；在 `config` 中设置这两个键会被拒绝。`preStop` 的短暂停顿让 endpoints 有时间摘掉 Pod，然后 Spring Boot 才停止接收请求。

## 伸缩 {#scale}

增加副本，分区就分散到更多节点；减少副本，分区就重新集中。成员加入或离开时，租约在节点之间迁移分区，每次大约迁移 N 分之一。迁移是有代价的：

- **交接。** 离开的属主在 `wow.cluster.drain-timeout` 内完成该分区进行中的命令，并保存其订阅的检查点。在此期间，它那些聚合的命令要么等待，要么在收到命令的节点上执行（[部署模型](./topology#services-and-nodes)）。
- **订阅**在新属主上从检查点继续，所以处理函数可能再次见到它在上一个检查点之后处理过的内容。
- **新属主的状态缓存是冷的**：每个聚合在那里的第一条命令要加载它的快照和之后的日志。

所以要依据持续的信号伸缩，并且要慢。超出分区数（默认 64）的节点不拥有任何分区，这也是分区数要预先选定的原因之一：副本数上限要低于它。值得依据的信号：

| 信号 | 含义 |
| --- | --- |
| CPU | 命令与查询的工作量，适用于处理函数做计算的服务 |
| `wow.subscription.backlog` | 订阅落后于日志：投影、Saga、Sink |
| `wow.command.duration` | 命令的延迟，前提是存储能及时应答 |

`autoscaling.hpa` 创建一个按 CPU（以及你自己的指标）伸缩的 HorizontalPodAutoscaler：

<!-- sample: deploy/helm/wow-service/values.yaml#hpa -->

```yaml
# A HorizontalPodAutoscaler on CPU, and metrics of your own. Exclusive with keda.
hpa:
  enabled: false
  minReplicas: 2
  maxReplicas: 8
  cpuUtilization: 70
  metrics: []
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 60
      policies:
        - type: Pods
          value: 1
          periodSeconds: 60
    scaleDown:
      stabilizationWindowSeconds: 600
      policies:
        - type: Pods
          value: 1
          periodSeconds: 300
```

它的行为是每分钟至多增加一个节点，安静十分钟后每五分钟至多减少一个，免得一阵突发让分区来回迁移。装了 [KEDA](https://keda.sh) 时，`autoscaling.keda` 改为经 Prometheus 按积压伸缩；两者互斥：

<!-- sample: deploy/helm/wow-service/values.yaml#keda -->

```yaml
# A KEDA ScaledObject on the subscriptions' backlog, through Prometheus; needs KEDA installed. Exclusive with hpa.
keda:
  enabled: false
  minReplicas: 2
  maxReplicas: 8
  pollingInterval: 30
  cooldownPeriod: 300
  prometheus:
    serverAddress: ""
    # sum(wow_subscription_backlog{namespace="<namespace>", pod=~"<name>-.*"}) when empty.
    query: ""
    # The backlog per node above which a node is added.
    threshold: 1000
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 60
      policies:
        - type: Pods
          value: 1
          periodSeconds: 60
    scaleDown:
      stabilizationWindowSeconds: 600
      policies:
        - type: Pods
          value: 1
          periodSeconds: 300
```

chart 两者都不要求：默认都关闭，此时副本数就是 `replicas`。

## 监控 {#monitor}

节点的 span 与指标经 OpenTelemetry Java agent 输出（[可观测性](./observability)），由它经 OTLP 推送（eShop 的 values 就是这样设置的），或者在一个 Prometheus 端点上提供指标（`OTEL_METRICS_EXPORTER=prometheus`，端口 9464）。对 Prometheus Operator，chart 在该端点上加一个 ServiceMonitor 或 PodMonitor：

<!-- sample: deploy/helm/wow-service/values.yaml#metrics -->

```yaml
# Where Prometheus scrapes a node: the OpenTelemetry Java agent's Prometheus exporter (OTEL_METRICS_EXPORTER=prometheus,
# port 9464, /metrics), or Actuator's endpoint on the HTTP port (port: the HTTP port, path: /actuator/prometheus).
metrics:
  port: 9464
  path: /metrics
  serviceMonitor:
    enabled: false
    interval: 30s
    labels: {}
  podMonitor:
    enabled: false
    interval: 30s
    labels: {}
```

独立的指标端口会在 Pod 上打开，并由 `<name>-public` 按 `networkPolicy.metrics.from`（默认是 Prometheus 的 Pod）放行。给监控对象加上你的 Prometheus 所选择的标签（`labels`）。要对什么告警，见[可观测性](./observability#what-to-alert-on)。

## 中断预算 {#disruption-budgets}

自愿中断（排空一个节点、升级集群）一次带走一个节点：离开的节点交出它的分区，其他节点经租约接管之后，下一个才离开。chart 的 PodDisruptionBudget 就是这样规定的，并把节点分散到各可用区和主机：

<!-- sample: deploy/helm/wow-service/values.yaml#disruption -->

```yaml
podDisruptionBudget:
  enabled: true
  # Or minAvailable, which wins when given.
  maxUnavailable: 1
  # AlwaysAllow lets a drain evict a node that is not ready, which a budget would otherwise hold.
  unhealthyPodEvictionPolicy: AlwaysAllow

topologySpread:
  enabled: true
  maxSkew: 1
  whenUnsatisfiable: ScheduleAnyway
```

`AlwaysAllow` 让排空可以驱逐未就绪的节点，否则中断预算会一直挡住它。优先用 `maxUnavailable` 而不是 `minAvailable`：两个副本时，`minAvailable: 2` 会挡住每一次排空。

## 升级 {#upgrade}

chart 的版本就是 Wow 的版本，它的 `appVersion` 是镜像的默认 tag。把服务依赖的 Wow 和 chart 一起升级，然后：

```bash
helm upgrade eshop-ordering oci://ghcr.io/ahoo-wang/wow-service --version <new-wow-version> \
  -f examples/eshop/ordering/ordering-server/build/wow/helm/values.yaml \
  -f deploy/eshop-ordering/values.yaml --wait
```

滚动更新一次替换一个节点，新节点就绪之后旧节点才离开，所以有一段时间两个版本并存：[升级](./upgrades)说明哪些可以并存，以及哪些设置永远不能改，日志的分区数就是其中之一。只改共用的设置也会滚动节点：Pod 带着 ConfigMap 的校验和。每次构建都重新生成构建的 values：代码里新增的对端随即被策略放行、被节点找到。

## 配合 Kustomize {#with-kustomize}

渲染 chart，再把你的补丁叠加在输出上，不必另维护一套清单：

```bash
helm template eshop-ordering oci://ghcr.io/ahoo-wang/wow-service --version <wow-version> --namespace shop \
  -f examples/eshop/ordering/ordering-server/build/wow/helm/values.yaml \
  -f deploy/eshop-ordering/values.yaml > base/eshop-ordering.yaml
```

把 `eshop-ordering.yaml` 列入 `base/kustomization.yaml` 的 `resources`，每次升级、每次构建出新的 values 时重新渲染。

## 在总 chart 中 {#in-an-umbrella-chart}

平台的 chart 为每个服务各引用一次本 chart，各用一个别名，每个服务的 values 写在它的别名下：

```yaml
dependencies:
  - name: wow-service
    version: <wow-version>
    repository: oci://ghcr.io/ahoo-wang
    alias: ordering
  - name: wow-service
    version: <wow-version>
    repository: oci://ghcr.io/ahoo-wang
    alias: basket
```

schema 接受总 chart 的 `global` values，本 chart 不读取它们。
