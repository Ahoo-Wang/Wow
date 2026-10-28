description = "Wow view store: the commands, events and value types of the view store context (roadmap 7.2a)"

dependencies {
    api(project(":api"))
    // @JsonValue on the view kinds and audiences, so they read as the view engine writes them ("record", "shared").
    api("com.fasterxml.jackson.core:jackson-annotations")
    // The generated state views read the free-form config of a view and a wording's fields as Jackson's ObjectNode.
    api("tools.jackson.core:jackson-databind")
}
