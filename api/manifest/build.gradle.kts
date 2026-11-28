description = "Wow Manifest: the contract every projection reads (REST, OpenAPI, clients, views, Skills); plain data"

dependencies {
    // The guard of the hand-written equals (finding R5.B-13).
    testImplementation(project(":tck"))
    // The stability markers (decision D282), so the Manifest can hide what it promises nothing about.
    api(project(":annotations"))
    // The error catalog's retry (D366 ②), the one Retry of the programming model's problems.
    api(project(":api"))
    // Only to show that the plain tree of ManifestTree is what a JSON library writes and reads back.
    testImplementation("tools.jackson.module:jackson-module-kotlin")
    // Only to validate what ManifestTree writes against ManifestSchema.
    testImplementation(libs.json.schema.validator)
    // Only to hold the contract's vocabulary (error codes, Creation, Ownership) to the programming model's (D294).
}

// ManifestRequiresTest holds the Kotlin reader to the table of refused markers that wow-client's and wow-generator's
// tests hold their readers to: declared, so a changed table runs it instead of reusing a cached result.
tasks.named<Test>("test") {
    inputs.file(rootProject.file("typescript/wow-client/fixtures/manifest-requires-cases.json"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("manifestRequiresCases")
}
