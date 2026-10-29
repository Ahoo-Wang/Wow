description = "Wow quickstart: the bank service of the quickstart page, its domain and its server in one module"

// The sample of documentation/docs/{en,zh}/start/quickstart.md (decision D217). `./gradlew :quickstart:run` starts it
// on the memory backend, `--args='--spring.profiles.active=mongo'` on MongoDB (or `postgres`). Never published. As on
// the page, the service in one module applies `me.ahoo.wow.service`: KSP with wow-compiler, the BOM and the kernel, the
// service's Manifest and its gate on manifest/baseline.json.
plugins {
    application
    id("me.ahoo.wow.service")
}

// The service as it names itself (spring.application.name), which its Manifest names.
wow {
    service = "bank-service"
}

application {
    mainClass.set("me.ahoo.wow.example.quickstart.BankServerKt")
}

// `./gradlew :quickstart:run` is one node on this machine: its internal port on the loopback interface alone. A service of
// several nodes binds an address they reach, behind a NetworkPolicy that lets only the platform's own pods in (D300).
tasks.named<JavaExec>("run") {
    systemProperty("wow.internal.host", "127.0.0.1")
}

dependencies {
    implementation("me.ahoo.wow:wow-spring-boot")
    // Swagger UI over the service's routes (/swagger-ui.html): the host merges Wow's document into springdoc's.
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui")
    // The commands' Jakarta constraints and a Bean Validation provider: the host checks them at admission (D106).
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // The `mongo` profile (application-mongo.yaml): the host runs on MongoDB when the Mongo URI is set (decision D318).
    implementation("me.ahoo.wow:wow-mongo")
    // The `postgres` profile (application-postgres.yaml), with the pool the host makes of `spring.datasource.*` (D310).
    implementation("me.ahoo.wow:wow-postgres")
    runtimeOnly("com.zaxxer:HikariCP")
    testImplementation("me.ahoo.wow:wow-test")
    testImplementation("me.ahoo.wow:wow-test-spring-boot")
}
