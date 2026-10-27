plugins {
    `java-test-fixtures`
}

description = "Wow Manifest: the contract every projection reads (REST, OpenAPI, clients, views, Skills); plain data"

dependencies {
    // The guard of the hand-written equals (finding R5.B-13).
    testImplementation(project(":tck"))
    // The stability markers (decision D282), so the Manifest can hide what it promises nothing about.
    api(project(":annotations"))
    // Only to show that the plain tree of ManifestTree is what a JSON library writes and reads back.
    testImplementation("tools.jackson.module:jackson-module-kotlin")
    // Only to hold the contract's vocabulary (error codes, Creation, Ownership) to the programming model's (D294).
    testImplementation(project(":api"))
}
