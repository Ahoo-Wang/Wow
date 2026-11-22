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
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.lifecycle.TransferOwner
import me.ahoo.wow.api.lifecycle.TransferSpace
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.TransformedFrom
import me.ahoo.wow.api.result.Violation
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What admission refuses or reshapes before a command runs: identity sources, the authorizer, bodies that do not
 * decode or carry NUL characters, ids over 256 characters, unhandled types, validators and transformers.
 */
class CommandGatewayRefusalsTest : CommandGatewayFixtures() {
    @Test
    fun `conflicting or missing identity sources are refused as malformed`(): Unit = runBlocking {
        val gateway = gateway()
        gateway.execute(request(headers = mapOf("Wow-Tenant-Id" to "other")))
            .assertRefused(FrameworkCodes.COMMAND_MALFORMED)
        gateway.execute(request(headers = mapOf("wow.owner" to "bob"))).assertRefused(FrameworkCodes.COMMAND_MALFORMED)
        // A rejection's stream records its rejection under wow.rejection.*: no request may set it.
        gateway.execute(request(headers = mapOf(EventStreamRecord.REJECTION_CODE to "forged")))
            .assertRefused(FrameworkCodes.COMMAND_MALFORMED)
        gateway.execute(request(requestId = " ")).assertRefused(FrameworkCodes.COMMAND_MALFORMED)
        gateway.execute(request(aggregateId = " ")).assertRefused(FrameworkCodes.COMMAND_MALFORMED)
        // "(none)" stands for an id not chosen yet: no caller names it.
        gateway.execute(request(aggregateId = AggregateRef.UNASSIGNED)).assertRefused(FrameworkCodes.COMMAND_MALFORMED)
        gateway.execute(request().copyWith(expectedVersion = -1)).assertRefused(FrameworkCodes.COMMAND_MALFORMED)
        val missing = gateway.execute(request(command = "Checkout", body = "{}", aggregateId = null))
        missing.assertRefused(FrameworkCodes.COMMAND_MALFORMED)
        missing.ref.id.assert().isEqualTo(CommandGateway.UNADDRESSED)
        decided.assert().isEmpty()
        // A request fact outside the framework's namespace travels with the command.
        gateway.execute(request(headers = mapOf("CoSec-App-Id" to "app"))).status
            .assert().isEqualTo(CommandResult.Status.SUCCEEDED)
    }

    @Test
    fun `the authorizer sees the action before the body is decoded`(): Unit = runBlocking {
        val actions = CopyOnWriteArrayList<CommandAction>()
        val denying = CommandAuthorizer { caller, action ->
            actions += action
            if (caller == bob) Authorization.Denied("Bob may not add items.") else Authorization.Allowed
        }
        val gateway = gateway(authorizer = denying)
        gateway.execute(request(caller = bob, body = "{not json")).assertRefused(FrameworkCodes.AUTH_FORBIDDEN)
        actions.single().also {
            it.type.name.assert().isEqualTo("shop.cart.AddItem")
            it.ref.assert().isEqualTo(model.type.ref(TENANT, "cart-1"))
            it.scope.assert().isEqualTo(bob.scope)
            it.creation.assert().isEqualTo(Creation.ALLOWED)
        }
        val coded = gateway(authorizer = { _, _ -> Authorization.Denied("No.", "shop.cart.closed") })
        coded.execute(request()).assertRefused("shop.cart.closed")
        decided.assert().isEmpty()
    }

    @Test
    fun `a gateway without an authorizer allows every action and says it has none`(): Unit = runBlocking {
        val bare = CommandGateway(
            ModelRegistry(listOf(model)),
            mapOf(CONTEXT to coordinator),
            committed = CommittedRequests.NONE,
        )
        bare.authorizes.assert().isFalse()
        bare.execute(request()).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        // An authorizer given, even one that allows everything, is the node's: the public port leaves it to decide.
        gateway(authorizer = CommandAuthorizer.ALLOW_ALL).authorizes.assert().isTrue()
    }

    @Test
    fun `a body that does not decode as the command is malformed, naming the property and why`(): Unit = runBlocking {
        val gateway = gateway()
        suspend fun malformed(body: String, vararg violations: Violation) = gateway.execute(request(body = body)).run {
            assertRefused(FrameworkCodes.COMMAND_MALFORMED)
            error!!.message.assert().isEqualTo("The body is not a $CONTEXT.cart.AddItem.")
            error!!.violations.assert().describedAs(body).containsExactly(*violations)
        }
        // Not JSON, or no value: nothing to name.
        malformed("{not json")
        malformed("null")
        malformed("""{"quantity":2}""", Violation.of("sku", "wrong-kind", "A value is required, given none."))
        malformed(
            """{"sku":"apple","quantity":"many"}""",
            Violation.of("quantity", "wrong-kind", "Expected an integer, given a string."),
        )
        malformed(
            """{"sku":"apple","quantity":1.5}""",
            Violation.of("quantity", "wrong-kind", "Expected an integer, given a number."),
        )
        malformed(
            """{"sku":"apple","quantity":2147483648}""",
            Violation.of("quantity", "out-of-range", "Out of range: -2147483648 to 2147483647."),
        )
        // A property the command does not have is refused, a typo of an optional one first: it would take its default.
        malformed(
            """{"sku":"apple","Quantity":5}""",
            Violation.of("Quantity", "unknown-property", "Not a property of its type."),
        )
        // A property given twice is refused where a plain read keeps the last.
        malformed(
            """{"sku":"apple","quantity":1,"sku":"pear"}""",
            Violation.of("sku", "duplicate-property", "Given more than once in its object."),
        )
        malformed(
            """["apple"]""",
            Violation.of("", "invalid-format", "Not a valid value of its type, given an array."),
        )
        // A tree is read as its text is.
        val quantity = JsonMapper.builder().build().readTree("""{"sku":"pear","quantity":true}""")
        gateway.execute(request().copyWith(body = CommandBody.Tree(quantity))).error!!.violations.assert()
            .containsExactly(Violation.of("quantity", "wrong-kind", "Expected an integer, given a boolean."))
        decided.assert().isEmpty()
        val tree = JsonMapper.builder().build().readTree("""{"sku":"pear","quantity":3}""")
        gateway.execute(request().copyWith(body = CommandBody.Tree(tree))).status
            .assert().isEqualTo(CommandResult.Status.SUCCEEDED)
    }

    @Test
    fun `a command's getter-only collection should be filled from its body`(): Unit = runBlocking {
        gateway().execute(request("AddSkus", """{"skus":["apple","pear"]}""", wait = WaitPlan.COMMITTED)).status
            .assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        skus.assert().containsExactly("apple", "pear")
    }

    @Test
    fun `a body that writes a NUL character in a string or a key is malformed`(): Unit = runBlocking {
        // Finding A2-7: PostgreSQL's jsonb refuses it and MongoDB keeps it; no backend sees it now.
        val gateway = gateway()
        gateway.execute(request(body = """{"sku":"a\u0000b"}""")).assertRefused(FrameworkCodes.COMMAND_MALFORMED)
        gateway.execute(
            request(body = """{"sku":"apple","x\u0000":1}""")
        ).assertRefused(FrameworkCodes.COMMAND_MALFORMED)
        val mapper = JsonMapper.builder().build()
        val nested = mapper.readTree("""{"sku":"apple","tags":[{"k":"\u0000"}]}""")
        gateway.execute(
            request().copyWith(body = CommandBody.Tree(nested))
        ).assertRefused(FrameworkCodes.COMMAND_MALFORMED)
        val key = mapper.readTree("""{"sku":"apple","meta":{"\u0000":1}}""")
        gateway.execute(
            request().copyWith(body = CommandBody.Tree(key))
        ).assertRefused(FrameworkCodes.COMMAND_MALFORMED)
        decided.assert().isEmpty()
        // An escaped backslash before u0000 is the text "\u0000", not the character.
        gateway.execute(request(body = """{"sku":"a\\u0000b"}""", wait = WaitPlan.COMMITTED)).status
            .assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        val text = mapper.readTree("""{"sku":"a\\u0000b","quantity":1}""")
        gateway.execute(request(wait = WaitPlan.COMMITTED).copyWith(body = CommandBody.Tree(text))).status
            .assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        AdmissionLimits.writesNul("\\u000".toByteArray()).assert().isFalse()
    }

    @Test
    fun `an id longer than 256 characters is malformed`(): Unit = runBlocking {
        // Finding A3-10: PostgreSQL's index refused it, and the append was answered as unknown, retried for ever.
        val gateway = gateway()
        val long = "x".repeat(AdmissionLimits.MAX_ID_LENGTH + 1)
        val requestId = gateway.execute(request(requestId = long))
        requestId.assertRefused(FrameworkCodes.COMMAND_MALFORMED)
        // Each message names its id, the sentence reading alike whatever the name.
        requestId.error!!.message.assert().isEqualTo("The request id is at most 256 characters; this one has 257.")
        gateway.execute(request(aggregateId = long)).error!!.message.assert()
            .isEqualTo("The aggregate id is at most 256 characters; this one has 257.")
        val tenant = Caller(long, Scope("eve"), subject = "eve")
        gateway.execute(request(caller = tenant)).error!!.message.assert()
            .isEqualTo("The tenant id is at most 256 characters; this one has 257.")
        val space = Caller(TENANT, Scope("eve", long), subject = "eve")
        gateway.execute(request(caller = space)).error!!.message.assert()
            .isEqualTo("The space id is at most 256 characters; this one has 257.")
        val owner = Caller(TENANT, Scope(long, "home"), subject = "eve")
        gateway.execute(request(caller = owner)).error!!.message.assert()
            .isEqualTo("The owner id is at most 256 characters; this one has 257.")
        decided.assert().isEmpty()
        val longest = "y".repeat(AdmissionLimits.MAX_ID_LENGTH)
        gateway.execute(request(requestId = longest, aggregateId = longest, wait = WaitPlan.COMMITTED)).status
            .assert().isEqualTo(CommandResult.Status.SUCCEEDED)
    }

    @Test
    fun `an aggregate id no store keeps, or no route reaches behind a gateway, is malformed`(): Unit = runBlocking {
        // #6501: each was accepted and stored, NUL and the white space as another aggregate than the one without it.
        // #6594: and a '/', ';' or '\', or more than 256 characters percent-encoded, which CoSec's path check refuses.
        val gateway = gateway()
        mapOf(
            "a\u0000b" to "control character",
            "a\u007fb" to "control character",
            "a\u0085b" to "control character",
            " c-1" to "white space",
            "c-1\u00a0" to "white space",
            "." to "relative path segment",
            ".." to "relative path segment",
            "a/b" to "holds '/'",
            "a;b" to "holds ';'",
            "a\\b" to "holds '\\'",
            "购".repeat(29) to "261 characters percent-encoded",
        ).forEach { (id, why) ->
            val refused = gateway.execute(request(aggregateId = id))
            refused.assertRefused(FrameworkCodes.COMMAND_MALFORMED)
            refused.error!!.message.assert().contains(why)
        }
        decided.assert().isEmpty()
        // A dot inside an id, a space within it, a unicode id, and the other characters a path encodes are ids of their
        // own, up to 256 characters encoded.
        listOf("a.b", "...", "a b", "购", "a%b", "q?m", "h#t", "p+s", "购".repeat(28)).forEach { id ->
            gateway.execute(request(aggregateId = id, wait = WaitPlan.COMMITTED)).status
                .assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        }
    }

    @Test
    fun `a request's ids are checked before the creation lookup reads the store for its request id`(): Unit =
        runBlocking {
            val looked = CopyOnWriteArrayList<String>()
            val committed = CommittedRequests.of(
                object : EventLog by log {
                    override suspend fun findByRequestId(
                        type: AggregateType,
                        tenantId: String,
                        requestId: String,
                    ): EventStreamRecord? = null.also { looked += requestId }
                },
            )
            val long = "x".repeat(AdmissionLimits.MAX_ID_LENGTH + 1)
            gateway(committed = committed).execute(request(aggregateId = null, requestId = long))
                .assertRefused(FrameworkCodes.COMMAND_MALFORMED)
            looked.assert().isEmpty()
        }

    @Test
    fun `the owner or space a lifecycle command transfers to is held to the ids' length`(): Unit = runBlocking {
        val long = "o".repeat(AdmissionLimits.MAX_ID_LENGTH + 1)
        val transfer = gateway().execute(request(command = Lifecycle.TRANSFER_OWNER, body = """{"ownerId":"$long"}"""))
        transfer.assertRefused(FrameworkCodes.COMMAND_MALFORMED)
        transfer.error!!.message.assert().isEqualTo("The owner id is at most 256 characters; this one has 257.")
        AdmissionLimits.oversizedBody(TransferOwner(long)).assert()
            .isEqualTo("The owner id is at most 256 characters; this one has 257.")
        AdmissionLimits.oversizedBody(TransferSpace(long)).assert()
            .isEqualTo("The space id is at most 256 characters; this one has 257.")
        AdmissionLimits.oversizedBody(TransferOwner("o".repeat(AdmissionLimits.MAX_ID_LENGTH))).assert().isNull()
        AdmissionLimits.oversizedBody(AddItem("apple")).assert().isNull()
    }

    @Test
    fun `a transformer that names an aggregate id longer than 256 characters is malformed`(): Unit = runBlocking {
        val long = object : CommandTransformer {
            override val input = model.commandType(AddItem::class.java)

            override suspend fun transform(input: CommandInput): CommandTarget =
                CommandTarget(input.type, "z".repeat(AdmissionLimits.MAX_ID_LENGTH + 1), input.body, input.headers)
        }
        gateway(transformers = listOf(long)).execute(request()).assertRefused(FrameworkCodes.COMMAND_MALFORMED)
        decided.assert().isEmpty()
    }

    @Test
    fun `an unknown aggregate or command is unhandled`(): Unit = runBlocking {
        val gateway = gateway()
        gateway.execute(request(command = "RemoveItem")).assertRefused(FrameworkCodes.COMMAND_UNHANDLED)
        val unknown = gateway.execute(request().copyWith(aggregate = "wishlist", aggregateId = null))
        unknown.assertRefused(FrameworkCodes.COMMAND_UNHANDLED)
        unknown.ref.id.assert().isEqualTo(CommandGateway.UNADDRESSED)
        unknown.function.name.assert().isEqualTo("AddItem")
        gateway.execute(request(command = "Remove.")).function.name.assert().isEqualTo("Remove.")
        // The full model name resolves as well as the short one.
        gateway.execute(
            request(command = "shop.cart.AddItem")
        ).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
    }

    @Test
    fun `validators' violations refuse the command together`(): Unit = runBlocking {
        val positive = CommandValidator { command ->
            val body = command.body
            if ((body as AddItem).quantity > 0) emptyList() else listOf(Violation("quantity", "min", "At least 1."))
        }
        val known = CommandValidator { command ->
            val body = command.body
            if ((body as AddItem).sku == "apple") emptyList() else listOf(Violation("sku", "Known", "Unknown sku."))
        }
        val result = gateway(validators = listOf(positive, known))
            .execute(request(body = """{"sku":"kiwi","quantity":0}"""))
        result.assertRefused(FrameworkCodes.COMMAND_INVALID)
        result.error!!.violations.map { it.path }.assert().containsExactly("quantity", "sku")
        decided.assert().isEmpty()
    }

    @Test
    fun `a transformer changes the body and headers, the result is validated, and the wait is capped`(): Unit =
        runBlocking {
            val defaults = object : CommandTransformer {
                override val input = model.commandType(AddItem::class.java)

                override suspend fun transform(input: CommandInput): CommandTarget = CommandTarget(
                    input.type,
                    input.aggregateId,
                    (input.body as AddItem).copy(quantity = 5),
                    input.headers + ("App" to "web"),
                )
            }
            val validator = CommandValidator { command ->
                val body = command.body
                if ((body as AddItem).quantity == 5) emptyList() else listOf(Violation("quantity", "Five", "Not 5."))
            }
            val result = gateway(transformers = listOf(defaults), validators = listOf(validator)).execute(
                request(body = """{"sku":"apple","quantity":0}""", wait = WaitPlan.COMMITTED.within(10.minutes)),
            )
            result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            result.transformedFrom.assert().isEqualTo(TransformedFrom("shop.cart.AddItem", "cart-1"))
            commands.single().headers.assert().isEqualTo(
                mapOf(
                    "App" to "web",
                    CommandTransformer.TRANSFORMED_FROM to "shop.cart.AddItem",
                    CommandTransformer.TRANSFORMED_FROM_ID to "cart-1",
                ),
            )
            // The wait is capped by the coordinator's maximum when the command is built.
            commands.single().wait.timeout.assert().isEqualTo(30.seconds)
        }
}
