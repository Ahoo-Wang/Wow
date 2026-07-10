description = "Wow BI Sync Script Generator"

dependencies {
    api(project(":wow-api"))
    implementation(project(":wow-core"))
    testImplementation(project(":example-domain"))
    testImplementation(project(":example-transfer-domain"))
    testImplementation(project(":wow-compensation-domain"))
}
