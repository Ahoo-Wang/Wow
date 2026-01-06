plugins {
    alias(libs.plugins.ksp)
    alias(libs.plugins.jmh)
    kotlin("kapt")
}

dependencies {
    api(project(":example-domain"))
    ksp(project(":wow-compiler"))
    testImplementation(project(":wow-test"))
    testImplementation("io.projectreactor:reactor-test")
    jmh(project(":wow-test"))
    jmh(libs.jmh.core)
    jmh(libs.jmh.generator.annprocess)
    jmh(libs.jmh.generator.bytecode)
    kapt(libs.jmh.generator.annprocess)
}

jmh {
    includes.set(listOf(".*CommandDispatcher.*"))
    warmupIterations.set(1)
    iterations.set(2)
    resultFormat.set("json")
    threads.set(5)
    fork.set(2)
}