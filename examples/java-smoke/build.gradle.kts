description = "Wow Java smoke test: a domain written in Java, built by the Wow compiler"

// Decision D229 G-8: a Java domain builds with the compiler. Its sources are Java only, under src/main/java; the
// Kotlin JVM plugin (applied to every library project by the root build) is what a Java domain module applies too,
// because KSP runs within the Kotlin compilation and the models it generates are Kotlin (the Gradle plugin
// `me.ahoo.wow` fails a module without it), and the plugin `me.ahoo.wow`, as the eShop's domains do. Its tests are
// Kotlin: the test DSL has no Java API yet. Never published.
plugins {
    id("me.ahoo.wow")
}

dependencies {
    testImplementation("me.ahoo.wow:wow-test")
}
