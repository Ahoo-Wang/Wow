// The test fixtures: the in-process cluster channel the tests of Wow's modules run nodes over (LoopbackClusterChannel).
plugins {
    `java-test-fixtures`
}

description = "Wow Runtime: gateway, router, cluster channel, executor, subscriptions, waits and lifecycle"

dependencies {
    // The guard of the hand-written equals (finding R5.B-13).
    testImplementation(project(":tck"))
    // TenantScopePathsTest: one command decides its tenant and scope alike through the gateway and the test DSL.
    testImplementation(project(":test-dsl"))
    // ContractWaitHopsTest: every hop the contract generator names for a context is one its own node and a peer accept.
    testImplementation(project(":manifest-tools"))
    api(project(":kernel"))
    // The Manifest the node is built from: its contexts, routes and contracts (decision D231 ③: the kernel does not
    // bring it).
    api(project(":manifest"))
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("io.github.oshai:kotlin-logging-jvm")
}

dependencies {
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
}

// ChartGracePeriodTest reads the wow-service chart's shutdown values and the grace periods its unit tests assert.
tasks.named<Test>("test") {
    inputs.files(
        rootProject.fileTree("deploy/helm/wow-service") { include("values.yaml", "tests/deployment_test.yaml") },
    ).withPropertyName("chartGracePeriods").withPathSensitivity(PathSensitivity.RELATIVE)
}
