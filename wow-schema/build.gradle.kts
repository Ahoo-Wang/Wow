description = "Wow Schema"

dependencies {
    api(project(":wow-api"))
    api(project(":wow-core"))
    api(project(":wow-query"))
    implementation(kotlin("reflect"))
    api("io.swagger.core.v3:swagger-core-jakarta")
    api("com.github.victools:jsonschema-generator")
    api("com.github.victools:jsonschema-module-jackson")
    api("com.github.victools:jsonschema-module-jakarta-validation")
    api("com.github.victools:jsonschema-module-swagger-2")
    implementation(project(":wow-models"))
    implementation("tools.jackson.datatype:jackson-datatype-joda-money")
    testImplementation("org.springframework:spring-web")
    testImplementation(libs.json.schema.validator)
    testImplementation("io.swagger.core.v3:swagger-core-jakarta")
    testImplementation(project(":wow-tck"))
    testImplementation(project(":example-api"))
    testImplementation(project(":example-domain"))
}

tasks.processResources {
    from(rootProject.file("schema/query/v2/filter-expression.schema.json")) {
        into("META-INF/wow-schema")
        rename { "FilterExpression.json" }
    }
}
