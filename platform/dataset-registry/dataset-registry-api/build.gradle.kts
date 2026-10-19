description = "Wow V10 dataset registry: the commands, events and value types of the dataset registry context (roadmap 9.3b)"

dependencies {
    api(project(":api"))
    // A source's sensitivity is the query engine's SensitivityLevel, which the datasets built on it are masked by.
    api(project(":query-api"))
}
