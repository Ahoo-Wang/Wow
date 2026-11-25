description = "Wow Query TCK: the contract specs every query backend runs (snapshots, events, datasets)"

// The query backend contract specs (review R3.3-2), out of :query's test fixtures so that a backend outside Wow runs
// them too; they build on the query engine only, as :tck builds on the SPI only.
dependencies {
    // The guard of the hand-written equals (finding R5.B-13).
    testImplementation(project(":tck"))
    api(project(":query"))
    api("org.junit.jupiter:junit-jupiter-api")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    implementation("me.ahoo.test:fluent-assert-core")
}

// BackendNumbersReferenceTest reads the query language reference and each query backend's contract subclass: declare
// them as inputs, so a changed page or list runs it again instead of reusing a cached result.
tasks.named<Test>("test") {
    val root = rootDir
    inputs.files(
        fileTree(root) {
            include("documentation/docs/*/reference/query-language.md")
            include("query/query/src/test/kotlin/**/*QueryBackendTest.kt")
            include("backends/*/src/integrationTest/kotlin/**/*QueryBackendTest.kt")
        },
    ).withPropertyName("backendNumbers").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("wow-test.repository.root", root.path)
}
