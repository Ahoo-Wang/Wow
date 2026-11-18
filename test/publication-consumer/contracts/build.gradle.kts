plugins {
    java
}

val wowVersion: String = providers.gradleProperty("wow.consumer.version").get()

dependencies {
    providers.gradleProperty("wow.consumer.apis").get().split(',').forEach { api ->
        runtimeOnly("me.ahoo.wow:$api:$wowVersion") {
            capabilities { requireFeature("contract") }
            isTransitive = false
        }
    }
}

// The files the feature resolves to, by name: each module's jar of classifier `contract`.
tasks.register("contracts") {
    val files = configurations.runtimeClasspath.map { classpath -> classpath.files.map { it.name }.sorted() }
    doLast { println("CONTRACTS=" + files.get().joinToString(",")) }
}
