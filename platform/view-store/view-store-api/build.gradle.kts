description = "Wow view store: the commands, events and value types of the view store context (roadmap 7.2a)"

plugins {
    id("me.ahoo.wow.api")
}

dependencies {
    // The domain module whose contract this api module carries (decision D363 ①).
    wowContract(project(":view-store-domain"))
    // @JsonValue on the view kinds and audiences, so they read as the view engine writes them ("record", "shared").
    api("com.fasterxml.jackson.core:jackson-annotations")
}
