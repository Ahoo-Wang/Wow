package me.ahoo.wow.compiler.query

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.kspSourcesDir
import me.ahoo.test.asserts.assert
import me.ahoo.wow.compiler.compileTest
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.Path

class QuerySymbolProcessorTest {
    @OptIn(ExperimentalCompilerApi::class)
    fun compileTestQuerySymbolProcessor(
        sources: List<File>,
        consumer: (KotlinCompilation, JvmCompilationResult) -> Unit = { _, _ ->
        }
    ) {
        compileTest(sources, QuerySymbolProcessorProvider(), consumer)
    }

    @OptIn(ExperimentalCompilerApi::class)
    @Test
    fun process() {
        val mockBoundedContextFile = File("src/test/kotlin/me/ahoo/wow/compiler/MockBoundedContext.kt")
        val mockCompilerAggregateFile = File("src/test/kotlin/me/ahoo/wow/compiler/MockCompilerAggregate.kt")
        compileTestQuerySymbolProcessor(
            listOf(mockBoundedContextFile, mockCompilerAggregateFile),
        ) { compilation, _ ->
            val navFile = Path(
                compilation.kspSourcesDir.path,
                "kotlin/me/ahoo/wow/compiler",
                "MockCompilerAggregateProperties.kt"
            )
            val navFileContent = navFile.toFile().readText()
            val navFileContentLines = navFileContent.lines()
            val navFileContentLinesWithoutGenerated = navFileContentLines.subList(0, 4) + navFileContentLines.subList(
                5,
                navFileContentLines.size
            )
            val navFileContentWithoutGenerated = navFileContentLinesWithoutGenerated.joinToString("\n").trimIndent()
            navFileContentWithoutGenerated.assert().isEqualTo(
                """
                package me.ahoo.wow.compiler
                
                import me.ahoo.wow.api.annotation.Generated
                
                object MockCompilerAggregateProperties {
                    const val ID = "id"
                    const val STATE = "state"
                }
                """.trimIndent()
            )
        }
    }

    @OptIn(ExperimentalCompilerApi::class)
    @Test
    fun processExample() {
        val exampleApiDir = File("../example/example-api/src/main/kotlin/me/ahoo/wow/example/api")
        val exampleApiFiles = exampleApiDir.walkTopDown().filter { it.isFile }.toList()
        val exampleDomainDir = File("../example/example-domain/src/main/kotlin/me/ahoo/wow/example/domain")
        val exampleDomainFiles = exampleDomainDir.walkTopDown().filter { it.isFile }.toList()
        compileTestQuerySymbolProcessor(exampleApiFiles + exampleDomainFiles)
    }

    @OptIn(ExperimentalCompilerApi::class)
    @Test
    fun processJava() {
        val mockBoundedContextFile = File("src/test/java/me/ahoo/wow/compiler/MockJavaBoundedContext.java")
        val mockCompilerAggregateFile = File("src/test/java/me/ahoo/wow/compiler/MockJavaCompilerAggregate.java")
        compileTestQuerySymbolProcessor(listOf(mockBoundedContextFile, mockCompilerAggregateFile)) { compilation, _ ->
            val navFile = Path(
                compilation.kspSourcesDir.path,
                "kotlin/me/ahoo/wow/compiler",
                "MockJavaCompilerAggregateProperties.kt"
            )
            val navFileContent = navFile.toFile().readText()
            val navFileContentLines = navFileContent.lines()
            val navFileContentLinesWithoutGenerated = navFileContentLines.subList(0, 4) + navFileContentLines.subList(
                5,
                navFileContentLines.size
            )
            val navFileContentWithoutGenerated = navFileContentLinesWithoutGenerated.joinToString("\n").trimIndent()
            navFileContentWithoutGenerated.assert().isEqualTo(
                """
                    package me.ahoo.wow.compiler
                    
                    import me.ahoo.wow.api.annotation.Generated
                    
                    object MockJavaCompilerAggregateProperties {
                        const val ID = "id"
                    }
                """.trimIndent()
            )
        }
    }
}
