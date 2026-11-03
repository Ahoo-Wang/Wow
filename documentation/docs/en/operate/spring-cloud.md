---
title: Spring Cloud Discovery
description: Off Kubernetes, find a service's peers through a Spring Cloud service registry (Consul, Eureka, Nacos, Zookeeper) with wow-spring-cloud, register each node's internal address there, and keep the internal port behind a firewall.
outline: deep
---

# Spring Cloud Discovery

On Kubernetes a service finds its peers' nodes by DNS, through the headless Service of their internal port ([Configure a peer over HTTP](../guide/calling-services#configure-a-peer-over-http)). On virtual machines with a service registry, `wow-spring-cloud` finds them there instead:

- each node registers its internal address in its instance's metadata;
- a service that calls a peer reads the peer's instances with `wow.peer.discovery: spring-cloud`, and needs no `wow.peers.<context>.url`.

One adapter covers every registry with a Spring Cloud starter: Consul, Eureka, Nacos and Zookeeper. You choose the registry by adding its starter. The nodes of one service still find each other through the lease table in their database: only the calls between services go through the registry.

The module is experimental, as the peer discovery it implements is: its shape may still change in 10.x.

## Add the module

Add the module, the starter of your registry, and the Spring Cloud BOM of the train that matches Spring Boot 4.0 and 4.1, `2025.1.x`:

<!-- sample: fragment -->

```kotlin
dependencies {
    implementation(platform("org.springframework.cloud:spring-cloud-dependencies:2025.1.4"))
    implementation("me.ahoo.wow:wow-spring-cloud")
    implementation("org.springframework.cloud:spring-cloud-starter-consul-discovery")
}
```

Each node of a service with the module registers its internal address as soon as Spring Cloud registers the instance. A node that still finds its peers another way logs, once at startup, how to switch.

## Find the peers

A service that calls a peer sets the discovery, and lists the peer without a `url`:

<!-- sample: fragment -->

```yaml
spring:
  application:
    name: eshop-basket-service
  cloud:
    consul:
      host: consul.internal
      discovery:
        prefer-ip-address: true
wow:
  peer:
    discovery: spring-cloud
  peers:
    eshop-ordering:
      mode: service
```

The client looks the peer up by its service name, the one its contract on the class path names. Every registry registers an instance under its `spring.application.name` by default, so keep each service's registry name at that default.

| Step | What happens |
| --- | --- |
| Read | The peer's instances are read through the application's `ReactiveDiscoveryClient`, Spring Cloud's own by default, on the first request to the peer. They are read again every 30 seconds, and at once when no node takes a connection (at most once per 30 seconds). |
| Map | Each instance is the base URL `http://<wow-internal-host>:<wow-internal-port>` its metadata names. An instance without `wow-internal-host` gives its own host. |
| Pass over | An instance whose metadata names no internal port, or another service or none (`wow-service`), is passed over, with one warning per service. |
| Keep | A read that fails, takes longer than 5 seconds or finds no instance keeps the instances read last. |
| Call | The requests go to the nodes in turn. A node that does not take the connection, or that answers it is shutting down or on standby, is passed over for the next one in the same attempt, so a registry that lags a rollout delays no call. |

A peer's `url` still wins over the registry.

The discovery has no `wow.peer.spring-cloud.*` setting. It needs Spring Cloud's reactive discovery client, which the starters give unless `spring.cloud.discovery.reactive.enabled` is `false`: without one, a node with `wow.peer.discovery: spring-cloud` refuses to start, naming that key.

## What a node registers

| Metadata key | Value |
| --- | --- |
| `wow-service` | The node's service: `wow.service`, else `spring.application.name` |
| `wow-internal-host` | The host of the node's internal address |
| `wow-internal-port` | The port of the node's internal address |

The internal address is the one the node gives the other nodes of its service in its lease membership: `wow.node.address`, else the internal server's bind host and port. The registry therefore names the address the node's own members reach it at, not one Spring Cloud detects on its own. On a machine with several interfaces, set `wow.node.address` (or `wow.internal.host`) to the private address the other machines reach.

Spring Cloud announces each registration just before it sends it, once the public server is up, so after the internal port is bound, even on a port the system chose. Consul, Nacos and Zookeeper send the registration's metadata, so the node writes the keys there. Eureka copies its metadata earlier, so the node also gives the keys to Eureka's `ApplicationInfoManager`. A registration that takes no metadata fails the node's start.

An instance that a Wow node does not register itself names the three keys in its registry's own metadata settings: `spring.cloud.consul.discovery.metadata.*`, `eureka.instance.metadata-map.*`, `spring.cloud.nacos.discovery.metadata.*` or `spring.cloud.zookeeper.discovery.metadata.*`. The keys have dashes because Consul refuses a dot in a metadata key.

The registry's health checks reach the public port, as for any Spring Boot application. Consul returns failing instances too unless `spring.cloud.consul.discovery.query-passing` is `true`. The client passes over a node that does not answer anyway, but reading the passing instances alone avoids trying it at all.

## Keep the internal port behind a firewall

The internal port takes no token: each caller names its service, and the port takes it at its word ([Close the internal port](./security#close-the-internal-port)). On Kubernetes a NetworkPolicy is the boundary. Off Kubernetes, the network is: the firewall of each machine and the security groups of your cloud. Registering the internal port in a shared registry makes its address known to anyone who can read the registry, so the network must refuse everyone else:

1. **Allow the internal port from the platform's machines alone.** On each node, with the internal port at 9093 and the platform's machines in `10.0.1.0/24`:

   ```bash
   iptables -A INPUT -p tcp --dport 9093 -s 10.0.1.0/24 -j ACCEPT
   iptables -A INPUT -p tcp --dport 9093 -j DROP
   ```

   In a cloud, allow the port in the node's security group from the platform's own security group alone, and from no address range wider than it.
2. **Bind it to the private interface** with `wow.internal.host`, never to every interface of a machine with a public address.
3. **Restrict who reads the registry**, with the registry's own authentication (Consul's ACLs, Nacos's authentication), so its list of internal addresses stays inside the platform.
4. **Keep the public port behind the gateway**, as on Kubernetes ([Put the gateway in front](./security#put-the-gateway-in-front)).

## Resolve `lb://` in an application that is no Wow service {#load-balancer}

An application that calls a Wow service with `WowClient`, and is not a Wow service itself, can find the service's instances through Spring Cloud LoadBalancer. Add `spring-cloud-starter-loadbalancer`, and give the client `LoadBalancedEndpoints`, with each context's service as `lb://<service>`:

<!-- sample: fragment -->

```kotlin
import me.ahoo.wow.client.WowClient
import me.ahoo.wow.spring.cloud.LoadBalancedEndpoints
import java.net.URI

val client = WowClient.builder()
    .endpoints(LoadBalancedEndpoints.of(loadBalancers, mapOf("eshop-catalog" to URI.create("lb://eshop-catalog-service"))))
    .service("storefront-app")
    .build()
```

`loadBalancers` is the application's `ReactiveLoadBalancer.Factory<ServiceInstance>` bean. The load balancer chooses an instance for every attempt, so a retry may reach another one:

- In service mode the client calls the internal address the chosen instance's metadata names.
- In user mode (`user(credential)`) it calls the instance's own URI, its public port.

Only the platform's own services call in service mode: [Call Wow from Other Services](../guide/calling-from-other-services) says which caller goes where.

## The rest of Spring Cloud

| Part | With Wow |
| --- | --- |
| Spring Cloud Gateway | The gateway in front of the public ports; CoSec's gateway is built on it ([Put the gateway in front](./security#put-the-gateway-in-front)) |
| Spring Cloud Config, Nacos configuration | The `wow.*` keys may come from there; a key the node does not know still refuses the start ([Mistyped settings](./security#mistyped-settings)) |
| Tracing | As in any Spring Boot application: `wow-observability`'s spans go through the application's OpenTelemetry ([Metrics and traces](./observability#metrics-and-traces)) |
| Spring Cloud Stream | Not adapted: Wow's topics go through its own transports ([Kafka](./kafka)) |
| LoadBalancer or OpenFeign between Wow services | Not used for commands: a Wow service routes each command to the node that owns its aggregate, and calls its peers with the `CommandClient` ([Call Other Services](../guide/calling-services)) |
