description = "Wow API: what domain code imports (the V10 annotations, Decision, DecideScope, StateEvent, Scope), " +
    "the value types of the wire contract (Command, CommandResult, WaitPlan) and the typed QueryApi"

// The query protocol and the coroutines' Flow the QueryApi streams its rows with (decision D260); nothing else.
dependencies {
    api(project(":query-api"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core")
}
