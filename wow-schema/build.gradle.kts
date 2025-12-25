description = "Wow Schema"

dependencies {
    api(project(":wow-api"))
    api(project(":wow-core"))
    implementation(kotlin("reflect"))
    api("io.swagger.core.v3:swagger-core-jakarta")
    api("com.github.victools:jsonschema-generator")
    api("com.github.victools:jsonschema-module-jackson")
    api("com.github.victools:jsonschema-module-jakarta-validation")
    api("com.github.victools:jsonschema-module-swagger-2")
    implementation(project(":wow-models"))
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-joda-money")
    testImplementation("org.springframework:spring-web")
    testImplementation(libs.json.schema.validator)
    testImplementation("io.swagger.core.v3:swagger-core-jakarta")
    testImplementation(project(":wow-tck"))
    testImplementation(project(":example-api"))
    testImplementation(project(":example-domain"))
}
