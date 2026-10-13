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

package me.ahoo.wow.runtime.admission

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.TransformedFrom
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.kernel.InMemoryEventLog
import me.ahoo.wow.kernel.InMemoryReservationStore
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.FrameworkCodes
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/** Command transformation at admission (decision D182): the order of its steps, its checks and its provenance. */
class CommandTransformerTest {
    data class Register(val username: String)
    data class ChangePassword(val username: String, val password: String)
    data class Import(val username: String, val legacyId: String)
    data class Callback(val user: String, val event: String)
    data class Note(val text: String = "")
    data class Registered(val username: String)
    data class PasswordChanged(val password: String)

    class Account {
        var password: String? = null
    }

    /** The commands the decide functions ran. */
    private val decided = CopyOnWriteArrayList<Command<*>>()

    private val account: AggregateModel<Account> = aggregate(CONTEXT, "account") {
        initial { Account() }
        decideSuspending<Register>(Creation.REQUIRED) { _, body ->
            decided += command
            check(reserve(USERNAMES, body.username)) { "${body.username} is taken." }
            Decision.events(Registered(body.username))
        }
        decide<ChangePassword> { _, body ->
            decided += command
            Decision.events(PasswordChanged(body.password))
        }
        mount<Import>()
        mount<Callback>(name = "$type.ProviderCallback")
        mount<Note>()
        evolve<Registered> { }
        evolve<PasswordChanged> { password = it.password }
    }

    private val reservations = InMemoryReservationStore()
    private val waits = WaitRegistry()
    private val origin = Origin("node-1", "127.0.0.1:9090", "shop")
    private val coordinator = WaitCoordinator(
        Executor(Kernel(InMemoryEventLog(partitions = 2), reservations = reservations)),
        waits,
        SubscriptionDirectory.NONE,
        LocalSignalSender(origin.node, waits),
        WaitOptions(origin, maxTimeout = 30.seconds),
    )

    private val register = account.commandType(Register::class.java)
    private val changePassword = account.commandType(ChangePassword::class.java)
    private val import = account.findMountedCommandType("$TYPE.Import")!!
    private val callback = account.findMountedCommandType("$TYPE.ProviderCallback")!!
    private val note = account.findMountedCommandType("$TYPE.Note")!!

    /** Changes a password by user name: the type stays, the aggregate is the user name's holder. */
    private val byUsername = ReservationTargetResolver(changePassword, TYPE, USERNAMES, reservations) { input ->
        (input.body as ChangePassword).username
    }

    /** Imports an account: a mounted command that becomes the creation command, the id generated. */
    private val importer = transformer(import) { input ->
        CommandTarget(register, null, Register((input.body as Import).username), input.headers + ("Source" to "v9"))
    }

    /** Adapts a provider's callback: a mounted command that becomes a command of the account its body names. */
    private val callbacks = transformer(callback) { input ->
        val body = input.body as Callback
        CommandTarget(changePassword, body.user, ChangePassword(body.user, body.event), input.headers)
    }

    /** Mounted, and becomes another mounted command, which no aggregate decides. */
    private val notes = transformer(note) { CommandTarget(import, "x", Import("u", "1")) }

    private val actions = CopyOnWriteArrayList<CommandAction>()

    private fun gateway(
        transformers: List<CommandTransformer> = listOf(byUsername, importer, callbacks, notes),
        authorizer: CommandAuthorizer = CommandAuthorizer.ALLOW_ALL,
        models: List<AggregateModel<*>> = listOf(account),
    ) = CommandGateway(
        ModelRegistry(models),
        mapOf(CONTEXT to coordinator),
        authorizer = CommandAuthorizer { caller, action ->
            actions += action
            authorizer.authorize(caller, action)
        },
        transformers = transformers,
    )

    private fun request(command: String, body: String, aggregateId: String? = null, requestId: String? = null) =
        CommandRequest(
            context = CONTEXT,
            aggregate = "account",
            command = command,
            body = CommandBody.of(body),
            caller = alice,
            aggregateId = aggregateId,
            requestId = requestId,
            wait = WaitPlan.COMMITTED,
        )

    private suspend fun CommandGateway.registered(username: String): CommandResult =
        execute(request("Register", """{"username":"$username"}"""))
            .also { it.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED) }

    @Test
    fun `a password is changed by user name, only the aggregate found, and the input is recorded`(): Unit =
        runBlocking {
            val gateway = gateway()
            val registered = gateway.registered("alice")
            actions.clear()
            decided.clear()
            val changed = gateway.execute(
                request("ChangePassword", """{"username":"alice","password":"s3cret"}""", requestId = "req-1"),
            )
            changed.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            changed.ref.assert().isEqualTo(registered.ref)
            changed.requestId.assert().isEqualTo("req-1")
            changed.transformedFrom.assert().isEqualTo(TransformedFrom("$TYPE.ChangePassword"))
            decided.single().headers.assert()
                .isEqualTo(mapOf(CommandTransformer.TRANSFORMED_FROM to "$TYPE.ChangePassword"))
            // Authorized as it arrived, without an aggregate, then again on the aggregate found.
            actions.map { it.ref.id }.assert().containsExactly(CommandGateway.UNADDRESSED, registered.ref.id)
            actions.map { it.type }.assert().containsExactly(changePassword, changePassword)
        }

    @Test
    fun `a user name no one holds is not found, and nothing runs`(): Unit = runBlocking {
        val result = gateway().execute(request("ChangePassword", """{"username":"nobody","password":"x"}"""))
        result.status.assert().isEqualTo(CommandResult.Status.REJECTED)
        result.error!!.code.assert().isEqualTo(FrameworkCodes.NOT_FOUND)
        result.ref.id.assert().isEqualTo(CommandGateway.UNADDRESSED)
        result.transformedFrom.assert().isEqualTo(TransformedFrom("$TYPE.ChangePassword"))
        decided.assert().isEmpty()
    }

    @Test
    fun `a request that names its aggregate keeps it, and is authorized once`(): Unit = runBlocking {
        val gateway = gateway()
        val registered = gateway.registered("bob")
        actions.clear()
        val result = gateway.execute(
            request("ChangePassword", """{"username":"someone-else","password":"x"}""", registered.ref.id),
        )
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        result.ref.assert().isEqualTo(registered.ref)
        result.transformedFrom.assert().isEqualTo(TransformedFrom("$TYPE.ChangePassword", registered.ref.id))
        actions.size.assert().isEqualTo(1)
    }

    @Test
    fun `a mounted import becomes the creation command, with a generated id`(): Unit = runBlocking {
        val result = gateway().execute(request("Import", """{"username":"carol","legacyId":"9"}"""))
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        result.function.name.assert().isEqualTo("Register")
        result.ref.id.assert().isNotEqualTo(CommandGateway.UNADDRESSED)
        result.transformedFrom.assert().isEqualTo(TransformedFrom("$TYPE.Import"))
        val command = decided.single()
        command.body.assert().isEqualTo(Register("carol"))
        command.headers.assert().isEqualTo(mapOf("Source" to "v9", CommandTransformer.TRANSFORMED_FROM to "$TYPE.Import"))
        // The mounted input is authorized as no creation; the creation command it becomes as one.
        actions.map { it.type to it.creation }.assert().containsExactly(
            import to Creation.NONE,
            register to Creation.REQUIRED,
        )
    }

    @Test
    fun `a callback adapter sends the provider's call to the aggregate it names`(): Unit = runBlocking {
        val gateway = gateway()
        val registered = gateway.registered("dave")
        val result = gateway.execute(
            request("ProviderCallback", """{"user":"${registered.ref.id}","event":"rotated"}""", aggregateId = null),
        )
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        result.ref.assert().isEqualTo(registered.ref)
        result.transformedFrom.assert().isEqualTo(TransformedFrom("$TYPE.ProviderCallback"))
        gateway.transforms(TYPE, "ProviderCallback").assert().isTrue()
        gateway.transforms(TYPE, "$TYPE.ChangePassword").assert().isTrue()
        gateway.transforms(TYPE, "Register").assert().isFalse()
        gateway.transforms(AggregateType(CONTEXT, "other"), "Register").assert().isFalse()
    }

    @Test
    fun `the result is authorized again when its type or aggregate changed`(): Unit = runBlocking {
        val noCreation = CommandAuthorizer { _, action ->
            if (action.creation == Creation.NONE) Authorization.Allowed else Authorization.Denied("No creation.")
        }
        val result = gateway(authorizer = noCreation)
            .execute(request("Import", """{"username":"erin","legacyId":"1"}"""))
        result.status.assert().isEqualTo(CommandResult.Status.REJECTED)
        result.error!!.code.assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
        result.function.name.assert().isEqualTo("Register")
        result.transformedFrom.assert().isEqualTo(TransformedFrom("$TYPE.Import"))
        decided.assert().isEmpty()
    }

    @Test
    fun `the input is authorized before it is decoded or transformed`(): Unit = runBlocking {
        val noImports = CommandAuthorizer { _, action ->
            if (action.type == import) Authorization.Denied("No imports.") else Authorization.Allowed
        }
        val result = gateway(authorizer = noImports).execute(request("Import", "not json"))
        result.error!!.code.assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
        result.transformedFrom.assert().isNull()
    }

    @Test
    fun `a result no aggregate decides is unhandled`(): Unit = runBlocking {
        val result = gateway().execute(request("Note", "{}"))
        result.status.assert().isEqualTo(CommandResult.Status.REJECTED)
        result.error!!.code.assert().isEqualTo(FrameworkCodes.UNHANDLED)
    }

    @Test
    fun `without a transformer, a command that creates nothing still needs an aggregate id`(): Unit = runBlocking {
        val result = gateway(transformers = listOf(importer, callbacks, notes))
            .execute(request("ChangePassword", """{"username":"alice","password":"x"}"""))
        result.error!!.code.assert().isEqualTo(FrameworkCodes.MALFORMED)
    }

    @Test
    fun `an ambiguous or unclaimed setup fails when the gateway is built`() {
        fun fails(transformers: List<CommandTransformer>) {
            runCatching { gateway(transformers = transformers) }.exceptionOrNull()
                .assert().isInstanceOf(IllegalArgumentException::class.java)
        }
        // Two transformers of one input type.
        fails(listOf(byUsername, importer, callbacks, notes, transformer(import) { error("unused") }))
        // A mounted command no transformer claims.
        fails(listOf(byUsername, importer, callbacks))
        // An input no aggregate served here decides or mounts, in a context served here.
        fails(listOf(importer, callbacks, notes, transformer(CommandType("$CONTEXT.other.Thing", Note::class.java)) {
            error("unused")
        }))
        // An input of a served name but another class.
        fails(listOf(importer, callbacks, notes, transformer(CommandType(register.name, Note::class.java)) {
            error("unused")
        }))
        // An input of a context this node does not serve is left to the nodes that serve it.
        gateway(
            transformers = listOf(
                importer,
                callbacks,
                notes,
                transformer(CommandType("billing.invoice.Pay", Note::class.java)) { error("unused") },
            ),
        )
    }

    @Test
    fun `a target checks its body, its id and its headers`() {
        listOf(
            { CommandTarget(register, null, Note()) },
            { CommandTarget(register, " ", Register("x")) },
            { CommandTarget(register, null, Register("x"), mapOf("Wow-Scope" to "owner=\"bob\"")) },
            { CommandTarget(register, null, Register("x"), mapOf("wow.transformed-from" to "forged")) },
            { ReservationTargetResolver(register, TYPE, " ", reservations) { null } },
            { TransformedFrom(" ") },
            { TransformedFrom("a.b.C", " ") },
        ).forEach { make ->
            runCatching { make() }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `the resolver finds only an aggregate of its type`(): Unit = runBlocking {
        reservations.reserve(USERNAMES, "frank", "$CONTEXT.other/$TENANT/x", 10.seconds)
        reservations.reserve(USERNAMES, "grace", "$TYPE/$TENANT/", 10.seconds)
        val input = CommandInput(changePassword, ChangePassword("frank", "x"), null, emptyMap(), "r", alice)
        byUsername.transform(input).aggregateId.assert().isNull()
        byUsername.transform(input.copy(body = ChangePassword("grace", "x"))).aggregateId.assert().isNull()
        val held = reservations.reserve(USERNAMES, "heidi", "$TYPE/$TENANT/acc-1", 10.seconds)
        held.assert().isNotNull()
        byUsername.transform(input.copy(body = ChangePassword("heidi", "x"))).aggregateId.assert().isEqualTo("acc-1")
    }

    private fun transformer(input: CommandType, transform: suspend (CommandInput) -> CommandTarget) =
        object : CommandTransformer {
            override val input: CommandType = input

            override suspend fun transform(input: CommandInput): CommandTarget = transform(input)
        }

    private companion object {
        const val CONTEXT = "shop"
        const val TENANT = "acme"
        const val USERNAMES = "shop.account.username"
        val TYPE = AggregateType(CONTEXT, "account")
        val alice = Caller(TENANT, Scope("alice"), subject = "alice")
    }
}
