description = "Wow API: what domain code imports (the annotations, Decision, DecideScope, StateEvent, Scope), " +
    "the value types of the wire contract (Command, CommandResult, WaitPlan) and the typed QueryApi"

// The stability markers, the query protocol and the coroutines' Flow the QueryApi streams its rows with (decision
// D260); and Jackson's databind, which this module's code does not use: an api module's free-shape content is an
// ObjectNode or a JsonNode, its classes' and its generated state views' alike (D362), so every api module has it.
dependencies {
    // The guard of the hand-written equals (finding R5.B-13).
    testImplementation(project(":tck"))
    api(project(":annotations"))
    api(project(":query-api"))
    // The one platform a contract module imports (R5.C-4): the version of its one third-party dependency.
    api(platform(libs.kotlinx.coroutines.bom))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    api("tools.jackson.core:jackson-databind")
}
