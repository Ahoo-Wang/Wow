description = "Wow Reactor: Reactor adapters for the user programming model"

dependencies {
    api(project(":wow-runtime"))
    api("io.projectreactor:reactor-core")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
}
