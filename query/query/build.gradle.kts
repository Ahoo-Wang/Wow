description = "Wow Query: the query engine over the snapshot, event and dataset models, and its backend SPI"

// The coroutine port of 9.x wow-query (decision D126); this is its skeleton (work package 6.00).
dependencies {
    api(project(":query-api"))
    api(project(":spi"))
    api(project(":manifest"))
}
