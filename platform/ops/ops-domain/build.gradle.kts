description = "Wow ops: the parked aggregate, the read model of every service's failure ledger (roadmap 6.3f, 7.1a)"

// The minimal ops domain of work package 6.3f (decision D141); roadmap 7.1a builds the ops service on it. The plugin
// `me.ahoo.wow` compiles the aggregate and the saga into kernel models, their binding index and the module's Manifest
// fragment, and offers its contract to :ops-api, as the eShop's domains do.
plugins {
    id("me.ahoo.wow")
}

wow {
    service = "ops-service"
}

dependencies {
    api(project(":ops-api"))
    // @QueryTemporal on the parked state's times: a query buckets the entries by when they failed (roadmap 7.1c).
    implementation("me.ahoo.wow:wow-query-api")
    testImplementation("me.ahoo.wow:wow-test")
    // The Manifest fragment the compiler writes, which the tests read (the kernel does not bring :manifest, D231).
    testImplementation("me.ahoo.wow:wow-manifest")
}
