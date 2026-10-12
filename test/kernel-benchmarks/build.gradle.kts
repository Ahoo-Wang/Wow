plugins {
    alias(libs.plugins.jmh)
}

description = "V10 spike H3: the coroutine write kernel against the 9.3 Reactor kernel, on the same JVM"

dependencies {
    // H3 runs both kernels on one JVM, so it reaches both wow-api modules (the one exemption, decision D90). The 9.x
    // side comes first: the JMH jar keeps the first copy of a class, so the annotations both declare are 9.x's, which
    // the 9.x kernel reads with their 9.x-only attributes.
    jmhImplementation(project(":wow-benchmarks"))
    jmhImplementation(project(":example-domain"))
    jmhImplementation(project(":wow-core"))
    jmhImplementation(project(":kernel"))
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

// Of a class both wow-api modules declare, the JMH jar keeps the first copy: 9.x's, declared first above. The JMH
// plugin sets the jar's strategy when it creates the task (INCLUDE, which keeps both copies), so it is set here.
tasks.named<Jar>("jmhJar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
