/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.compiler.aggregate.metadata

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.kspSourcesDir
import me.ahoo.test.asserts.assert
import me.ahoo.wow.compiler.compileTest
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.Path

class AggregatesMetadataSymbolProcessorTest {
    @OptIn(ExperimentalCompilerApi::class)
    fun compileNamedAggregatesSymbolProcessor(
        sources: List<File>,
        consumer: (KotlinCompilation, JvmCompilationResult) -> Unit = { _, _ ->
        }
    ) {
        compileTest(sources, AggregatesMetadataSymbolProcessorProvider(), consumer)
    }

    @OptIn(ExperimentalCompilerApi::class)
    @Test
    fun process() {
        val mockBoundedContextFile = File("src/test/kotlin/me/ahoo/wow/compiler/MockBoundedContext.kt")
        val mockCompilerAggregateFile = File("src/test/kotlin/me/ahoo/wow/compiler/MockCompilerAggregate.kt")
        compileNamedAggregatesSymbolProcessor(
            listOf(mockBoundedContextFile, mockCompilerAggregateFile),
        ) { compilation, _ ->
            val navFile = Path(
                compilation.kspSourcesDir.path,
                "kotlin/me/ahoo/wow/compiler",
                "AggregatesMetadata.kt"
            )
            val navFileContent = navFile.toFile().readText()
            navFileContent.assert().contains(
                "val MockCompilerAggregateAggregateMetadata = aggregateMetadata<MockCompilerAggregate, MockCompilerAggregate>()"
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
        compileNamedAggregatesSymbolProcessor(exampleApiFiles + exampleDomainFiles)
    }

    @OptIn(ExperimentalCompilerApi::class)
    @Test
    fun processJava() {
        val mockBoundedContextFile = File("src/test/java/me/ahoo/wow/compiler/MockJavaBoundedContext.java")
        val mockCompilerAggregateFile = File("src/test/java/me/ahoo/wow/compiler/MockJavaCompilerAggregate.java")
        compileNamedAggregatesSymbolProcessor(listOf(mockBoundedContextFile, mockCompilerAggregateFile))
    }
}
