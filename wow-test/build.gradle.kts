description = "The Technology Compatibility Kit"

dependencies {
    api(project(":wow-core"))
    api("io.projectreactor:reactor-test")
    api("me.ahoo.cosid:cosid-test")
    api("org.hamcrest:hamcrest")
    implementation("org.junit.jupiter:junit-jupiter-api")
    implementation("com.fasterxml.jackson.core:jackson-databind")
}
