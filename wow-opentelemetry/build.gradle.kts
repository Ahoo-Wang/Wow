dependencies {
    api(project(":wow-core"))
    implementation("io.opentelemetry.instrumentation:opentelemetry-instrumentation-api")
    testImplementation(project(":wow-tck"))
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("io.opentelemetry:opentelemetry-sdk")
}
