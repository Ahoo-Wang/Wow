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
import me.ahoo.wow.api.lifecycle.TransferOwner
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.FunctionKind
import me.ahoo.wow.manifest.FunctionManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.Manifest.Companion.schemaRef
import me.ahoo.wow.manifest.ManifestConflictException
import me.ahoo.wow.manifest.PeerContracts
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.SubscriptionManifest
import me.ahoo.wow.manifest.SubscriptionSource
import me.ahoo.wow.manifest.copyWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ManifestToolsTest {
    class CartState(val id: String)

    class AccountState(val id: String, val holder: String? = null)

    @TempDir
    lateinit var directory: File

    private fun fragment(context: String, state: Class<*>, defs: Map<String, Any?> = emptyMap()): Manifest = Manifest(
        contexts = listOf(
            ContextManifest(
                name = context,
                aggregates = listOf(
                    AggregateManifest(name = "aggregate", state = StateManifest(schemaRef(state.name)))
                ),
            ),
        ),
        defs = defs,
    )

    /** A class path directory holding [manifests] as fragments, by module name. */
    private fun classesDirectory(name: String, vararg manifests: Pair<String, Manifest>): File =
        File(directory, name).also { classes ->
            manifests.forEach { (module, manifest) ->
                ManifestJson.write(manifest, File(classes, "${Manifest.FRAGMENT_DIRECTORY}/$module.json"))
            }
            // Neither a nested directory nor another file type is a fragment.
            File(
                classes,
                "${Manifest.FRAGMENT_DIRECTORY}/nested/ignored.json"
            ).apply { parentFile.mkdirs() }.writeText("{}")
            File(classes, "${Manifest.FRAGMENT_DIRECTORY}/README.txt").writeText("not a fragment")
        }

    /** A jar holding [manifest] as the fragment of [module]. */
    private fun jar(module: String, manifest: Manifest): File {
        val fragment = File(directory, "$module-fragment.json").also { ManifestJson.write(manifest, it) }
        return File(directory, "$module.jar").also { jar ->
            ZipOutputStream(jar.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("${Manifest.FRAGMENT_DIRECTORY}/"))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("${Manifest.FRAGMENT_DIRECTORY}/$module.json"))
                zip.write(fragment.readBytes())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("${Manifest.FRAGMENT_DIRECTORY}/nested/ignored.json"))
                zip.write("{}".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("sample/Other.class"))
                zip.closeEntry()
            }
        }
    }

    @Test
    fun `completing a fragment should write its schemas and fingerprints`() {
        val skeleton = File(
            directory,
            "skeleton.json"
        ).also { ManifestJson.write(fragment("shop", CartState::class.java), it) }
        val output = File(directory, "out/${Manifest.FRAGMENT_DIRECTORY}/shop.json")

        ManifestTools.completeFragment(skeleton, output, emptyMap())

        val completed = ManifestJson.read(output)
        completed.unresolvedSchemaRefs.assert().isEmpty()
        completed.contexts.single().aggregates.single().state.fingerprint.assert().hasSize(64)
    }

    @Test
    fun `merging should read the fragments of directories and jars`() {
        val shop =
            fragment("shop", CartState::class.java, mapOf(CartState::class.java.name to mapOf("type" to "object")))
        val billing =
            fragment(
                "billing",
                AccountState::class.java,
                mapOf(AccountState::class.java.name to mapOf("type" to "object"))
            )
        val output = File(directory, "manifest.json")
        val classpath = listOf(
            classesDirectory("classes", "shop" to shop),
            jar("billing", billing),
            File(directory, "missing.jar"),
            File(directory, "classes-without-fragments").apply { mkdirs() },
        )

        ManifestTools.merge(classpath, emptyMap(), "store", "fallback", "1.0.0", output).assert().isEqualTo(2)

        val manifest = ManifestJson.read(output)
        manifest.service.assert().isEqualTo(ServiceInfo("store", "1.0.0"))
        manifest.contexts.map { it.name }.assert().containsExactly("billing", "shop")
    }

    @Test
    fun `merging should refuse conflicting fragments`() {
        val shop =
            fragment("shop", CartState::class.java, mapOf(CartState::class.java.name to mapOf("type" to "object")))
        val classpath = listOf(classesDirectory("a", "shop" to shop), jar("other", shop))
        assertThrows<ManifestConflictException> {
            ManifestTools.merge(classpath, emptyMap(), "store", "fallback", null, File(directory, "manifest.json"))
        }.conflicts.single().assert().startsWith("context shop is declared by")
    }

    @Test
    fun `merging should refuse two fragments of one name, naming their owners`() {
        val shop =
            fragment("shop", CartState::class.java, mapOf(CartState::class.java.name to mapOf("type" to "object")))
        val billing =
            fragment(
                "billing",
                AccountState::class.java,
                mapOf(AccountState::class.java.name to mapOf("type" to "object"))
            )
        // Two modules of one path in two builds: the node would read one `domain.json` alone and drop a context.
        val local = classesDirectory("classes", "domain" to shop)
        val published = jar("domain", billing)
        val owners = mapOf(local.path to "project ':domain'", published.path to "com.acme:domain:1.0.0")
        assertThrows<IllegalStateException> {
            ManifestTools.merge(
                listOf(local, published),
                owners,
                "store",
                "fallback",
                null,
                File(directory, "manifest.json")
            )
        }.message.assert().contains(
            "The Manifest fragment META-INF/wow/manifest/domain.json is in project ':domain' and com.acme:domain:1.0.0",
        ).doesNotContain(directory.path)
    }

    @Test
    fun `merging should refuse a schema no fragment holds`() {
        val classpath = listOf(classesDirectory("classes", "shop" to fragment("shop", CartState::class.java)))
        assertThrows<IllegalStateException> {
            ManifestTools.merge(classpath, emptyMap(), "store", "fallback", null, File(directory, "manifest.json"))
        }.message.assert().contains("refers to schemas no fragment holds").contains(CartState::class.java.name)
        File(directory, "manifest.json").exists().assert().isFalse()
    }

    @Test
    fun `a domain module's contract should be assembled into the api module that holds its classes`() {
        val created = CommandManifest("CreateCart", schema = schemaRef(CreateCart::class.java.name))
        val shop = fragment("shop", CartState::class.java).let { fragment ->
            val aggregate = fragment.contexts.single().aggregates.single().copyWith(commands = listOf(created))
            fragment.copyWith(contexts = listOf(fragment.contexts.single().copyWith(aggregates = listOf(aggregate))))
        }
        val fragmentFile = File(directory, "fragment/shop.json").also { ManifestJson.write(shop, it) }
        val contractFile = File(directory, "domain/contract.json")
        ManifestTools.generateContract(listOf(fragmentFile), contractFile, null)
        ManifestJson.read(contractFile).contexts.map { it.name }.assert().containsExactly("shop")

        val classes = File(directory, "api-classes")
        File(classes, CreateCart::class.java.name.replace('.', '/') + ".class").apply { parentFile.mkdirs() }
            .writeText("")
        val output = File(directory, "api/contract.json")
        ManifestTools.assembleContract(listOf(contractFile), listOf(classes), ":shop-api", output).assert().isTrue()
        ManifestJson.read(output).contexts.single().aggregates.single().commands.map { it.name }
            .assert().containsExactly("CreateCart")

        val other = File(directory, "other/contract.json")
        ManifestTools.assembleContract(listOf(contractFile), emptyList(), ":other-api", other).assert().isFalse()
        other.exists().assert().isFalse()
    }

    /** [fragment] of [context] with a saga `CartSaga.onEvent` that derives [derives]. */
    private fun deriving(context: String, vararg derives: String): Manifest {
        val shop =
            fragment(context, CartState::class.java, mapOf(CartState::class.java.name to mapOf("type" to "object")))
        val saga = SubscriptionManifest(
            name = "CartSaga.onEvent",
            kind = SubscriptionKind.SAGA,
            source = SubscriptionSource(),
            function = FunctionManifest(FunctionKind.SAGA, context, "CartSaga", "onEvent"),
            derives = derives.toList(),
        )
        val defs = derives.filter { it.startsWith("#") }
            .associate { Manifest.schemaKey(it) to mapOf("type" to "object") }
        return Manifest(
            contexts = listOf(
                ContextManifest(
                    name = context,
                    aggregates = shop.contexts.single().aggregates,
                    subscriptions = listOf(saga)
                ),
            ),
            defs = shop.defs + defs,
        )
    }

    /** A jar holding [contract] as its peer contract. */
    private fun contractJar(name: String, contract: Manifest): File {
        val file = File(directory, "$name-contract.json").also { ManifestJson.write(contract, it) }
        return File(directory, "$name.jar").also { jar ->
            ZipOutputStream(jar.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry(PeerContracts.RESOURCE))
                zip.write(file.readBytes())
                zip.closeEntry()
            }
        }
    }

    @Test
    fun `merging should refuse a derived command no context or peer contract decides`() {
        val reserve = schemaRef(CreateCart::class.java.name)
        val shop = deriving("shop", reserve, schemaRef(TransferOwner::class.java.name), "shop.aggregate.Local")
        val classpath = listOf(classesDirectory("classes", "shop" to shop))
        assertThrows<IllegalStateException> {
            ManifestTools.merge(classpath, emptyMap(), "store", "fallback", null, File(directory, "manifest.json"))
        }.message.assert()
            .contains("- shop/CartSaga.onEvent derives ${CreateCart::class.java.name}")
            // A lifecycle command is no context's, and a full type name is the service's own.
            .doesNotContain(TransferOwner::class.java.name)
            .doesNotContain("Local")

        val inventory = Manifest(
            contexts = listOf(
                ContextManifest(
                    name = "inventory",
                    aggregates = listOf(
                        AggregateManifest(
                            name = "stock",
                            state = StateManifest(schemaRef(AccountState::class.java.name)),
                            commands = listOf(CommandManifest("Reserve", schema = reserve)),
                        ),
                    ),
                ),
            ),
        )
        val peers = classpath + listOf(contractJar("inventory-api", inventory), File(directory, "inventory-classes"))
        ManifestTools.merge(
            peers,
            emptyMap(),
            "store",
            "fallback",
            null,
            File(directory, "manifest.json")
        ).assert().isEqualTo(1)
    }

    /** The service a merge of the shop's fragment and the [contracts] describes, unnamed, with "fallback" by default. */
    private fun derivedService(vararg contracts: Pair<String, Manifest>, own: String? = null): String {
        val classpath = listOf(jar("shop", shopFragment())) +
            contracts.map { (name, contract) -> contractJar(name, contract) }
        val output = File(directory, "manifest.json")
        ManifestTools.merge(classpath, emptyMap(), own, "fallback", null, output)
        return ManifestJson.read(output).service!!.name
    }

    private fun shopFragment(): Manifest =
        fragment("shop", CartState::class.java, mapOf(CartState::class.java.name to mapOf("type" to "object")))

    private fun contract(context: String, service: String?): Manifest =
        PeerContracts.contractOf(listOf(fragment(context, CartState::class.java)), service)

    @Test
    fun `an unnamed service should be the one the contract of its contexts names`() {
        derivedService("shop-api" to contract("shop", "shop-service")).assert().isEqualTo("shop-service")
    }

    @Test
    fun `an unnamed service should ignore the services its peers' contracts name`() {
        derivedService("shop-api" to contract("shop", "shop-service"), "billing-api" to contract("billing", "billing"))
            .assert().isEqualTo("shop-service")
        derivedService("billing-api" to contract("billing", "billing")).assert().isEqualTo("fallback")
    }

    @Test
    fun `an unnamed service whose contracts name no service should take the default name`() {
        derivedService().assert().isEqualTo("fallback")
        derivedService("shop-api" to contract("shop", null)).assert().isEqualTo("fallback")
    }

    @Test
    fun `an unnamed service whose contracts name several services should fail`() {
        val failure = assertThrows<IllegalStateException> {
            derivedService("shop-api" to contract("shop", "shop-service"), "shop-v2-api" to contract("shop", "other"))
        }
        failure.message.assert().contains("shop-service", "other", "wow { service")
    }

    @Test
    fun `a service's own name should agree with its contexts' contracts`() {
        derivedService(own = "shop-service").assert().isEqualTo("shop-service")
        derivedService("shop-api" to contract("shop", "shop-service"), own = "shop-service").assert()
            .isEqualTo("shop-service")
        assertThrows<IllegalStateException> {
            derivedService("shop-api" to contract("shop", "shop-service"), own = "edge")
        }.message.assert().contains("shop-service", "edge (the service module's wow { service })")
    }

    @Test
    fun `a classes directory should carry its peer contract`() {
        val classes = File(directory, "api-classes")
        ManifestJson.contract(classes).assert().isNull()
        ManifestJson.write(fragment("shop", CartState::class.java), File(classes, PeerContracts.RESOURCE))
        ManifestJson.contract(classes)!!.manifest.contexts.single().name.assert().isEqualTo("shop")
        // A jar without one, or what is neither a directory nor a jar, carries none.
        ManifestJson.contract(jar("billing", fragment("billing", AccountState::class.java))).assert().isNull()
        ManifestJson.contract(File(directory, "notes.txt").apply { writeText("") }).assert().isNull()
        ManifestJson.contract(contractJar("shop-api", fragment("shop", CartState::class.java)))!!
            .manifest.contexts.single().name.assert().isEqualTo("shop")
    }

    @Test
    fun `a subscription that derives nothing it names by schema should pass the check`() {
        val shop = deriving("shop", "shop.aggregate.Local")
        val silent = Manifest(
            contexts = listOf(
                ContextManifest(
                    name = "billing",
                    subscriptions = listOf(
                        SubscriptionManifest(
                            name = "BillingSaga.onEvent",
                            kind = SubscriptionKind.SAGA,
                            source = SubscriptionSource(),
                            function = FunctionManifest(FunctionKind.SAGA, "billing", "BillingSaga", "onEvent"),
                        ),
                    ),
                ),
            ),
        )
        ManifestTools.undecidedCommands(shop, emptyList()).assert().isEmpty()
        ManifestTools.undecidedCommands(silent, emptyList()).assert().isEmpty()
    }

    class CreateCart(val id: String)
}
