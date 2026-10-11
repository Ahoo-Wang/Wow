plugins {
    alias(libs.plugins.ksp)
}

dependencies {
    api(project(":wow-dataset-registry-api"))
    api(project(":wow-spring"))
    api(project(":wow-query"))
    ksp(project(":wow-compiler"))
    testImplementation(project(":wow-test"))
    testImplementation("io.projectreactor:reactor-test")
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test, tasks.jacocoTestReport)
    violationRules {
        rule {
            limit {
                minimum = 0.8.toBigDecimal()
            }
        }
    }
}
