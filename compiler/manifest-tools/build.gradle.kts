description = "Wow Manifest tools: completes Manifest fragments with Jackson-introspected schemas and merges them; " +
    "run by wow-gradle-plugin in isolated workers, never at runtime"

dependencies {
    implementation(project(":manifest"))
    // generateWowOpenApi: the OpenAPI document of the merged Manifest, plain data like wow-manifest.
    implementation(project(":openapi"))
    // The state schema fingerprint, Wow's state JSON (StateJson), computed at build time as a snapshot carries it.
    implementation(project(":spi"))
    // The query field annotations it writes as the x-wow-* keywords of the state schema (decisions D125, D151).
    implementation(project(":query-api"))
    // Build time only (decision D73): schemas are introspected in the plugin's isolated workers (decision D83).
    implementation("com.github.victools:jsonschema-generator")
    implementation("com.github.victools:jsonschema-module-jackson")
    // The Jakarta constraints of commands, queries and states as validation keywords of their schemas (decision D380);
    // the module reads the annotations through the API, which it leaves to its user.
    implementation("com.github.victools:jsonschema-module-jakarta-validation")
    implementation("jakarta.validation:jakarta.validation-api")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation(kotlin("reflect"))
    testImplementation("com.fasterxml.jackson.core:jackson-annotations")
    // Only to show that the fingerprint it writes is the one the kernel checks a snapshot against.
    testImplementation(project(":kernel"))
}
