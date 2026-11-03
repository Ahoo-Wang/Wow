description = "Wow Spring Cloud: a service's peers found through a Spring Cloud service registry, its nodes registered " +
    "there with their internal ports, and a WowClient's endpoints through Spring Cloud LoadBalancer (D396)"

dependencies {
    // Spring Cloud's versions for this module alone (D396): the BOM joins its own build platform, so no other module
    // resolves with it, and its POM names the versions it resolved, as every POM does (versionMapping).
    "wowBuildPlatform"(platform(libs.spring.cloud.dependencies))
    // The port it implements (PeerDiscovery) and the client's endpoints (EndpointResolver), for a non-Wow application.
    api(project(":spi"))
    api(project(":client"))
    // DiscoveryClient, ServiceInstance, the registration events and LoadBalancer's interfaces; the application's
    // registry starter (Consul, Eureka, Nacos, Zookeeper) and LoadBalancer bring the implementations.
    api("org.springframework.cloud:spring-cloud-commons")
    // The node whose address a registration carries: a Wow service's, so a non-Wow application that only resolves
    // `lb://` endpoints pulls in neither the runtime nor the host.
    compileOnly(project(":runtime"))
    // Eureka copies an instance's metadata when its bean is built, so a registration there is given it through
    // Eureka's own ApplicationInfoManager; Zookeeper builds its instance on first use, so it is built before it is
    // given it. Only when the application registers with either.
    compileOnly("org.springframework.cloud:spring-cloud-netflix-eureka-client")
    compileOnly("org.springframework.cloud:spring-cloud-zookeeper-discovery")
    compileOnly("org.apache.curator:curator-x-discovery")
    // The configuration the host imports (AutoConfiguration.imports) is plain Spring: only the host imports Spring
    // Boot (decision D118's module rules), as in wow-cosid.
    compileOnly("org.springframework:spring-context")
    // The reactive discovery client and LoadBalancer, awaited.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("io.github.oshai:kotlin-logging-jvm")
    testImplementation(project(":runtime"))
    testImplementation("org.springframework:spring-context")
    testImplementation("org.springframework.cloud:spring-cloud-netflix-eureka-client")
    testImplementation("org.springframework.cloud:spring-cloud-zookeeper-discovery")
    testImplementation("org.apache.curator:curator-x-discovery")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    // ApplicationContextRunner, the import selector that reads this module's auto-configuration metadata, and Spring
    // Cloud's simple discovery client as a registry.
    testImplementation("org.springframework.boot:spring-boot-autoconfigure")
    testImplementation("org.springframework.boot:spring-boot-test")
    // The host, which takes the module's discovery and checks the keys (D302).
    testImplementation(project(":spring-boot"))
    // The quickstart's bank service, two nodes of it registered in Consul, and a node of the eShop's catalog service,
    // a peer of another service, calling them.
    integrationTestImplementation(project(":test-spring-boot"))
    integrationTestImplementation(project(":quickstart"))
    integrationTestImplementation(project(":eshop-catalog-domain"))
    integrationTestImplementation("org.springframework.cloud:spring-cloud-starter-consul-discovery")
    integrationTestImplementation("org.springframework.cloud:spring-cloud-starter-loadbalancer")
    integrationTestImplementation("org.testcontainers:testcontainers-consul")
}
