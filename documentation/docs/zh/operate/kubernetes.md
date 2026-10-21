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
| `secret.yaml` | 数据库凭据、集群令牌和服务令牌 |
| `deployment.yaml` | 节点 |
| `service.yaml` | HTTP 服务，以及内部端口的 headless Service |
| `networkpolicy.yaml` | 谁可以访问内部端口 |
| `Dockerfile` | 镜像，基于服务的 Gradle 发行包 |

## 镜像 {#the-image}

镜像在 JDK 21 基础镜像上，以数字 ID 的非 root 用户运行服务的 Gradle 发行包（`./gradlew :eshop-ordering-server:installDist`），并暴露 HTTP 端口和内部端口。

## 共用的设置 {#shared-settings}

服务的每个节点都从一个 ConfigMap 获得相同的配置，以环境变量形式提供，由 Spring Boot 按宽松规则绑定：

<!-- sample: deploy/eshop-ordering/configmap.yaml -->

```yaml
# The settings every node shares, as environment variables (Spring Boot's relaxed binding: wow.kafka.bootstrap-servers
# is WOW_KAFKA_BOOTSTRAPSERVERS). They override the application.yaml in the image, its postgres and kafka profiles
# included.
apiVersion: v1
kind: ConfigMap
metadata:
  name: eshop-ordering-config
  labels:
    app.kubernetes.io/name: eshop-ordering
data:
  # The postgres profile wires the DataSource; the kafka profile the transport and the command intake.
  SPRING_PROFILES_ACTIVE: postgres,kafka
  SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/eshop_ordering
  WOW_KAFKA_BOOTSTRAPSERVERS: kafka:9092
  # The topics the service owns are created by the operator; the node only validates them (decision D168). The kafka
  # profile's auto is for trying the service out.
  WOW_KAFKA_TOPICS_DDL: validate
  # Fixed once the context's log holds entries; the command topic wow.eshop-ordering.commands has as many partitions.
  WOW_LOG_PARTITIONS: "64"
  JAVA_OPTS: -XX:MaxRAMPercentage=75.0
```

密钥以同样的方式来自 Secret：数据库密码、集群令牌 `WOW_INTERNAL_TOKEN`，以及 ordering 信任的服务的令牌。替换掉示例值。

## 节点 {#the-nodes}

<!-- sample: deploy/eshop-ordering/deployment.yaml#rollout -->

```yaml
# The nodes share the partitions through leases in PostgreSQL: no ZooKeeper, no consumer group.
replicas: 2
selector:
  matchLabels:
    app.kubernetes.io/name: eshop-ordering
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
  - name: POD_NAMESPACE
    valueFrom:
      fieldRef:
        fieldPath: metadata.namespace
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
  # D173: the internal port binds the pod's private address only, and the node advertises its headless name.
  - name: WOW_INTERNAL_HOST
    value: $(POD_IP)
  - name: WOW_NODE_ADDRESS
    value: $(POD_NAME).eshop-ordering-cluster.$(POD_NAMESPACE).svc.cluster.local:9093
```

- **节点 ID** 就是 Pod 的名字。替换后的 Pod 作为新成员加入；旧成员的租约会过期。
- **内部端口**绑定 Pod 自己的地址，节点公布它在 headless Service 下的名字 `<pod>.eshop-ordering-cluster.<namespace>.svc.cluster.local:9093`，这个名字由 Pod 的 `subdomain` 提供。

## 两个 Service {#two-services}

<!-- sample: deploy/eshop-ordering/service.yaml -->

```yaml
# The public HTTP port, for clients and ingresses.
apiVersion: v1
kind: Service
metadata:
  name: eshop-ordering
  labels:
    app.kubernetes.io/name: eshop-ordering
spec:
  selector:
    app.kubernetes.io/name: eshop-ordering
  ports:
    - name: http
      port: 80
      targetPort: http
      protocol: TCP
---
# The cluster channel and the wait signals (decisions D89, D173): a headless service that gives each node the DNS name
# it advertises, <pod>.eshop-ordering-cluster.<namespace>.svc.cluster.local. It publishes the nodes that are not
# ready, so a node that is starting or draining still gets its peers' forwarded commands and signals. Never expose it
# outside the cluster: its forwards and signals take the cluster token (WOW_INTERNAL_TOKEN, decision D226), its calls a
# service token, but it is the service's inside.
apiVersion: v1
kind: Service
metadata:
  name: eshop-ordering-cluster
  labels:
    app.kubernetes.io/name: eshop-ordering
spec:
  clusterIP: None
  publishNotReadyAddresses: true
  selector:
    app.kubernetes.io/name: eshop-ordering
  ports:
    - name: internal
      port: 9093
      targetPort: internal
      protocol: TCP
```

- `eshop-ordering` 向客户端和 Ingress 提供公开端口。
- `eshop-ordering-cluster` 是 headless 的：它给每个节点一个 DNS 名字。它也发布尚未就绪的节点，所以正在启动或排空的节点仍能收到同伴转发的命令，以及完成等待的信号。

绝不要把内部端口暴露到集群之外。网络策略只允许商店自己的 Pod 访问它，端口本身也用集群令牌或服务令牌认证每个请求（[保护部署](./security#close-the-internal-port)）。不执行 NetworkPolicy 的网络插件会忽略它。

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
2. 准备数据库；服务用到 Kafka 时，也准备 Kafka。
3. 创建服务拥有的 Kafka 主题，因为 ConfigMap 设置了 `WOW_KAFKA_TOPICS_DDL=validate`：命令主题的分区数必须与日志完全相同（[Kafka](./kafka#creating-topics)）。
4. 替换示例密钥。

然后执行 `kubectl apply -k deploy/eshop-ordering` 和 `kubectl rollout status deployment/eshop-ordering`。
