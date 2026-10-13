description = "Wow V10 example: the inventory's commands and events"

dependencies {
    api(project(":api"))
    // Bean Validation constraints on command bodies, for the edge to check, as the shop's API declares them.
    compileOnlyApi("jakarta.validation:jakarta.validation-api")
}
