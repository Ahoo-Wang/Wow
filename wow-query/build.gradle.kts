dependencies {
    api(project(":wow-core"))
    testImplementation("io.projectreactor:reactor-test")
    testImplementation(project(":wow-tck"))
}

kotlin {
    compilerOptions {
        optIn.add("me.ahoo.wow.query.backend.ExperimentalQueryBackendApi")
        optIn.add("me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi")
    }
}
