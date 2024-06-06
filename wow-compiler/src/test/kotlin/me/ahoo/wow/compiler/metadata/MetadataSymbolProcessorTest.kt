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

package me.ahoo.wow.compiler.metadata

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.symbolProcessorProviders
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import java.io.File

class MetadataSymbolProcessorTest {

    @OptIn(ExperimentalCompilerApi::class)
    @Test
    fun process() {
        val mockBoundedContextFile = File("src/test/kotlin/me/ahoo/wow/compiler/MockBoundedContext.kt")
        val mockCompilerAggregateFile = File("src/test/kotlin/me/ahoo/wow/compiler/MockCompilerAggregate.kt")
        val compilation = KotlinCompilation().apply {
            sources =
                listOf(
                    SourceFile.fromPath(mockBoundedContextFile),
                    SourceFile.fromPath(mockCompilerAggregateFile),
                )
            symbolProcessorProviders = mutableListOf(MetadataSymbolProcessorProvider())
            inheritClassPath = true
            messageOutputStream = System.out
        }
        val result = compilation.compile()
        assertThat(result.exitCode, `is`(KotlinCompilation.ExitCode.OK))
    }

    @OptIn(ExperimentalCompilerApi::class)
    @Test
    fun processJava() {
        val mockBoundedContextFile = File("src/test/java/me/ahoo/wow/compiler/MockJavaBoundedContext.java")
        val mockCompilerAggregateFile = File("src/test/java/me/ahoo/wow/compiler/MockJavaCompilerAggregate.java")
        val compilation = KotlinCompilation().apply {
            sources =
                listOf(
                    SourceFile.fromPath(mockBoundedContextFile),
                    SourceFile.fromPath(mockCompilerAggregateFile),
                )
            symbolProcessorProviders = mutableListOf(MetadataSymbolProcessorProvider())
            inheritClassPath = true
            messageOutputStream = System.out
        }
        val result = compilation.compile()
        assertThat(result.exitCode, `is`(KotlinCompilation.ExitCode.OK))
    }

    @OptIn(ExperimentalCompilerApi::class)
    @Test
    fun processExample() {
        val exampleApiDir = File("../example/example-api/src/main/kotlin/me/ahoo/wow/example/api")
        val exampleApiFiles = exampleApiDir.walkTopDown().filter { it.isFile }.toList()
            .map { SourceFile.fromPath(it) }
        val exampleDomainDir = File("../example/example-domain/src/main/kotlin/me/ahoo/wow/example/domain")
        val exampleDomainFiles = exampleDomainDir.walkTopDown().filter { it.isFile }.toList()
            .map { SourceFile.fromPath(it) }
        val compilation = KotlinCompilation().apply {
            sources = exampleDomainFiles + exampleApiFiles
            symbolProcessorProviders = mutableListOf(MetadataSymbolProcessorProvider())
            inheritClassPath = true
            messageOutputStream = System.out
        }
        val result = compilation.compile()
        assertThat(result.exitCode, `is`(KotlinCompilation.ExitCode.OK))
    }
}
