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

package me.ahoo.wow.compiler.model

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSPropertyGetter
import com.google.devtools.ksp.symbol.KSType
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.Alias
import me.ahoo.wow.api.annotation.Name
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.Upgrade
import me.ahoo.wow.compiler.model.symbol.AnnotationArguments
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.SubscriptionStart
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Every annotation the model processor reads is read from its arguments, never through a proxy of the annotation
 * class: a constant of another module is the value it reads, and an argument KSP evaluated no value for fails the
 * compilation with an error naming the declaration, the annotation and the argument, generating no model and no
 * Manifest fragment. An argument that is not a constant (a plain `val`) has no value KSP evaluates, as a constant of
 * another module may not have.
 */
@OptIn(ExperimentalCompilerApi::class)
class AnnotationArgumentsTest {
    private val options = mapOf(
        ModelSymbolProcessorProvider.CONTEXT_OPTION to "shop",
        ModelSymbolProcessorProvider.MODULE_OPTION to "shop-domain",
    )

    /** What each argument the domain writes is, by its slot: a constant of the library [NAMES], by default. */
    private val constants = mapOf(
        "ROOT_NAME" to "Names.CART",
        "DESCRIPTION" to "Names.DESCRIPTION",
        "SNAPSHOT" to "Names.SNAPSHOT",
        "STATE_REVISION" to "Names.STATE_REVISION",
        "COMMAND_NAME" to "Names.OPEN",
        "QUALIFIER" to "Names.AUDIT",
        "SUMMARY" to "Names.SUMMARY",
        "EVENT_NAME" to "Names.OPENED",
        "REVISION" to "Names.REVISION",
        "ERROR_DESCRIPTION" to "Names.FULL",
        "ERROR_STATUS" to "Names.STATUS",
        "MOUNTED_NAME" to "Names.IMPORT",
        "MOUNTED_AGGREGATE" to "Names.CART",
        "PROCESSOR_NAME" to "Names.BOARD",
        "PROCESSOR_QUALIFIER" to "Names.AUDIT",
        // An enum entry is never another module's constant: only a plain val can leave it without a value.
        "START" to "OnEvent.Start.END",
        "JOB_NAME" to "Names.JOB",
        "JOB_CRON" to "Names.CRON",
        "JOB_TIME_ZONE" to "Names.ZONE",
        "ON_DELETED" to "Names.ON_DELETED",
        // A class is never another module's constant either: only a plain val can leave an array without a value.
        "AFTER_INCLUDE" to "[Open::class]",
        "SENDS" to "[Close::class]",
        "RETRY_ATTEMPTS" to "Names.ATTEMPTS",
        "UPGRADE_FROM" to "Names.FROM",
        "ROUTE_PATH" to "Names.ROUTE",
        "QUERY_NAME" to "Names.QUERY",
        "HANDLER_NAME" to "Names.HANDLER",
        "SUPPRESS" to "Names.UNMARKED",
        "WIRE_NAME" to "Names.WIRE",
        "JSON_IGNORE" to "Names.IGNORED",
        "IGNORED_PROPERTIES" to "Names.HIDDEN",
    )

    /** The domain, each slot of [constants] written as [arguments] gives it, else as the library's constant. */
    @Suppress("LongMethod") // The domain's sources, whole.
    private fun domain(arguments: Map<String, String> = emptyMap()): SourceFile {
        val slot = { name: String -> arguments[name] ?: constants.getValue(name) }
        return SourceFile.kotlin(
            "Cart.kt",
            """
            package sample

            import com.fasterxml.jackson.annotation.JsonIgnore
            import com.fasterxml.jackson.annotation.JsonIgnoreProperties
            import com.fasterxml.jackson.annotation.JsonProperty
            import me.ahoo.wow.api.annotation.AfterCommand
            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.Alias
            import me.ahoo.wow.api.annotation.CreateAggregate
            import me.ahoo.wow.api.annotation.DependencyName
            import me.ahoo.wow.api.annotation.Description
            import me.ahoo.wow.api.annotation.ErrorCode
            import me.ahoo.wow.api.annotation.MountedCommand
            import me.ahoo.wow.api.annotation.Name
            import me.ahoo.wow.api.annotation.OnCommand
            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.OnSchedule
            import me.ahoo.wow.api.annotation.Projection
            import me.ahoo.wow.api.annotation.Retry
            import me.ahoo.wow.api.annotation.Revision
            import me.ahoo.wow.api.annotation.RouteMethod
            import me.ahoo.wow.api.annotation.RouteQuery
            import me.ahoo.wow.api.annotation.Saga
            import me.ahoo.wow.api.annotation.Sends
            import me.ahoo.wow.api.annotation.ServiceRoute
            import me.ahoo.wow.api.annotation.Summary
            import me.ahoo.wow.api.annotation.Upgrade
            import me.ahoo.wow.api.client.CommandRequest
            import me.ahoo.wow.api.schedule.ScheduleTick
            import me.ahoo.wow.api.subscribe.StateEvent
            import names.Names
            import tools.jackson.databind.node.ObjectNode
            import kotlin.reflect.KClass

            /** Not constants: KSP evaluates no value for an argument that reads one. */
            object Local {
                val text: String = "local"
                val number: Int = 2
                val flag: Boolean = false
                val start: OnEvent.Start = OnEvent.Start.END
                val names: Array<String> = arrayOf("local")
                val commands: Array<KClass<*>> = arrayOf(Open::class)
            }

            fun interface Audit { fun log(text: String) }

            @Suppress(${slot("SUPPRESS")})
            @Name(${slot("COMMAND_NAME")})
            @CreateAggregate
            data class Open(val id: String)

            @Summary(${slot("SUMMARY")})
            data class Close(val id: String)

            @Name(${slot("EVENT_NAME")})
            @Revision(${slot("REVISION")})
            @Alias("Begun", Names.FORMER)
            data class Opened(val id: String, val note: String)

            data class Closed(val id: String)

            @Upgrade(event = Opened::class, from = ${slot("UPGRADE_FROM")})
            fun addNote(node: ObjectNode): ObjectNode = node.put("note", "")

            @Name(${slot("ROOT_NAME")})
            @Description(${slot("DESCRIPTION")})
            @AggregateRoot(
                snapshot = ${slot("SNAPSHOT")},
                stateRevision = ${slot("STATE_REVISION")},
                retiredEvents = [Names.RETIRED],
            )
            class Cart(private val state: CartState) {
                fun onCommand(
                    command: Open,
                    @DependencyName(${slot("QUALIFIER")}) audit: Audit,
                ): Opened = Opened(command.id, "")

                @OnCommand(onDeleted = ${slot("ON_DELETED")})
                fun onCommand(command: Close): Closed = Closed(command.id)

                @AfterCommand(include = ${slot("AFTER_INCLUDE")})
                fun afterCommand(command: Any): List<Closed> = emptyList()

                object Errors {
                    @ErrorCode(description = ${slot("ERROR_DESCRIPTION")}, status = ${slot("ERROR_STATUS")})
                    const val FULL: String = "full"
                }
            }

            @JsonIgnoreProperties(${slot("IGNORED_PROPERTIES")})
            class CartState(val id: String) {
                @JsonProperty(${slot("WIRE_NAME")})
                var label: String = ""

                @JsonIgnore(${slot("JSON_IGNORE")})
                var note: String = ""

                var hidden: String = ""

                fun onSourcing(event: Opened) {}

                fun onSourcing(event: Closed) {}
            }

            @Name(${slot("MOUNTED_NAME")})
            @MountedCommand(aggregate = ${slot("MOUNTED_AGGREGATE")})
            data class ImportCart(val id: String)

            @Name(${slot("PROCESSOR_NAME")})
            @Projection
            class CartBoard(@DependencyName(${slot("PROCESSOR_QUALIFIER")}) private val audit: Audit) {
                @Name(${slot("HANDLER_NAME")})
                @OnEvent(aggregates = [Names.QUALIFIED_CART], start = ${slot("START")})
                fun onEvent(event: StateEvent<CartState>) = Unit
            }

            @Saga
            object Nightly {
                @OnSchedule(name = ${slot("JOB_NAME")}, cron = ${slot("JOB_CRON")}, timeZone = ${slot("JOB_TIME_ZONE")})
                @Sends(derives = ${slot("SENDS")})
                @Retry(maxAttempts = ${slot("RETRY_ATTEMPTS")})
                fun nightly(tick: ScheduleTick): List<CommandRequest<Close>> = emptyList()
            }

            data class Count(val value: Int)

            class CartRoutes {
                @ServiceRoute(method = RouteMethod.GET, path = ${slot("ROUTE_PATH")})
                suspend fun count(@RouteQuery(${slot("QUERY_NAME")}) state: String?): Count = Count(0)
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `constants of another module should be the values the model and the fragment take`() {
        val (compilation, result) = compileModelAgainst("generate", options, listOf(NAMES), domain())
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val shop = context(compilation)
        val cart = shop.aggregates.single()
        cart.name.assert().isEqualTo("basket")
        cart.description.assert().isEqualTo("A basket of goods")
        cart.snapshot.enabled.assert().isFalse()
        cart.snapshot.stateRevision.assert().isEqualTo(3)
        cart.retired.assert().containsExactly("Abandoned")
        cart.domainCommands.associate { it.name to it.description }.assert()
            .containsEntry("OpenBasket", null)
            .containsEntry("Close", "Closes the basket")
        cart.domainCommands.single { it.mounted }.name.assert().isEqualTo("ImportBasket")
        val opened = cart.events.single { it.schema.endsWith("sample.Opened") }
        opened.name.assert().isEqualTo("BasketOpened")
        opened.revision.assert().isEqualTo(2)
        opened.aliases.assert().containsExactly("Begun", "Started")
        val error = shop.errors.single()
        error.code.assert().isEqualTo("shop.basket.full")
        error.status.assert().isEqualTo(409)
        error.description.assert().isEqualTo("The basket is full")
        val board = shop.subscriptions.single { it.kind == SubscriptionKind.PROJECTION }
        board.function.processor.assert().isEqualTo("basket-board")
        board.function.name.assert().isEqualTo("onBasket")
        board.start.assert().isEqualTo(SubscriptionStart.END)
        // The module's own aggregate, `shop.basket`, by its name in the context.
        board.source.aggregates.assert().containsExactly("basket")
        val nightly = shop.subscriptions.single { it.job != null }
        nightly.retry?.maxAttempts.assert().isEqualTo(3)
        val job = nightly.job!!
        job.name.assert().isEqualTo("sweep")
        job.cron.assert().isEqualTo("0 */5 * * * *")
        job.timeZone.assert().isEqualTo("Asia/Shanghai")
        val route = shop.routes.single()
        route.route.path.assert().endsWith("counts")
        route.parameters.map { it.name }.assert().containsExactly("status")
        // The suppression names the warning of Open alone.
        result.messages.assert().doesNotContain("command Open has a String property id")
            .contains("command Close has a String property id")
        // Jackson's names: the state's query fields.
        generated(compilation, "CartStateProperties.kt")!!.readText().assert()
            .contains("\"state.tag\"").doesNotContain("\"state.label\"", "\"state.note\"", "\"state.hidden\"")
        // The qualifier names the dependency of the aggregate's model and of the processor's handlers.
        generated(compilation, "ShopDomainModelProvider.kt")!!.readText().assert()
            .contains("dependencies.get(sample.Audit::class.javaObjectType, \"audit\", \"the Cart model\")")
        generated(compilation, "CartBoardSubscriptions.kt")!!.readText().assert()
            .contains("dependencies.get(sample.Audit::class.javaObjectType, \"audit\", \"CartBoard\")")
    }

    /**
     * For each annotation, the arguments that read no constant, and what they fail the compilation with. `@Alias` and
     * `@Suppress` have none: their one argument is an array, and KSP drops an element it evaluates no value for, even a
     * whole array passed to a vararg, rather than leaving it null.
     */
    private val unread = listOf(
        Unread(
            "@Name, on each declaration that takes one",
            mapOf(
                "ROOT_NAME" to "Local.text",
                "COMMAND_NAME" to "Local.text",
                "QUALIFIER" to "Local.text",
                "EVENT_NAME" to "Local.text",
                "MOUNTED_NAME" to "Local.text",
                "PROCESSOR_NAME" to "Local.text",
                "PROCESSOR_QUALIFIER" to "Local.text",
                "HANDLER_NAME" to "Local.text",
            ),
            listOf(
                "@Name of sample.Cart: KSP evaluated no value for its argument value, so the model takes nothing " +
                    "from it; an argument must be a constant KSP can read, a literal or a const val",
                "@Name of sample.Open: KSP evaluated no value for its argument value",
                "@DependencyName of parameter audit of sample.Cart.onCommand: KSP evaluated no value for its argument value",
                "@Name of sample.Opened: KSP evaluated no value for its argument value",
                "@Name of sample.ImportCart: KSP evaluated no value for its argument value",
                "@Name of sample.CartBoard: KSP evaluated no value for its argument value",
                "@DependencyName of parameter audit of sample.CartBoard.<init>: KSP evaluated no value for its argument value",
                "@Name of sample.CartBoard.onEvent: KSP evaluated no value for its argument value",
            ),
            missing = listOf("CartModel.kt", "CartBoardSubscriptions.kt"),
        ),
        Unread(
            "@Revision",
            mapOf("REVISION" to "Local.number"),
            listOf("@Revision of sample.Opened: KSP evaluated no value for its argument value"),
            missing = listOf("CartModel.kt"),
        ),
        Unread(
            "@Description",
            mapOf("DESCRIPTION" to "Local.text"),
            listOf("@Description of sample.Cart: KSP evaluated no value for its argument value"),
            missing = listOf("CartModel.kt"),
        ),
        Unread(
            "@Summary",
            mapOf("SUMMARY" to "Local.text"),
            listOf("@Summary of sample.Close: KSP evaluated no value for its argument value"),
            missing = listOf("CartModel.kt"),
        ),
        Unread(
            "@AggregateRoot",
            mapOf("SNAPSHOT" to "Local.flag", "STATE_REVISION" to "Local.number"),
            listOf(
                "@AggregateRoot of sample.Cart: KSP evaluated no value for its arguments snapshot and stateRevision"
            ),
            missing = listOf("CartModel.kt"),
        ),
        Unread(
            "@ErrorCode",
            mapOf("ERROR_STATUS" to "Local.number"),
            listOf("@ErrorCode of sample.Cart.Errors.FULL: KSP evaluated no value for its argument status"),
        ),
        Unread(
            "@MountedCommand",
            mapOf("MOUNTED_AGGREGATE" to "Local.text"),
            listOf("@MountedCommand of sample.ImportCart: KSP evaluated no value for its argument aggregate"),
        ),
        Unread(
            "@OnEvent",
            mapOf("START" to "Local.start"),
            listOf("@OnEvent of sample.CartBoard.onEvent: KSP evaluated no value for its argument start"),
            missing = listOf("CartBoardSubscriptions.kt"),
        ),
        Unread(
            "@OnSchedule",
            mapOf("JOB_TIME_ZONE" to "Local.text"),
            listOf("@OnSchedule of sample.Nightly.nightly: KSP evaluated no value for its argument timeZone"),
            missing = listOf("NightlySubscriptions.kt"),
        ),
        Unread(
            "@OnCommand",
            mapOf("ON_DELETED" to "Local.flag"),
            listOf("@OnCommand of sample.Cart.onCommand: KSP evaluated no value for its argument onDeleted"),
            missing = listOf("CartModel.kt"),
        ),
        Unread(
            "@AfterCommand",
            mapOf("AFTER_INCLUDE" to "Local.commands"),
            listOf("@AfterCommand of sample.Cart.afterCommand: KSP evaluated no value for its argument include"),
            missing = listOf("CartModel.kt"),
        ),
        Unread(
            "@Sends",
            mapOf("SENDS" to "Local.commands"),
            listOf("@Sends of sample.Nightly.nightly: KSP evaluated no value for its argument derives"),
            missing = listOf("NightlySubscriptions.kt"),
        ),
        Unread(
            "@Retry",
            mapOf("RETRY_ATTEMPTS" to "Local.number"),
            listOf("@Retry of sample.Nightly.nightly: KSP evaluated no value for its argument maxAttempts"),
            missing = listOf("NightlySubscriptions.kt"),
        ),
        Unread(
            "@Upgrade",
            mapOf("UPGRADE_FROM" to "Local.number"),
            listOf("@Upgrade of sample.addNote: KSP evaluated no value for its argument from"),
            missing = listOf("CartModel.kt"),
        ),
        Unread(
            "@ServiceRoute",
            mapOf("ROUTE_PATH" to "Local.text"),
            listOf("@ServiceRoute of sample.CartRoutes.count: KSP evaluated no value for its argument path"),
        ),
        Unread(
            "@RouteQuery",
            mapOf("QUERY_NAME" to "Local.text"),
            listOf(
                "@RouteQuery of parameter state of sample.CartRoutes.count: KSP evaluated no value for its argument name"
            ),
        ),
        Unread(
            "@JsonProperty",
            mapOf("WIRE_NAME" to "Local.text"),
            listOf("@JsonProperty of sample.CartState.label: KSP evaluated no value for its argument value"),
            missing = listOf("CartStateProperties.kt"),
        ),
        Unread(
            "@JsonIgnore",
            mapOf("JSON_IGNORE" to "Local.flag"),
            listOf("@JsonIgnore of sample.CartState.note: KSP evaluated no value for its argument value"),
            missing = listOf("CartStateProperties.kt"),
        ),
        Unread(
            "@JsonIgnoreProperties",
            mapOf("IGNORED_PROPERTIES" to "Names.HIDDEN, ignoreUnknown = Local.flag"),
            listOf("@JsonIgnoreProperties of sample.CartState: KSP evaluated no value for its argument ignoreUnknown"),
            missing = listOf("CartStateProperties.kt"),
        ),
    )

    @TestFactory
    fun `an argument with no value should fail the compilation, naming its declaration and annotation`() =
        unread.map { case ->
            DynamicTest.dynamicTest(case.annotation) {
                val sources = domain(case.arguments)
                val (compilation, result) = compileModelAgainst("generate", options, listOf(NAMES), sources)
                result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
                result.messages.assert().contains(case.errors).doesNotContain("NullPointerException")
                (case.missing + "shop-domain.json").forEach { generated(compilation, it).assert().isNull() }
            }
        }

    @Test
    fun `an array holding an element without a value should be unread`() {
        AnnotationArguments(Alias::class, fakeAnnotation("value" to listOf("Begun", null))).unread.assert()
            .containsExactly("value")
        AnnotationArguments(Alias::class, fakeAnnotation("value" to listOf("Begun"))).run {
            unread.assert().isEmpty()
            strings("value").assert().containsExactly("Begun")
        }
    }

    @Test
    fun `an enum entry should be read by its name, given as its declaration or its type`() {
        val entry = fake<KSClassDeclaration> { if (it == "getSimpleName") fakeName("END") else error(it) }
        AnnotationArguments(OnEvent::class, fakeAnnotation("aggregates" to emptyList<String>(), "start" to entry))
            .enumName("start").assert().isEqualTo("END")
        val type = fake<KSType> { if (it == "getDeclaration") entry else error(it) }
        AnnotationArguments(OnEvent::class, fakeAnnotation("aggregates" to emptyList<String>(), "start" to type))
            .enumName("start").assert().isEqualTo("END")
    }

    @Test
    fun `an argument KSP lists nowhere should be the annotation class's default, a required one unread`() {
        AnnotationArguments(OnEvent::class, fakeAnnotation()).run {
            unread.assert().isEmpty()
            strings("aggregates").assert().isEmpty()
            enumName("start").assert().isEqualTo("DEFAULT")
        }
        AnnotationArguments(Upgrade::class, fakeAnnotation("from" to 1)).unread.assert().containsExactly("event")
    }

    @Test
    fun `a problem should name what it is on, a declaration, a parameter, or else as KSP renders it`() {
        val arguments = AnnotationArguments(Name::class, fakeAnnotation("value" to null))
        arguments.unread.assert().containsExactly("value")
        val file = fake<KSFile> { if (it == "toString") "File: Cart.kt" else error(it) }
        arguments.problem(file).assert().startsWith(
            "@Name of File: Cart.kt: KSP evaluated no value for its argument value, so the model takes nothing from it",
        )
        val label = fakeName("sample.Cart.label")
        val property = fake<KSPropertyDeclaration> { if (it == "getQualifiedName") label else error(it) }
        val getter = fake<KSPropertyGetter> { if (it == "getReceiver") property else error(it) }
        arguments.problem(getter).assert().startsWith("@Name of accessor of sample.Cart.label: KSP evaluated no value")
    }

    /**
     * The arguments that read no constant for the [annotation], the [errors] they fail the compilation with, and the
     * files the processor then generates none of, besides the fragment.
     */
    private data class Unread(
        val annotation: String,
        val arguments: Map<String, String>,
        val errors: List<String>,
        val missing: List<String> = emptyList(),
    )

    /** The one context of the Manifest fragment [compilation] generated. */
    private fun context(compilation: KotlinCompilation): ContextManifest = compilation.workingDir.walkTopDown()
        .single { it.isFile && it.parentFile.invariantSeparatorsPath.endsWith(Manifest.FRAGMENT_DIRECTORY) }
        .let {
            ManifestFragmentFixtures.read(it).contexts.single()
        }

    private fun generated(compilation: KotlinCompilation, name: String): File? =
        compilation.workingDir.walkTopDown().firstOrNull { it.isFile && it.name == name }

    private companion object {
        /** Another module, compiled: the constants the domain's annotations name. */
        val NAMES: File by lazy {
            compileLibrary(
                SourceFile.kotlin(
                    "Names.kt",
                    """
                    package names

                    object Names {
                        const val CART: String = "basket"
                        const val QUALIFIED_CART: String = "shop.basket"
                        const val DESCRIPTION: String = "A basket of goods"
                        const val SNAPSHOT: Boolean = false
                        const val STATE_REVISION: Int = 3
                        const val RETIRED: String = "Abandoned"
                        const val OPEN: String = "OpenBasket"
                        const val AUDIT: String = "audit"
                        const val SUMMARY: String = "Closes the basket"
                        const val OPENED: String = "BasketOpened"
                        const val REVISION: Int = 2
                        const val FORMER: String = "Started"
                        const val FULL: String = "The basket is full"
                        const val STATUS: Int = 409
                        const val IMPORT: String = "ImportBasket"
                        const val BOARD: String = "basket-board"
                        const val JOB: String = "sweep"
                        const val CRON: String = "0 */5 * * * *"
                        const val ZONE: String = "Asia/Shanghai"
                        const val ON_DELETED: Boolean = false
                        const val ATTEMPTS: Int = 3
                        const val FROM: Int = 1
                        const val ROUTE: String = "counts"
                        const val QUERY: String = "status"
                        const val HANDLER: String = "onBasket"
                        const val UNMARKED: String = "UnmarkedAggregateId"
                        const val WIRE: String = "tag"
                        const val IGNORED: Boolean = true
                        const val HIDDEN: String = "hidden"
                    }
                    """.trimIndent(),
                ),
            )
        }
    }
}
