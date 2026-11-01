---
title: Kubernetes
description: 用普通的清单在 Kubernetes 上运行服务：镜像、两个 Service、节点的身份和地址、探针、优雅关闭、滚动更新和伸缩。
outline: deep
---

# Kubernetes

Wow 服务在 Kubernetes 上以一个 Deployment 运行：它的 Pod 就是服务的节点，它们通过服务的数据库互相发现，通过一个 headless Service 互相访问。别的都不需要：没有 Operator，没有 ZooKeeper，也没有 Kafka 消费组。本页逐一介绍 eShop 的 ordering 服务在 `deploy/eshop-ordering` 中的清单，它们可以作为你自己清单的模板。背后的思路见[部署模型](./topology)。

## 文件 {#the-files}

| 文件 | 包含 |
| --- | --- |
| `kustomization.yaml` | 这组清单：`kubectl apply -k deploy/eshop-ordering` |
| `configmap.yaml` | 每个节点共用的设置，以环境变量提供 |
| `secret.yaml` | 数据库的连接：MongoDB 连接串 |
| `deployment.yaml` | 节点 |
| `service.yaml` | HTTP 服务，以及内部端口的 headless Service |
| `networkpolicy.yaml` | 谁可以访问每个端口：网关访问 HTTP 端口，商店自己的 Pod 访问内部端口 |
| `postgres/` | 一个组件，改用 PostgreSQL |
| `Dockerfile` | 镜像，基于服务的 Gradle 发行包 |

## 镜像 {#the-image}

镜像在按 digest 固定的 JDK 25 基础镜像上，以数字 ID 的非 root 用户运行服务的 Gradle 发行包（`./gradlew :eshop-ordering-server:installDist`），并暴露 HTTP 端口和内部端口。

## JDK {#the-jdk}

Wow 为 JDK 21 构建，也能在 JDK 21 上运行，但生产环境请在 JDK 25 上运行。后端在虚拟线程上执行阻塞的驱动调用（JDBC、MongoDB 和 Kafka 客户端）。在 JDK 24 之前，虚拟线程在 `synchronized` 代码中阻塞时（驱动和 Java agent 常常如此）会钉住它所在的载体线程。同时有足够多这样的线程，其他虚拟线程就没有载体可用：节点的每个存储和 topic 调用都不再返回，它的租约失效，其他节点接管它的分区。随后节点的存活探针在 `wow.health.liveness.stall-timeout` 之后失败，由 Kubernetes 重启它（[健康](./observability#health)）。JDK 24 及以后，这些线程可以卸载。运行在更早 JDK 上的节点会在启动时警告一次。

## 共用的设置 {#shared-settings}

服务的每个节点都从一个 ConfigMap 获得相同的配置，以环境变量形式提供，由 Spring Boot 按宽松规则绑定：

<!-- sample: deploy/eshop-ordering/configmap.yaml -->

```yaml
# The settings every node shares, as environment variables (Spring Boot's relaxed binding: wow.kafka.bootstrap-servers
# is WOW_KAFKA_BOOTSTRAPSERVERS). They override the application.yaml in the image, its profiles included.
apiVersion: v1
kind: ConfigMap
metadata:
  name: eshop-ordering-service-config
  labels:
    app.kubernetes.io/name: eshop-ordering-service
data:
  # The mongo profile keeps the service's data on MongoDB, the replica set at secret.yaml's URI (deploy/mongodb), in a
  # database of its own; the kafka profile connects it to the other contexts through Kafka; the cosec profile takes the
  # callers from the CoSec gateway in front of the service. The postgres component (postgres/)
  # puts the service on PostgreSQL instead.
  SPRING_PROFILES_ACTIVE: mongo,kafka,cosec
  # The order numbers (SO-<yyMMdd>-<sequence>, the orders' ids): every node reserves ranges of one sequence from the
  # service's own database, at the same URI (CosId's mongo store; on the postgres component, its jdbc store, whose
  # tables it creates at the first start), set by the profile in the image (eshop-ordering-ids.yaml). The numbers'
  # generator is a segment and needs no machine slot, so the replica count needs no setting here, nor a StatefulSet.
  WOW_KAFKA_BOOTSTRAPSERVERS: kafka:9092
  # The topics the service owns are created by the operator; the node only validates them. The kafka
  # profile's auto is for trying the service out.
  WOW_KAFKA_DDL: validate
  # Fixed once the context's log holds entries; the command topic wow.eshop-ordering.commands has as many partitions.
  WOW_LOG_PARTITIONS: "64"
  JAVA_OPTS: -XX:MaxRAMPercentage=75.0
  # Traces and metrics: the node's spans and Micrometer meters go through the OpenTelemetry Java agent,
  # which the OpenTelemetry Operator injects (the pod's instrumentation.opentelemetry.io/inject-java annotation, with
  # an Instrumentation resource in the namespace). Without the agent they are no-ops. The agent exports to the
  # collector below over OTLP, and bridges Micrometer's global registry.
  OTEL_SERVICE_NAME: eshop-ordering-service
  OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-collector:4318
  OTEL_EXPORTER_OTLP_PROTOCOL: http/protobuf
  OTEL_INSTRUMENTATION_MICROMETER_ENABLED: "true"
```

密钥以同样的方式来自一个 Secret：数据库用户名和密码。替换掉示例值。内部端口不需要密钥：网络是它的边界。

## 节点 {#the-nodes}

<!-- sample: deploy/eshop-ordering/deployment.yaml#rollout -->

```yaml
# The nodes share the partitions through leases in MongoDB: no ZooKeeper, no consumer group.
replicas: 2
selector:
  matchLabels:
    app.kubernetes.io/name: eshop-ordering-service
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxSurge: 1
    maxUnavailable: 0
```

两个副本就是两个节点，它们通过数据库中的租约划分日志的分区。滚动更新先启动一个新 Pod，再停止一个旧 Pod（`maxUnavailable: 0`），所以分区总有属主。

每个节点都需要一个身份，以及一个同伴能访问到的地址：

<!-- sample: deploy/eshop-ordering/deployment.yaml#node -->

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
  - name: SERVER_PORT
    value: "8083"
  - name: WOW_INTERNAL_PORT
    value: "9093"
  # The node id is the pod's name: a replaced pod is a new member.
  - name: WOW_NODE_ID
    value: $(POD_NAME)
  # The internal port binds the pod's private address only, and the node advertises that address,
  # $(POD_IP):9093: a Deployment's pods have no DNS name of their own.
  - name: WOW_INTERNAL_HOST
    value: $(POD_IP)
```

- **节点 ID** 就是 Pod 的名字。替换后的 Pod 作为新成员加入；旧成员的租约会过期。
- **内部端口**绑定 Pod 自己的地址，节点公布的也是这个地址 `<Pod IP>:9093`：这是宿主的默认值，所以清单不设置 `wow.node.address`。Deployment 的 Pod 没有属于自己的 DNS 名字：headless Service 只为设置了 `spec.hostname` 的 Pod 发布 `<pod>.<service>` 记录，而 Deployment 无法给每个 Pod 各自的 hostname。只有当 Pod 自己的地址不是同伴访问它的地址时才设置 `wow.node.address`，例如 Pod 有稳定名字的 StatefulSet（`serviceName` 和 `hostname`），或者有多块网卡的主机。

## 两个 Service {#two-services}

<!-- sample: deploy/eshop-ordering/service.yaml -->

```yaml
# The public HTTP port, for clients and ingresses.
apiVersion: v1
kind: Service
metadata:
  name: eshop-ordering-service
  labels:
    app.kubernetes.io/name: eshop-ordering-service
spec:
  selector:
    app.kubernetes.io/name: eshop-ordering-service
  ports:
    - name: http
      port: 80
      targetPort: http
      protocol: TCP
---
# The internal port's headless service, named after the service (spring.application.name) with
# -cluster: its SRV records, _internal._tcp.eshop-ordering-service-cluster, name every node's address, the address each
# node advertises (its pod IP), and the other services find the nodes through them. It publishes
# the nodes that are not ready, so a node that is starting or draining is still found. Never expose it
# outside the cluster: it takes no token, each node and service names itself on each request (Wow-Service),
# and only the shop's own pods reach it (networkpolicy.yaml).
apiVersion: v1
kind: Service
metadata:
  name: eshop-ordering-service-cluster
  labels:
    app.kubernetes.io/name: eshop-ordering-service
spec:
  clusterIP: None
  publishNotReadyAddresses: true
  selector:
    app.kubernetes.io/name: eshop-ordering-service
  ports:
    - name: internal
      port: 9093
      targetPort: internal
      protocol: TCP
```

两者都以服务的名字，即它的 `spring.application.name` 命名。

- `eshop-ordering-service` 向网关提供公开端口，客户端经网关访问它。
- `eshop-ordering-service-cluster` 是 headless 的：它的 SRV 记录 `_internal._tcp.eshop-ordering-service-cluster` 列出每个节点的地址，其他服务只凭服务名就能通过它们找到这些节点（对端发现）。它也发布尚未就绪的节点，所以正在启动或排空的节点仍能被找到。

绝不要把内部端口暴露到集群之外。它不用令牌：每个请求都声明自己的服务（`Wow-Service`），网络策略就是边界。`networkpolicy.yaml` 里有两条：`eshop-ordering-service-public` 只允许网关的 Pod（任意命名空间中带 `app.kubernetes.io/component: gateway` 标签）访问 HTTP 端口，`eshop-ordering-service-internal` 只允许商店自己的 Pod（带 `app.kubernetes.io/part-of: eshop` 标签）访问内部端口（[保护部署](./security#close-the-internal-port)）。给你的网关 Pod 打上这个标签，或者修改选择器去匹配它们。不执行 NetworkPolicy 的网络插件会忽略它们；这时要用别的办法让内部端口只在平台的网络上可达。

## 探针 {#probes}

宿主在 HTTP 端口上，把节点的健康状况交给 Spring Boot Actuator 的就绪和存活分组：

<!-- sample: deploy/eshop-ordering/deployment.yaml#probes -->

```yaml
startupProbe:
  httpGet:
    path: /actuator/health/readiness
    port: http
  periodSeconds: 5
  failureThreshold: 36
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: http
  periodSeconds: 5
  failureThreshold: 2
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: http
  periodSeconds: 10
  failureThreshold: 3
```

节点无法提供服务时就绪探针失败：它运行之前、它的日志存储或租约存储不可用时、交接宽限期过后它仍没有拥有应得的任何分区时，或者它读取的某个主题仍在加入中时。存活探针只在节点卡住时失败，从不因存储故障失败，节点排空期间也不会失败，所以一次故障不会让所有 Pod 同时重启。启动探针给节点三分钟的启动时间。

## 优雅关闭 {#graceful-shutdown}

Kubernetes 停止一个 Pod 时，节点先完成仍未结束的等待，然后在在途命令完成后交出它的分区，保存检查点，最后关闭内部端口。宽限期必须覆盖这全部过程：

<!-- sample: deploy/eshop-ordering/deployment.yaml#shutdown -->

```yaml
# The host's shutdown: the public server finishes its requests, the waits among them (wow.wait.max-timeout,
# 60s); the node then hands its partitions off after their commands in flight (wow.cluster.drain-timeout, 10s)
# and saves its checkpoints. 5s of preStop, 60s and 10s, with room to spare: raise it with those settings.
terminationGracePeriodSeconds: 90
```

一个短的 `preStop` 暂停，给端点留出时间把这个 Pod 摘掉，然后 Spring Boot 才停止接收请求：

<!-- sample: deploy/eshop-ordering/deployment.yaml#prestop -->

```yaml
lifecycle:
  # Time for the endpoints to drop the pod before Spring Boot stops taking requests.
  preStop:
    exec:
      command: ["sleep", "5"]
```

提高 `wow.wait.max-timeout` 或 `wow.cluster.drain-timeout` 时，也要提高宽限期。

## 伸缩 {#scale}

增加副本，把分区分散到更多节点上；减少副本，再把它们收拢。成员加入和离开时，租约会在节点之间移动分区，被移动的分区上的订阅从它们的检查点继续。超出分区数（默认 64）的节点不拥有任何分区，这也是分区数要预先选定的原因之一。

## 应用之前 {#before-you-apply}

1. 构建镜像并推送，或者把它加载到本地集群。
2. 准备数据库；服务用到 Kafka 时，也准备 Kafka。`deploy/mongodb` 里有一个可作起点的 MongoDB 副本集；要用 PostgreSQL，就在你自己的 kustomization 里加上服务的 `postgres` 组件（`components: [deploy/eshop-ordering/postgres]`），并按 [PostgreSQL](./postgresql#server-settings) 设置服务端。
3. 创建服务拥有的 Kafka 主题，因为 ConfigMap 设置了 `WOW_KAFKA_DDL=validate`：命令主题的分区数必须与日志完全相同（[Kafka](./kafka#creating-topics)）。
4. 替换示例密钥。

然后执行 `kubectl apply -k deploy/eshop-ordering` 和 `kubectl rollout status deployment/eshop-ordering-service`。`kubectl apply -k deploy/eshop` 部署整个 eShop。
