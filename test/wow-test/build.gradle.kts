description = "Wow Test Suite"

dependencies {
    api(project(":wow-core"))
    api("io.projectreactor:reactor-test")
    api("me.ahoo.cosid:cosid-test")
    api("me.ahoo.test:fluent-assert-core")
    api("org.hibernate.validator:hibernate-validator")
    implementation("org.junit.jupiter:junit-jupiter-api")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("io.micrometer:micrometer-core")
    testImplementation(project(":example-domain"))
}
