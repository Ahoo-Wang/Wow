plugins {
    `java-test-fixtures`
}

description = "Wow Query: the query engine over the snapshot, event and dataset models, and its backend SPI"

// The coroutine port of 9.x wow-query (decision D126).
dependencies {
    api(project(":query-api"))
    api(project(":spi"))
    api(project(":manifest"))
    implementation("io.github.oshai:kotlin-logging-jvm")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    // QueryBackendSpec, the backend contract every query backend passes (the in-memory reference backend is main code,
    // decision D209).
    testFixturesApi("org.junit.jupiter:junit-jupiter-api")
    testFixturesApi("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testFixturesImplementation("me.ahoo.test:fluent-assert-core")
}
