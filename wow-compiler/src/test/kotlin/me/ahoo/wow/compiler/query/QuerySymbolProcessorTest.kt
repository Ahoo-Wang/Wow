package me.ahoo.wow.compiler.query

import me.ahoo.wow.compiler.compileTest
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import java.io.File

class QuerySymbolProcessorTest {

    @OptIn(ExperimentalCompilerApi::class)
    @Test
    fun process() {
        val mockBoundedContextFile = File("src/test/kotlin/me/ahoo/wow/compiler/MockBoundedContext.kt")
        val mockCompilerAggregateFile = File("src/test/kotlin/me/ahoo/wow/compiler/MockCompilerAggregate.kt")
        compileTest(listOf(mockBoundedContextFile, mockCompilerAggregateFile), QuerySymbolProcessorProvider())
    }

    @OptIn(ExperimentalCompilerApi::class)
    @Test
    fun processExample() {
        val exampleApiDir = File("../example/example-api/src/main/kotlin/me/ahoo/wow/example/api")
        val exampleApiFiles = exampleApiDir.walkTopDown().filter { it.isFile }.toList()
        val exampleDomainDir = File("../example/example-domain/src/main/kotlin/me/ahoo/wow/example/domain")
        val exampleDomainFiles = exampleDomainDir.walkTopDown().filter { it.isFile }.toList()
        compileTest(exampleApiFiles + exampleDomainFiles, QuerySymbolProcessorProvider())
    }

    @OptIn(ExperimentalCompilerApi::class)
    @Test
    fun processJava() {
        val mockBoundedContextFile = File("src/test/java/me/ahoo/wow/compiler/MockJavaBoundedContext.java")
        val mockCompilerAggregateFile = File("src/test/java/me/ahoo/wow/compiler/MockJavaCompilerAggregate.java")
        compileTest(listOf(mockBoundedContextFile, mockCompilerAggregateFile), QuerySymbolProcessorProvider())
    }
}
