package me.ahoo.wow.compiler.query

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.symbolProcessorProviders
import me.ahoo.wow.compiler.SourceFiles.toSourceFile
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import java.io.File

class QuerySymbolProcessorTest {

    @OptIn(ExperimentalCompilerApi::class)
    @Test
    fun process() {
        val mockBoundedContextFile = File("src/test/kotlin/me/ahoo/wow/compiler/MockBoundedContext.kt")
        val mockCompilerAggregateFile = File("src/test/kotlin/me/ahoo/wow/compiler/MockCompilerAggregate.kt")
        val compilation = KotlinCompilation().apply {
            sources =
                listOf(
                    mockBoundedContextFile.toSourceFile(),
                    mockCompilerAggregateFile.toSourceFile(),
                )
            symbolProcessorProviders = mutableListOf(QuerySymbolProcessorProvider())
            inheritClassPath = true
            languageVersion = "1.9"
        }
        val result = compilation.compile()
        assertThat(result.messages, result.exitCode, `is`(KotlinCompilation.ExitCode.OK))
    }

    @OptIn(ExperimentalCompilerApi::class)
    @Test
    fun processExample() {
        val exampleApiDir = File("../example/example-api/src/main/kotlin/me/ahoo/wow/example/api")
        val exampleApiFiles = exampleApiDir.walkTopDown().filter { it.isFile }.toList()
            .map { it.toSourceFile() }
        val exampleDomainDir = File("../example/example-domain/src/main/kotlin/me/ahoo/wow/example/domain")
        val exampleDomainFiles = exampleDomainDir.walkTopDown().filter { it.isFile }.toList()
            .map { it.toSourceFile() }
        val compilation = KotlinCompilation().apply {
            sources = exampleDomainFiles + exampleApiFiles
            symbolProcessorProviders = mutableListOf(QuerySymbolProcessorProvider())
            inheritClassPath = true
            languageVersion = "1.9"
        }
        val result = compilation.compile()
        assertThat(result.messages, result.exitCode, `is`(KotlinCompilation.ExitCode.OK))
    }

    @OptIn(ExperimentalCompilerApi::class)
    @Test
    fun processJava() {
        val mockBoundedContextFile = File("src/test/java/me/ahoo/wow/compiler/MockJavaBoundedContext.java")
        val mockCompilerAggregateFile = File("src/test/java/me/ahoo/wow/compiler/MockJavaCompilerAggregate.java")
        val compilation = KotlinCompilation().apply {
            sources =
                listOf(
                    mockBoundedContextFile.toSourceFile(),
                    mockCompilerAggregateFile.toSourceFile()
                )
            symbolProcessorProviders = mutableListOf(QuerySymbolProcessorProvider())
            inheritClassPath = true
            languageVersion = "1.9"
        }
        val result = compilation.compile()
        assertThat(result.messages, result.exitCode, `is`(KotlinCompilation.ExitCode.OK))
    }
}
