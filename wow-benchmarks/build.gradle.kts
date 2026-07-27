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

tasks.named("check") {
    dependsOn(gradle.includedBuild("build-logic").task(":test"))
}
