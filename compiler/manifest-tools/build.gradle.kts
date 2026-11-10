import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

description = "Wow Manifest tools: completes Manifest fragments with Jackson-introspected schemas and merges them; " +
    "run by wow-gradle-plugin in isolated workers, never at runtime"

dependencies {
    implementation(project(":manifest"))
    // generateWowOpenApi: the OpenAPI document of the merged Manifest, plain data like wow-manifest.
    implementation(project(":openapi"))
    // The state schema fingerprint, Wow's state JSON (StateJson), computed at build time as a snapshot carries it.
    implementation(project(":spi"))
    // The query field annotations it writes as the x-wow-* keywords of the state schema (decisions D125, D151).
    implementation(project(":query-api"))
    // Build time only (decision D73): schemas are introspected in the plugin's isolated workers (decision D83).
    implementation("com.github.victools:jsonschema-generator")
    implementation("com.github.victools:jsonschema-module-jackson")
    // The Jakarta constraints of commands, queries and states as validation keywords of their schemas (decision D380);
    // the module reads the annotations through the API, which it leaves to its user.
    implementation("com.github.victools:jsonschema-module-jakarta-validation")
    implementation("jakarta.validation:jakarta.validation-api")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation(kotlin("reflect"))
    testImplementation("com.fasterxml.jackson.core:jackson-annotations")
    // StateViewNamesTest compiles the state views it generates.
    testImplementation(libs.kotlin.compile.testing)
    // Only to show that the fingerprint it writes is the one the kernel checks a snapshot against.
    testImplementation(project(":kernel"))
}

// The fixtures of the state schema's computed properties, compiled in each of Kotlin's JVM default modes: as Wow
// compiles its own classes (`no-compatibility`), and as Kotlin compiles an application's by default (`enable`), where a
// class repeats each default getter of its interfaces as a bridge of its own. The tests read each mode's classes from a
// class loader of their own, their directories named by `wow-test.jvm-default.<mode>`.
mapOf("noCompatibility" to JvmDefaultMode.NO_COMPATIBILITY, "enable" to JvmDefaultMode.ENABLE).forEach { (mode, jvmDefault) ->
    val fixtures = sourceSets.create("jvmDefault${mode.replaceFirstChar(Char::uppercase)}")
    the<KotlinJvmProjectExtension>().sourceSets.named(fixtures.name) {
        kotlin.setSrcDirs(listOf("src/jvmDefault/kotlin"))
    }
    tasks.named<KotlinCompile>(fixtures.getCompileTaskName("kotlin")) {
        compilerOptions.jvmDefault.set(jvmDefault)
    }
    tasks.named<Test>("test") {
        val classes = fixtures.output.classesDirs
        inputs.files(classes).withPropertyName("jvmDefault.$mode")
        jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dwow-test.jvm-default.$mode=${classes.asPath}") })
    }
}

// detekt (and so :check) covers the fixtures, which no other check reads.
tasks.named<io.gitlab.arturbosch.detekt.Detekt>("detekt") {
    source(files("src/jvmDefault/kotlin"))
}
