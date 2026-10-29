plugins {
    kotlin("jvm")
    id("me.ahoo.wow")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":catalog-api"))
}
