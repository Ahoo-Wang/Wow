plugins {
    id("com.google.devtools.ksp")
}
dependencies {
    api(platform(project(":wow-dependencies")))
    implementation(project(":wow-api"))
    implementation("com.fasterxml.jackson.core:jackson-annotations")
    api("jakarta.validation:jakarta.validation-api")
    ksp(project(":wow-compiler"))
}
