dependencies {
    api("tools.jackson.core:jackson-databind")
    compileOnly("com.fasterxml.jackson.core:jackson-annotations")
    compileOnly("io.swagger.core.v3:swagger-annotations-jakarta")
    compileOnly("org.springframework:spring-context")
    testImplementation("tools.jackson.module:jackson-module-kotlin")
}
