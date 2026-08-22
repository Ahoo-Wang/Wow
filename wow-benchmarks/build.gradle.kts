plugins {
    alias(libs.plugins.ksp)
    alias(libs.plugins.jmh)
    kotlin("kapt")
    id("me.ahoo.wow.jmh-packaging")
    id("me.ahoo.wow.benchmarking")
}

dependencies {
    implementation(project(":example-domain"))
    implementation(project(":wow-test"))
    implementation(project(":wow-mock"))
    implementation(project(":wow-redis"))
    implementation(project(":wow-mongo"))
    implementation(project(":wow-elasticsearch"))
    implementation(project(":wow-webflux"))
    implementation("org.springframework:spring-test")
    jmh(libs.jmh.core)
    jmh(libs.jmh.generator.annprocess)
    jmh(libs.jmh.generator.bytecode)
    kapt(libs.jmh.generator.annprocess)
}

fun registerSnapshotAggregationBenchmark(taskName: String, backend: String) {
    tasks.register<JavaExec>(taskName) {
        group = "benchmark"
        description = "Runs the $backend Snapshot Elements aggregation benchmark and writes independent reports."
        val jmhJar = tasks.named<Jar>("jmhJar")
        dependsOn(jmhJar)
        classpath(jmhJar.flatMap { it.archiveFile })
        mainClass.set("org.openjdk.jmh.Main")
        val reportBase = layout.projectDirectory.file("results/reports/snapshot-elements-$backend")
        args(
            "me.ahoo.wow.benchmark.infrastructure.SnapshotElementsAggregationBenchmark",
            "-p",
            "backend=$backend",
            "-wi",
            "1",
            "-i",
            "3",
            "-f",
            "1",
            "-foe",
            "true",
            "-rf",
            "json",
            "-rff",
            "${reportBase.asFile}.json",
            "-o",
            "${reportBase.asFile}.txt",
        )
        doFirst { reportBase.asFile.parentFile.mkdirs() }
        doLast {
            check(reportBase.asFile.resolveSibling("${reportBase.asFile.name}.json").readText().contains("\"benchmark\"")) {
                "$taskName produced no benchmark results."
            }
        }
    }
}

registerSnapshotAggregationBenchmark("benchmarkSnapshotElementsMongo", "mongo")
registerSnapshotAggregationBenchmark("benchmarkSnapshotElementsElasticsearch", "elasticsearch")

tasks.named("check") {
    dependsOn(gradle.includedBuild("build-logic").task(":test"))
}
