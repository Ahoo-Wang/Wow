description = "Wow Manifest tools: completes Manifest fragments with Jackson-introspected schemas and merges them; " +
    "run by wow-gradle-plugin in isolated workers, never at runtime"

dependencies {
    implementation(project(":wow-manifest"))
    // The state schema fingerprint is the kernel's own (EventCodec.stateSchemaFingerprint), computed at build time.
    implementation(project(":wow-kernel"))
    // Build time only (decision D73): schemas are introspected in the plugin's isolated workers (decision D83).
    implementation("com.github.victools:jsonschema-generator")
    implementation("com.github.victools:jsonschema-module-jackson")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation(kotlin("reflect"))
    testImplementation("com.fasterxml.jackson.core:jackson-annotations")
}
