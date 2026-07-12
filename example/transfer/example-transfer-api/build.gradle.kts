import com.google.devtools.ksp.gradle.KspAATask

plugins {
    alias(libs.plugins.ksp)
}
dependencies {
    api(platform(project(":wow-dependencies")))
    api("io.swagger.core.v3:swagger-core-jakarta")
    implementation(project(":wow-api"))
    implementation("com.fasterxml.jackson.core:jackson-annotations")
    api("jakarta.validation:jakarta.validation-api")
    ksp(project(":wow-compiler"))
}

// KSP 2.3.10 incremental tracking crashes while resolving nested Java annotation values.
afterEvaluate {
    tasks.withType<KspAATask>().configureEach {
        kspConfig.incremental.set(false)
    }
}
