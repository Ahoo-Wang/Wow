description = "Wow BI Sync Script Generator"

dependencies {
    implementation(project(":wow-core"))
    implementation(libs.jte)
    implementation(libs.jte.kotlin)
    testImplementation(project(":example-domain"))
    testImplementation(project(":wow-compensation-domain"))
}