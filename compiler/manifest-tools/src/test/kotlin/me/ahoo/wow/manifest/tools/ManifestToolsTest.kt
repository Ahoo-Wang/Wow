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
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.schedule.SetSchedule
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
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
import me.ahoo.wow.spi.SystemCommandGroups
import me.ahoo.wow.spi.snapshot.RebuildSnapshot
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

    /** A class path directory holding [manifests] as fragments, by module name (a path, `orders/domain`). */
    private fun classesDirectory(name: String, vararg manifests: Pair<String, Manifest>): File =
        File(directory, name).also { classes ->
            manifests.forEach { (module, manifest) ->
                ManifestJson.write(manifest, File(classes, "${Manifest.FRAGMENT_DIRECTORY}/$module.json"))
            }
            // Another file type is no fragment.
            File(classes, "${Manifest.FRAGMENT_DIRECTORY}/README.txt").apply { parentFile.mkdirs() }
                .writeText("not a fragment")
        }

    /** The jar [name] holding [manifest] as the fragment of [module] (a path, `orders/domain`). */
    private fun jar(
        module: String,
        manifest: Manifest,
        name: String = module.replace('/', '-'),
        provider: String? = null,
    ): File {
        val fragment = File(directory, "$name-fragment.json").also { ManifestJson.write(manifest, it) }
        return File(directory, "$name.jar").also { jar ->
            ZipOutputStream(jar.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("${Manifest.FRAGMENT_DIRECTORY}/"))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("${Manifest.FRAGMENT_DIRECTORY}/$module.json"))
                zip.write(fragment.readBytes())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("${Manifest.FRAGMENT_DIRECTORY}/README.txt"))
                zip.closeEntry()
                provider?.let {
                    zip.putNextEntry(ZipEntry("META-INF/services/me.ahoo.wow.kernel.ModelProvider"))
                    zip.write("$it\n".toByteArray())
                    zip.closeEntry()
                }
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
        completed.contexts.single().aggregates.single().state.fingerprint.assert().hasSize(16)
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
        // A nested project's fragment is in a directory of its path.
        val classpath = listOf(
            classesDirectory("classes", "shop/domain" to shop),
            jar("billing/domain", billing),
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
    fun `fragments of two project paths that join to one name should both be merged`() {
        val shop =
            fragment("shop", CartState::class.java, mapOf(CartState::class.java.name to mapOf("type" to "object")))
        val billing = fragment(
            "billing",
            AccountState::class.java,
            mapOf(AccountState::class.java.name to mapOf("type" to "object")),
        )
        // `:a-b` and `:a:b`: `a-b.json` and `a/b.json`, two names, wherever each is found.
        val output = File(directory, "manifest.json")
        listOf(
            listOf(classesDirectory("flat", "a-b" to shop), classesDirectory("nested", "a/b" to billing)),
            listOf(jar("a-b", shop, "flat"), jar("a/b", billing, "nested")),
        ).forEach { classpath ->
            ManifestTools.merge(classpath, emptyMap(), "store", "fallback", null, output).assert().isEqualTo(2)
            ManifestJson.read(output).contexts.map { it.name }.assert().containsExactly("billing", "shop")
        }
    }

    @Test
    fun `merging should refuse a model provider two modules register, naming both`() {
        val shop =
            fragment("shop", CartState::class.java, mapOf(CartState::class.java.name to mapOf("type" to "object")))
        val billing = fragment(
            "billing",
            AccountState::class.java,
            mapOf(AccountState::class.java.name to mapOf("type" to "object")),
        )
        fun registering(entry: File, vararg providers: String) = entry.also {
            File(it, "META-INF/services/me.ahoo.wow.kernel.ModelProvider").apply { parentFile.mkdirs() }
                .writeText("# the module's binding index\n" + providers.joinToString("\n") + "\n")
        }
        // `:orders:domain` and `:orders-domain`, their models in one package: one provider class, one of them unbound.
        val nested = registering(
            classesDirectory("nested", "orders/domain" to shop),
            "orders.OrdersDomainModelProvider"
        )
        val flat = jar("orders-domain", billing, provider = "orders.OrdersDomainModelProvider")
        val owners = mapOf(nested.path to "project ':orders:domain'", flat.path to "project ':orders-domain'")
        val output = File(directory, "manifest.json")
        assertThrows<IllegalStateException> {
            ManifestTools.merge(listOf(nested, flat), owners, "store", "fallback", null, output)
        }.message.assert().contains(
            "The model provider orders.OrdersDomainModelProvider is registered by project ':orders:domain' and " +
                "project ':orders-domain'",
        )
        // Apart in two packages, both bound; a jar that registers none and a file that is no jar register nothing.
        val apart = jar("orders-domain", billing, provider = "refunds.OrdersDomainModelProvider")
        val other = File(directory, "notes.txt").apply { writeText("no class path entry") }
        ManifestTools.merge(
            listOf(nested, apart, other),
            owners,
            "store",
            "fallback",
            null,
            output
        ).assert().isEqualTo(2)
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
        ManifestTools.generateContract(listOf(fragmentFile), null, ":shop-domain", emptyMap(), contractFile)
        ManifestJson.read(contractFile).contexts.map { it.name }.assert().containsExactly("shop")

        val classes = File(directory, "api-classes")
        File(classes, CreateCart::class.java.name.replace('.', '/') + ".class").apply { parentFile.mkdirs() }
            .writeText("")
        val output = File(directory, "api")
        val owners = mapOf(contractFile.path to "project ':shop-domain'")
        ManifestTools.assembleContract(listOf(contractFile), owners, listOf(classes), "project ':shop-api'", output)
            .assert().isTrue()
        ManifestJson.read(File(output, PeerContracts.RESOURCE)).contexts.single().aggregates.single().commands
            .map { it.name }.assert().containsExactly("CreateCart")

        // The classes are in another api module: it carries no contract, and clears what it carried.
        ManifestTools.assembleContract(listOf(contractFile), owners, emptyList(), "project ':other-api'", output)
            .assert().isFalse()
        File(output, PeerContracts.RESOURCE).exists().assert().isFalse()

        // A project whose classes hold the context's, and that does not pair with the domain module, is named.
        assertThrows<IllegalStateException> {
            ManifestTools.generateContract(
                listOf(fragmentFile),
                null,
                ":shop-domain",
                mapOf(classes.path to ":shop-api"),
                contractFile,
            )
        }.message.assert().isEqualTo(
            "The contract of project ':shop-domain' reaches no api module:\n- context shop has its classes " +
                "${CreateCart::class.java.name} in project ':shop-api', which does not pair with project " +
                "':shop-domain': in :shop-api's build file, apply id(\"me.ahoo.wow.api\") and add " +
                "dependencies { wowContract(project(\":shop-domain\")) }",
        )
    }

    @Test
    fun `an api module should refuse a declared domain module of no contract`() {
        val empty = File(directory, "empty/contract.json").also { ManifestJson.write(Manifest(), it) }
        assertThrows<IllegalStateException> {
            ManifestTools.assembleContract(
                listOf(empty),
                mapOf(empty.path to "project ':empty-domain'"),
                emptyList(),
                "project ':shop-api'",
                File(directory, "api"),
            )
        }.message.assert().startsWith(
            "project ':shop-api' declares project ':empty-domain' in wowContract, which compiles no model",
        )
    }

    @Test
    fun `the OpenAPI document should be written from the merged Manifest`() {
        val manifest = File(directory, "manifest.json").also {
            ManifestJson.write(fragment("shop", CartState::class.java).copyWith(service = ServiceInfo("shop")), it)
        }
        val output = File(directory, "openapi/openapi.json")
        ManifestTools.generateOpenApi(manifest, output)
        output.readText().assert().contains("\"openapi\" : \"3.1").endsWith("}\n")
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
    fun `the chart's values should name the service and the peers whose contracts are on its class path`() {
        val manifest = File(directory, "manifest.json").also {
            ManifestJson.write(
                fragment("shop", CartState::class.java).copyWith(service = ServiceInfo("shop-service")),
                it
            )
        }
        val classpath = listOf(
            // The service's own api: its contexts are no peer.
            contractJar("shop-api", contract("shop", "shop-service")),
            contractJar("billing-api", contract("billing", "billing-service")),
            // A contract that names no service: the peer is reached by its url alone.
            contractJar("mail-api", contract("mail", null)),
            jar("cart-domain", fragment("cart", CartState::class.java)),
            File(directory, "absent.jar"),
        )
        val output = File(directory, "helm/values.yaml")
        ManifestTools.generateHelmValues(manifest, classpath, output).assert().isEqualTo(2)
        output.readText().lines().filterNot { it.startsWith("#") }.joinToString("\n").assert().isEqualTo(
            """
            service:
              name: "shop-service"
              peers:
                - context: "billing"
                  service: "billing-service"
                - context: "mail"
              manifest:
                route: "/wow/manifest"

            """.trimIndent(),
        )
        ManifestTools.generateHelmValues(manifest, emptyList(), output).assert().isEqualTo(0)
        output.readText().assert().contains("  peers: []\n")
        // A text a YAML reader would take for something else is quoted as JSON quotes it.
        HelmValues("a: \"b\"", emptyList()).yaml().assert().contains("""  name: "a: \"b\""""")
        val unnamed = File(directory, "unnamed.json")
        ManifestJson.write(fragment("shop", CartState::class.java), unnamed)
        assertThrows<IllegalStateException> { ManifestTools.generateHelmValues(unnamed, emptyList(), output) }
            .message.assert().contains("names no service")
    }

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

    @Test
    fun `a derived system command should not be reported undecided, whichever group it is of`() {
        // Each is decided by the framework somewhere: every aggregate's kernel, a built-in aggregate, or an intake.
        val system = SystemCommandGroups.ALL.classes.map { Manifest.schemaRef(it.name) }.toTypedArray()
        ManifestTools.undecidedCommands(deriving("shop", *system), emptyList()).assert().isEmpty()
        val derived = deriving(
            "shop",
            Manifest.schemaRef(SetSchedule::class.java.name),
            Manifest.schemaRef(RebuildSnapshot::class.java.name),
        )
        ManifestTools.undecidedCommands(derived, emptyList()).assert().isEmpty()
        ManifestTools.undecidedCommands(deriving("shop", Manifest.schemaRef("shop.aggregate.Nowhere")), emptyList())
            .assert().containsExactly("shop/CartSaga.onEvent derives shop.aggregate.Nowhere")
    }

    class CreateCart(val id: String)
}
