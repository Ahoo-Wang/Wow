dependencies {
    api(project(":wow-core"))
    implementation(project(":wow-query"))
    api("org.springframework:spring-context")
    testImplementation("me.ahoo.test:fluent-assert-core")
}
