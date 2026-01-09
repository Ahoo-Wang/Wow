plugins {
    alias(libs.plugins.ksp)
    alias(libs.plugins.jmh)
    kotlin("kapt")
}

dependencies {
    implementation(project(":example-domain"))
    implementation(project(":wow-test"))
    implementation(project(":wow-redis"))
    implementation(project(":wow-mongo"))
    jmh(libs.jmh.core)
    jmh(libs.jmh.generator.annprocess)
    jmh(libs.jmh.generator.bytecode)
    kapt(libs.jmh.generator.annprocess)
}
tasks.named<Jar>("jmhJar") {
    isZip64 = true
}
jmh {
    zip64.set(true)
    includes.set(listOf("CommandDispatcherBenchmark.sendAndWaitForProcessed"))
    warmup.set("2s")
    warmupIterations.set(1)
    iterations.set(2)
    timeOnIteration.set("10s")
    resultFormat.set("json")
    threads.set(12)
    fork.set(2)
    jvmArgs.set(
        listOf(
            "-Xmx4g",
            "-Xms4g",
            "-XX:+UseG1GC",
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:+DebugNonSafepoints",
            "-XX:+AlwaysPreTouch",
        )
    )
    val asyncProfilerLib = file("/opt/async-profiler/lib/libasyncProfiler.dylib")
    val hasAsyncProfiler = asyncProfilerLib.exists()
    profilers.set(buildList {
        add("gc")
        if (hasAsyncProfiler) {
            add("async:output=flamegraph;dir=build/profiling;event=cpu;libPath=${asyncProfilerLib.absolutePath}")
            println("✅ Using async-profiler:  ${asyncProfilerLib.absolutePath}")
        } else {
            add("stack:lines=10;top=20")
            println("⚠️  async-profiler not found, using stack profiler instead")
            println("   Install guide: https://github.com/async-profiler/async-profiler")
        }
    })
}