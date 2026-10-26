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

package me.ahoo.wow.migrate

import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.migrate.example.NineXExample
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

class MigrationModelsTest {
    @TempDir
    lateinit var directory: Path

    private val parent: ClassLoader = javaClass.classLoader

    /** A dependency of the models, which the tool never calls. */
    interface Pricing {
        fun price(productId: String): Int
    }

    private class Provider(val take: (Dependencies) -> Unit) : ModelProvider {
        override val context: String = NineXExample.CONTEXT
        override val binding: Int = ModelProvider.BINDING
        override val fragment: String = "test.json"

        override fun models(dependencies: Dependencies): List<AggregateModel<*>> {
            take(dependencies)
            return listOf(NineXExample.orderModel)
        }
    }

    @Test
    fun `the models registered on the class path are found by their type`() {
        val models = MigrationModels.load(parent)
        models.of(NineXExample.orderModel.type).assert().isSameAs(NineXExample.orderModel)
        models.of(NineXExample.cartModel.type).assert().isSameAs(NineXExample.cartModel)
    }

    @Test
    fun `a dependency of an interface is a stand-in the tool never calls`() {
        lateinit var pricing: Pricing
        MigrationModels.of(listOf(Provider { pricing = it.get(Pricing::class.java, "prices") }))
        pricing.toString().assert().contains("stand-in").contains("\"prices\"")
        pricing.hashCode().assert().isEqualTo(System.identityHashCode(pricing))
        (pricing == pricing).assert().isTrue()
        pricing.equals("other").assert().isFalse()
        assertThrows<MigrationException> { pricing.price("p-1") }.message!!.assert().contains("Pricing.price")
    }

    @Test
    fun `a dependency of a class cannot be stood in for`() {
        assertThrows<MigrationException> {
            MigrationModels.of(listOf(Provider { it.get(StringBuilder::class.java) }))
        }.message!!.assert().contains("java.lang.StringBuilder")
    }

    @Test
    fun `the class path entries are jars and directories, with the jars in them`() {
        MigrationModels.classLoaderOf(emptyList(), parent).assert().isSameAs(parent)
        val lib = Files.createDirectory(directory.resolve("lib"))
        Files.createFile(lib.resolve("b.jar"))
        Files.createFile(lib.resolve("a.jar"))
        Files.createFile(lib.resolve("notes.txt"))
        val single = Files.createFile(directory.resolve("single.jar"))
        val loader = MigrationModels.classLoaderOf(listOf(lib.toString(), single.toString()), parent) as URLClassLoader
        loader.urLs.map { Path.of(it.toURI()).fileName.toString() }.assert()
            .containsExactly("lib", "a.jar", "b.jar", "single.jar")
        loader.parent.assert().isSameAs(parent)
        assertThrows<MigrationException> {
            MigrationModels.classLoaderOf(listOf(directory.resolve("missing.jar").toString()), parent)
        }.message!!.assert().contains("does not exist")
    }
}
