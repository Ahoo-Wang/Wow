description = "Wow V10 example: the shop's commands, events and value types"

dependencies {
    api(project(":api"))
    // Bean Validation constraints on command bodies, for the edge to check (managed by the Spring Boot BOM). Compile
    // only: the domain never validates, and a missing annotation class is ignored at run time. compileOnlyApi puts it
    // on the domain's compile class path too, so KSP resolves the annotations it reads on the commands.
    compileOnlyApi("jakarta.validation:jakarta.validation-api")
}
