description = "Wow Reactor: Reactor adapters for the user programming model"

dependencies {
    api(project(":kernel"))
    api("io.projectreactor:reactor-core")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
}
