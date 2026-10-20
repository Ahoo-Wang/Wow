plugins {
    alias(libs.plugins.ksp)
}
dependencies {
    api(project(":wow-compensation-api"))
    api(project(":wow-spring"))
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
