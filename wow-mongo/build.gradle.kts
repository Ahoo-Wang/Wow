dependencies {
    api(project(":wow-core"))
    api(project(":wow-query"))
    api("org.mongodb:mongodb-driver-reactivestreams")
    // V10: the log and the stores (me.ahoo.wow.mongo.{log,snapshot,subscription,ledger,reservation})
    // on the sync driver and virtual threads, like wow-postgres (D27).
    api(project(":spi"))
    api("org.mongodb:mongodb-driver-sync")
    testImplementation(project(":wow-tck"))
    integrationTestImplementation(testFixtures(project(":spi")))
}

// Spike H1 (MongoDB) measures throughput only on demand: ./gradlew :wow-mongo:integrationTest -Dwow.spike.h1=true
tasks.named<Test>("integrationTest") {
    systemProperty("wow.spike.h1", providers.systemProperty("wow.spike.h1").orElse("false").get())
}
