description = "Wow Query API: the query protocol (filters, sort, pagination, projection, aggregation) and the query " +
    "field annotations, as plain data; nothing but the stability markers"

// The stability markers alone (decision D282), so the query protocol can hide what it promises nothing about.
dependencies {
    // The guard of the hand-written equals (finding R5.B-13).
    testImplementation(project(":tck"))
    api(project(":annotations"))
}
