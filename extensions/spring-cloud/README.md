# wow-spring-cloud

Spring Cloud service discovery for Wow, for deployments off Kubernetes (virtual machines with CoSky, Consul, Eureka, Nacos or Zookeeper). Experimental, as the peer discovery it implements.

- Each node of a service registers its internal address in its instance's metadata: `wow-service`, `wow-internal-host` and `wow-internal-port`.
- `wow.peer.discovery: spring-cloud` finds a peer's nodes through the application's `ReactiveDiscoveryClient`, with no `wow.peers.<context>.url`.
- `LoadBalancedEndpoints` gives a `WowClient` in an application that is no Wow service its endpoints as `lb://<service>`, through Spring Cloud LoadBalancer.

```kotlin
dependencies {
    implementation(platform("org.springframework.cloud:spring-cloud-dependencies:2026.0.0"))
    implementation("me.ahoo.wow:wow-spring-cloud")
    // The registry's starter: CoSky here, the registry its tests run on.
    implementation("me.ahoo.cosky:cosky-spring-boot-starter:8.0.0-beta.11")
}
```

[Spring Cloud Discovery](https://wow.ahoo.me/operate/spring-cloud) describes the configuration, what a node registers and the firewall the internal port needs; [Call Wow from Other Services](https://wow.ahoo.me/guide/calling-from-other-services) says which caller goes where.
