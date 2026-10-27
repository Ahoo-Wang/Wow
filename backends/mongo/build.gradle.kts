description = "Wow MongoDB: log, snapshots, query, checkpoints, leases, failure ledger and reservations on MongoDB"

dependencies {
    api(project(":spi"))
    // The snapshot query backend (decision D131).
    api(project(":query"))
    // The log and the stores run on the sync driver and virtual threads, like wow-postgres (D27).
    api("org.mongodb:mongodb-driver-sync")
    implementation("tools.jackson.core:jackson-databind")
    implementation("io.github.oshai:kotlin-logging-jvm")
    testImplementation(project(":query-tck"))
    integrationTestImplementation(project(":tck"))
    integrationTestImplementation(project(":tck-containers"))
    integrationTestImplementation(project(":query-tck"))
}

// Spike H1 (MongoDB) measures throughput only on demand: ./gradlew :mongo:integrationTest -Dwow.spike.h1=true
tasks.named<Test>("integrationTest") {
    systemProperty("wow.spike.h1", providers.systemProperty("wow.spike.h1").orElse("false").get())
    // A MongoDB cluster to run against in place of the single-member container (roadmap §6 item 18):
    // test/mongo-cluster/cluster.sh test rs|sharded
    providers.systemProperty("wow.mongo.url").orNull?.let { systemProperty("wow.mongo.url", it) }
    providers.systemProperty("wow.mongo.container").orNull?.let { systemProperty("wow.mongo.container", it) }
}
