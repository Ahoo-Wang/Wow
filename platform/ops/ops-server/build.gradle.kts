description = "Wow V10 ops: the ops service on the Spring Boot host (roadmap 7.1a)"

// The aggregation and operation surface of every service's failure ledger (decisions D14, D141), in place of the 9.x
// compensation server: `./gradlew :ops-server:run --args='--spring.profiles.active=kafka'` starts it on the memory
// backend with Kafka on localhost, `postgres,kafka` on PostgreSQL. V10 modules are not published before M8 (D88).
plugins {
    application
}

application {
    mainClass.set("me.ahoo.wow.ops.server.OpsServerKt")
}

// The Manifest compatibility gate (decision D76, roadmap 5a.4): `check` compares the service's Manifest with
// manifest/baseline.json. The plugin `me.ahoo.wow` cannot be applied inside the build that produces it, so the server
// applies the repository's script in its place, as the eShop's servers do (DX gap G6).
extra["wowService"] = "ops-service"
apply(from = rootProject.file("example/eshop/wow-manifest-compatibility.gradle.kts"))

dependencies {
    implementation(project(":ops-domain"))
    implementation(project(":spring-boot"))
    // The `kafka` profile: the services' ledger records come in on wow.ops.commands, and an operator's action goes out
    // to the owning context's command topic as the ledger's system command.
    implementation(project(":kafka"))
    // The `postgres` profile: the entries' log and snapshots on PostgreSQL, their snapshot queries served there.
    implementation(project(":postgres"))
    implementation("com.zaxxer:HikariCP")
    testImplementation("org.springframework:spring-test")
    // The same flow on PostgreSQL in a container (decision D196).
    integrationTestImplementation(sourceSets["test"].output)
    integrationTestImplementation(project(":tck-containers"))
}
