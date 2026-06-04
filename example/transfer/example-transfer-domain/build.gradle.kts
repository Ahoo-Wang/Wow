import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

plugins {
    alias(libs.plugins.ksp)
}
dependencies {
    api(project(":example-transfer-api"))
    api(project(":wow-spring"))
    ksp(project(":wow-compiler"))
    testImplementation(project(":wow-test"))
    testImplementation("io.projectreactor:reactor-test")
}

val domainTestTask = tasks.named<Test>("domainTest")
val domainTestJacocoData = domainTestTask.map {
    it.extensions.getByType(JacocoTaskExtension::class).destinationFile
}

tasks.jacocoTestReport {
    dependsOn(domainTestTask)
    executionData.setFrom(domainTestJacocoData)
}

tasks.jacocoTestCoverageVerification {
    dependsOn(domainTestTask, tasks.jacocoTestReport)
    executionData.setFrom(domainTestJacocoData)
    violationRules {
        rule {
            limit {
                minimum = 0.8.toBigDecimal()
            }
        }
    }
}
