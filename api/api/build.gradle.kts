description = "Wow API: what domain code imports (the annotations, Decision, DecideScope, StateEvent, Scope), " +
    "the value types of the wire contract (Command, CommandResult, WaitPlan) and the typed QueryApi"

// The stability markers, the query protocol and the coroutines' Flow the QueryApi streams its rows with (decision
// D260); nothing else.
dependencies {
    // The guard of the hand-written equals (finding R5.B-13).
    testImplementation(project(":tck"))
    api(project(":annotations"))
    api(project(":query-api"))
    // The one platform a contract module imports (R5.C-4): the version of its one third-party dependency.
    api(platform(libs.kotlinx.coroutines.bom))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core")
}
