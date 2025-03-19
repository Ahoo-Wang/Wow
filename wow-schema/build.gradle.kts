description = "Wow Model Context Protocol"

dependencies {
    api(project(":wow-api"))
    api(project(":wow-core"))
    implementation(kotlin("reflect"))
    api("com.github.victools:jsonschema-generator")
    api("com.github.victools:jsonschema-module-jackson")
    api("com.github.victools:jsonschema-module-jakarta-validation")
    api("com.github.victools:jsonschema-module-swagger-2")
    testImplementation("io.swagger.core.v3:swagger-core-jakarta")
    testImplementation(project(":wow-tck"))
    testImplementation(project(":example-api"))
}
