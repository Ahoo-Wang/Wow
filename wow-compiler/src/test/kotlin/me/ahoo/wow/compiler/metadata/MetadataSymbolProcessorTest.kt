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

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.kspSourcesDir
import me.ahoo.wow.compiler.compileTest
import me.ahoo.wow.configuration.WOW_METADATA_RESOURCE_NAME
import me.ahoo.wow.configuration.WowMetadata
import me.ahoo.wow.serialization.toObject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.Path
import kotlin.io.path.readText

class MetadataSymbolProcessorTest {
    @OptIn(ExperimentalCompilerApi::class)
    fun compileTestMetadataSymbolProcessor(
        sources: List<File>,
        consumer: (KotlinCompilation, JvmCompilationResult) -> Unit = { _, _ ->
        }
    ) {
        compileTest(sources, MetadataSymbolProcessorProvider(), consumer)
    }

    @OptIn(ExperimentalCompilerApi::class)
    @Test
    fun process() {
        val mockBoundedContextFile = File("src/test/kotlin/me/ahoo/wow/compiler/MockBoundedContext.kt")
        val mockCompilerAggregateFile = File("src/test/kotlin/me/ahoo/wow/compiler/MockCompilerAggregate.kt")
        compileTestMetadataSymbolProcessor(
            listOf(
                mockBoundedContextFile,
                mockCompilerAggregateFile,
            )
        ) { compilation, _ ->
            val metadataContent = Path(
                compilation.kspSourcesDir.path,
                "resources",
                WOW_METADATA_RESOURCE_NAME
            ).readText()
            val metadata = metadataContent.toObject<WowMetadata>()
            assertThat(metadata.contexts.containsKey("mock"), equalTo(true))
        }
    }

    @OptIn(ExperimentalCompilerApi::class)
    @Test
    fun processJava() {
        val mockBoundedContextFile = File("src/test/java/me/ahoo/wow/compiler/MockJavaBoundedContext.java")
        val mockCompilerAggregateFile = File("src/test/java/me/ahoo/wow/compiler/MockJavaCompilerAggregate.java")
        compileTestMetadataSymbolProcessor(
            listOf(
                mockBoundedContextFile,
                mockCompilerAggregateFile,
            )
        ) { compilation, _ ->
            val metadataContent = Path(
                compilation.kspSourcesDir.path,
                "resources",
                WOW_METADATA_RESOURCE_NAME
            ).readText()
            val metadata = metadataContent.toObject<WowMetadata>()
            assertThat(metadata.contexts.containsKey("mock_java"), equalTo(true))
        }
    }

    @OptIn(ExperimentalCompilerApi::class)
    @Test
    fun processExample() {
        val exampleApiDir = File("../example/example-api/src/main/kotlin/me/ahoo/wow/example/api")
        val exampleApiFiles = exampleApiDir.walkTopDown().filter { it.isFile }.toList()
        val exampleDomainDir = File("../example/example-domain/src/main/kotlin/me/ahoo/wow/example/domain")
        val exampleDomainFiles = exampleDomainDir.walkTopDown().filter { it.isFile }.toList()

        compileTestMetadataSymbolProcessor(
            exampleApiFiles + exampleDomainFiles,
        ) { compilation, _ ->
            val metadataContent = Path(
                compilation.kspSourcesDir.path,
                "resources",
                WOW_METADATA_RESOURCE_NAME
            ).readText()
            val metadata = metadataContent.toObject<WowMetadata>()
            assertThat(metadata.contexts.containsKey("example-service"), equalTo(true))
        }
    }
}
