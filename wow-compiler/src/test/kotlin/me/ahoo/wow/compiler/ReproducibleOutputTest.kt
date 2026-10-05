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

package me.ahoo.wow.compiler

import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.tschuchort.compiletesting.kspSourcesDir
import me.ahoo.test.asserts.assert
import me.ahoo.wow.compiler.aggregate.metadata.AggregatesMetadataSymbolProcessorProvider
import me.ahoo.wow.compiler.metadata.MetadataSymbolProcessor
import me.ahoo.wow.compiler.metadata.MetadataSymbolProcessorProvider
import me.ahoo.wow.compiler.query.QuerySymbolProcessorProvider
import me.ahoo.wow.configuration.WOW_METADATA_RESOURCE_NAME
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.naming.NamingConverter
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File

class ReproducibleOutputTest {
    /** The same sources compiled twice, a moment apart, generate byte-identical files. */
    @OptIn(ExperimentalCompilerApi::class)
    @ParameterizedTest
    @MethodSource("processors")
    fun `generated files do not depend on when they are generated`(provider: SymbolProcessorProvider) {
        val sources = exampleSources()
        val first = generate(sources, provider)
        Thread.sleep(SECOND_BUILD_DELAY_MILLIS)
        val second = generate(sources, provider)

        first.assert().isNotEmpty()
        second.assert().isEqualTo(first)
    }

    @Test
    fun `names the compiler writes without wow-core agree with wow-core`() {
        MetadataSymbolProcessor.WOW_METADATA_RESOURCE_PATH.assert().isEqualTo(WOW_METADATA_RESOURCE_NAME)
        MESSAGE_EXCHANGE_NAME.assert().isEqualTo(MessageExchange::class.qualifiedName)
        listOf("Order", "OrderItem", "CartState", "HTTPRequest", "A", "orderAggregate").forEach {
            it.pascalToSnake().assert().isEqualTo(NamingConverter.PASCAL_TO_SNAKE.convert(it))
        }
    }

    @OptIn(ExperimentalCompilerApi::class)
    private fun generate(sources: List<File>, provider: SymbolProcessorProvider): Map<String, String> {
        lateinit var generated: Map<String, String>
        compileTest(sources, provider) { compilation, _ ->
            val root = compilation.kspSourcesDir
            generated = root.walkTopDown().filter { it.isFile }
                .associate { it.relativeTo(root).path to it.readText() }
        }
        return generated
    }

    private fun exampleSources(): List<File> =
        listOf(
            File("../example/example-api/src/main/kotlin/me/ahoo/wow/example/api"),
            File("../example/example-domain/src/main/kotlin/me/ahoo/wow/example/domain"),
        ).flatMap { dir -> dir.walkTopDown().filter { it.isFile }.toList() } +
            // AggregatesMetadata is generated for the aggregates under a bounded-context marker.
            File("src/test/kotlin/me/ahoo/wow/compiler/MockBoundedContext.kt") +
            File("src/test/kotlin/me/ahoo/wow/compiler/MockCompilerAggregate.kt")

    companion object {
        private const val SECOND_BUILD_DELAY_MILLIS = 20L

        @JvmStatic
        fun processors(): List<SymbolProcessorProvider> = listOf(
            AggregatesMetadataSymbolProcessorProvider(),
            QuerySymbolProcessorProvider(),
            MetadataSymbolProcessorProvider(),
        )
    }
}
