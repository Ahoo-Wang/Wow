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

package me.ahoo.wow.schema.kotlin

import com.github.victools.jsonschema.generator.SchemaGenerator
import me.ahoo.test.asserts.assert
import me.ahoo.wow.schema.KotlinFixture
import me.ahoo.wow.schema.RecursiveGetterFixture
import me.ahoo.wow.schema.SchemaGeneratorBuilder
import org.junit.jupiter.api.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Query-schema sources describe aggregates concurrently, each with its own generator. Every concurrent generation must
 * produce what a lone generation produces, including the getter-only properties the Kotlin provider adds. One generator
 * is not shared across threads: victools' Jackson property sorter is not thread-safe.
 */
class KotlinCustomDefinitionProviderConcurrencyTest {
    private val types = listOf(KotlinFixture::class.java, RecursiveGetterFixture::class.java)

    @Test
    fun `recursive getter-only properties are generated`() {
        val properties = SchemaGeneratorBuilder().customizer { }.build()
            .generateSchema(RecursiveGetterFixture::class.java)
            .get("properties")
        properties.has("size").assert().isTrue()
        properties.has("first").assert().isTrue()
    }

    @Test
    fun `concurrent generators match a lone generation`() {
        assertConcurrentGenerations { SchemaGeneratorBuilder().build() }
    }

    private fun assertConcurrentGenerations(generator: () -> SchemaGenerator) {
        val expected = types.associateWith { SchemaGeneratorBuilder().build().generateSchema(it) }
        val barrier = CyclicBarrier(THREADS)
        val executor = Executors.newFixedThreadPool(THREADS)
        try {
            val results = (1..THREADS).map {
                executor.submit<List<Boolean>> {
                    val threadGenerator = generator()
                    (1..ROUNDS).flatMap {
                        barrier.await(1, TimeUnit.MINUTES)
                        types.map { type -> threadGenerator.generateSchema(type) == expected.getValue(type) }
                    }
                }
            }.flatMap { it.get(1, TimeUnit.MINUTES) }
            results.count { !it }.assert().isZero()
        } finally {
            executor.shutdownNow()
        }
    }

    private companion object {
        const val THREADS = 4
        const val ROUNDS = 50
    }
}
