plugins {
    alias(libs.plugins.jmh)
}

description = "V10 spike H3: the coroutine write kernel against the 9.3 Reactor kernel, on the same JVM"

dependencies {
    jmhImplementation(project(":wow-kernel"))
    jmhImplementation(project(":wow-benchmarks"))
    jmhImplementation(project(":example-domain"))
    jmhImplementation(project(":wow-core"))
    jmh(libs.jmh.core)
    jmh(libs.jmh.generator.annprocess)
    jmh(libs.jmh.generator.bytecode)
}

jmh {
    fork.set(1)
    warmupIterations.set(3)
    iterations.set(5)
    warmup.set("2s")
    timeOnIteration.set("2s")
    profilers.add("gc")
    resultFormat.set("JSON")
}
