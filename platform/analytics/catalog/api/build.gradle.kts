description = "Wow analytics catalog: the commands, events and value types of the analytics catalog context (roadmap 9.3b)"

plugins {
    id("me.ahoo.wow.api")
}

dependencies {
    // The domain module whose contract this api module carries (decision D363 ①).
    wowContract(project(":analytics-catalog-domain"))
    // A source's sensitivity is the query engine's SensitivityLevel, which the datasets built on it are masked by.
    api("me.ahoo.wow:wow-query-api")
}
