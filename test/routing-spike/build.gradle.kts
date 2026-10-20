description = "V10 spike H2: affinity routing (Partitioned) against Local and ingress hashing, on PostgreSQL"

dependencies {
    integrationTestImplementation(project(":runtime"))
    integrationTestImplementation(project(":postgres"))
    integrationTestImplementation("tools.jackson.module:jackson-module-kotlin")
    integrationTestImplementation(project(":tck-containers"))
    integrationTestImplementation("com.zaxxer:HikariCP")
}

// Spike H2 runs only on demand: ./gradlew :routing-spike:integrationTest -Dwow.spike.h2=true
// (add -Dwow.spike.h2.quick=true for a shortened matrix that only checks the harness).
tasks.named<Test>("integrationTest") {
    listOf("wow.spike.h2", "wow.spike.h2.quick").forEach {
        systemProperty(it, providers.systemProperty(it).orElse("false").get())
    }
    maxHeapSize = "4g"
}

// Spike H2, stage 2 (decision D155): three node JVMs on the Spring Boot host, forwarding over the real cluster channel
// (HTTP/2 on the internal port), on a durable PostgreSQL; an owner is killed mid-run. Outside every check and the
// integration tests: `./gradlew :routing-spike:h2Stage2 -Pwow.h2.stage2.duration=PT5M`, or
// `-Pwow.h2.stage2.mode=smoke` for a run of seconds that only checks the harness. The Gradle properties
// `wow.h2.stage2.*` (Stage2Settings) reach the test JVM as system properties; the report and the nodes' output go to
// build/h2-stage2/. The workflow v10-h2-stage2.yml runs it on a GitHub-hosted runner.
val integrationTestSourceSet = sourceSets["integrationTest"]
val stage2SourceSet = sourceSets.create("stage2") {
    // Stage 1's account model and access distributions.
    compileClasspath += integrationTestSourceSet.output + integrationTestSourceSet.compileClasspath
    runtimeClasspath += output + compileClasspath + integrationTestSourceSet.runtimeClasspath
}
configurations["stage2Implementation"].extendsFrom(configurations["integrationTestImplementation"])
configurations["stage2RuntimeOnly"].extendsFrom(configurations["integrationTestRuntimeOnly"])

dependencies {
    // The node JVMs: the host, its public and internal servers and the cluster channel.
    "stage2Implementation"(project(":spring-boot"))
}

// detekt (and so v10Check) covers stage 2, which no check compiles otherwise.
tasks.named<io.gitlab.arturbosch.detekt.Detekt>("detekt") {
    source(files("src/stage2/kotlin"))
}

tasks.register<Test>("h2Stage2") {
    description = "Runs spike H2's stage 2 (D155): three node JVMs on PostgreSQL, real forwarding, an owner killed."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = stage2SourceSet.output.classesDirs
    classpath = stage2SourceSet.runtimeClasspath
    useJUnitPlatform()
    // The nodes run on the same class path, in JVMs of their own.
    jvmArgumentProviders.add(
        CommandLineArgumentProvider { listOf("-Dwow.h2.stage2.classpath=${stage2SourceSet.runtimeClasspath.asPath}") },
    )
    val outputDir = layout.buildDirectory.dir("h2-stage2")
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dwow.h2.stage2.dir=${outputDir.get().asFile}") })
    providers.gradlePropertiesPrefixedBy("wow.h2.stage2.").get().forEach { (name, value) -> systemProperty(name, value) }
    maxHeapSize = "2g"
    // The driver logs its own progress only (see the file); the repository's DEBUG test logging would cost more than
    // what is measured.
    jvmArgs = listOf("-Dlogback.configurationFile=${projectDir}/src/stage2/resources/logback-stage2.xml")
    extensions.configure<JacocoTaskExtension> { isEnabled = false }
    // A measurement is never up to date, and never retried: a second try would hide the first one's numbers.
    outputs.upToDateWhen { false }
    doFirst {
        extensions.getByType<org.gradle.testretry.TestRetryTaskExtension>().maxRetries.set(0)
    }
    testLogging {
        showStandardStreams = true
    }
}
