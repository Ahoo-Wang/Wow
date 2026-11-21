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

package me.ahoo.wow.manifest.tools

import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The package of each context's generated code ([ContractPackages]): required, checked once, carried as a file. */
class ContractPackagesTest {
    @TempDir
    lateinit var directory: File

    /** A context [name] whose commands are the classes [commands]. */
    private fun context(name: String, vararg commands: String) = ContextManifest(
        name,
        aggregates = listOf(
            AggregateManifest(
                "cart",
                state = StateManifest(Manifest.schemaRef("sample.CartState")),
                commands = commands.map {
                    CommandManifest(
                        it.substringAfterLast('.'),
                        schema = Manifest.schemaRef(it)
                    )
                },
            ),
        ),
    )

    @Test
    fun `a module with a context and no package should fail, giving the line with the package its classes share`() {
        assertThrows<IllegalStateException> {
            ContractPackages.of(
                null,
                listOf(context("shop-ordering", "shop.api.cart.AddItem", "shop.api.Pay")),
                ":shop"
            )
        }.message.assert().isEqualTo(
            "Project ':shop' sets no contractPackage. It is the package of the code generated for its context " +
                "shop-ordering (ShopOrderingContract), which other services import, so once it is set it should not " +
                "change. Add to its build file, with the package its command and event classes share today:\n\n" +
                "    wow { contractPackage = \"shop.api\" }\n",
        )
        // Classes that share no package: the module chooses one.
        assertThrows<IllegalStateException> {
            ContractPackages.of(null, listOf(context("shop", "shop.AddItem", "billing.Pay")), ":shop")
        }.message.assert().endsWith(
            "Its command and event classes share no package, so choose one: wow { contractPackage = \"<package>\" }",
        )
        // A module of no context needs none.
        ContractPackages.of(null, emptyList(), ":empty").assert().isNull()
    }

    @Test
    fun `a package should be a Java package name, naming the segment that is not`() {
        val contexts = listOf(context("shop", "shop.AddItem"))
        ContractPackages.of("com.example.shop.api", contexts, ":shop").assert().isEqualTo("com.example.shop.api")
        // A Kotlin keyword is a Java identifier: the generated code quotes it.
        ContractPackages.of("com.example.object", contexts, ":shop").assert().isEqualTo("com.example.object")
        mapOf(
            "" to "it is empty",
            "com..shop" to "its segment \"\" is not a Java identifier",
            "com.1shop" to "its segment \"1shop\" is not a Java identifier",
            "com.shop-api" to "its segment \"shop-api\" is not a Java identifier",
            "com.package" to "its segment \"package\" is a Java keyword",
            "com.null" to "its segment \"null\" is a Java keyword",
        ).forEach { (value, problem) ->
            assertThrows<IllegalStateException> { ContractPackages.of(value, contexts, ":shop") }.message.assert()
                .isEqualTo(
                    "wow { contractPackage = \"$value\" } of project ':shop' is not a Java package name: $problem."
                )
        }
    }

    @Test
    fun `the packages should be written one line per context and read back from several files`() {
        val first = File(directory, "a/contract-package.properties")
        ContractPackages.write(mapOf("shop" to "shop.api", "audit" to "shop.api.audit"), first)
        first.readText().assert().isEqualTo("audit=shop.api.audit\nshop=shop.api\n")
        val second = File(directory, "b/contract-package.properties")
        ContractPackages.write(emptyMap(), second)
        second.readText().assert().isEmpty()
        ContractPackages.read(listOf(first, second)).assert()
            .isEqualTo(mapOf("audit" to "shop.api.audit", "shop" to "shop.api"))
    }
}
