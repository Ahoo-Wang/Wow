description = "Wow quickstart: the bank service of the quickstart page, its domain and its server in one module"

// The sample of documentation/docs/{en,zh}/start/quickstart.md (decision D217). `./gradlew :quickstart:run` starts
// it on the memory backend. Never published. The page applies the Gradle plugin `me.ahoo.wow`; the plugin cannot be
// applied inside the build that produces it, so this applies KSP and passes the plugin's options by hand, as the eShop
// does.
plugins {
    alias(libs.plugins.ksp)
    application
}

application {
    mainClass.set("me.ahoo.wow.example.quickstart.BankServerKt")
}

// `./gradlew :quickstart:run` is one node on this machine: its internal port on the loopback interface alone, which runs
// without a cluster token (decision D226). A service of several nodes binds an address they reach and sets
// wow.internal.token.
tasks.named<JavaExec>("run") {
    systemProperty("wow.internal.host", "127.0.0.1")
}

ksp {
    arg("wow.model", "generate")
    arg("wow.manifest.module", project.name)
}

// The plugin's completion of the fragment with the schemas of the types, so the snapshot queries know the state.
apply(from = rootProject.file("example/wow-manifest-fragment.gradle.kts"))

dependencies {
    implementation(project(":kernel"))
    implementation(project(":spring-boot"))
    // The commands' Jakarta constraints and a Bean Validation provider: the host checks them at admission (D106).
    implementation("org.springframework.boot:spring-boot-starter-validation")
    ksp(project(":compiler"))
    testImplementation(project(":test-dsl"))
    testImplementation("org.springframework:spring-test")
}
