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

package me.ahoo.wow.example.shop.server

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.example.shop.api.ExampleContext
import me.ahoo.wow.example.shop.domain.customer.CustomerRejections
import me.ahoo.wow.http.WowHeaders
import me.ahoo.wow.runtime.FrameworkCodes
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.runtime.admission.CommandTransformer
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spring.boot.HostBackends
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.annotation.Bean
import java.math.BigDecimal
import java.util.UUID

/**
 * Command transformation through the running shop server (decision D182): a password changed by user name, a customer
 * imported through a mounted command, and a payment provider's callback adapted into the order's payment.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransformationEndToEndTest {
    private val shop = startShop()

    @AfterAll
    fun close() {
        shop.close()
    }

    /** A user name no other test takes. */
    private fun username(): String = "user-" + UUID.randomUUID()

    private fun register(username: String, user: String = ALICE): String {
        val registered = shop.post(
            "/example/customer/register-customer",
            """{"username":"$username","password":"first"}""",
            user,
        )
        registered.status.assert().isEqualTo(200)
        return registered.json["ref"]["id"].asString()
    }

    private fun passwordHash(id: String, user: String = ALICE): String =
        shop.get("/example/customer/$id/state", user).json["state"]["passwordHash"].asString()

    /** The event streams of the customer or order [id] in the log. */
    private fun streams(aggregate: String, id: String): List<EventStreamRecord> = runBlocking {
        val log = shop.context.getBean(HostBackends::class.java).contexts.getValue(ExampleContext.NAME).log
        log.read(AggregateType(ExampleContext.NAME, aggregate).ref(Caller.DEFAULT_TENANT, id)).toList()
    }

    @Test
    fun `a password is changed by user name, the transformer finding the customer that holds it`() {
        val username = username()
        val id = register(username)
        val before = passwordHash(id)

        val changed = shop.post(
            "/example/customer/change-password",
            """{"username":"$username","password":"second"}""",
            ALICE,
        )
        changed.status.assert().isEqualTo(200)
        changed.json["ref"]["id"].asString().assert().isEqualTo(id)
        changed.json["transformedFrom"]["command"].asString().assert().isEqualTo("example.customer.ChangePassword")
        passwordHash(id).assert().isNotEqualTo(before)
        streams(ExampleContext.CUSTOMER, id).last().headers[CommandTransformer.TRANSFORMED_FROM]
            .assert().isEqualTo("example.customer.ChangePassword")

        // The customer found is still guarded by its scope (decision D94): bob may not change alice's password.
        shop.post("/example/customer/change-password", """{"username":"$username","password":"x"}""", BOB)
            .assertProblem(403, FrameworkCodes.AGGREGATE_FORBIDDEN)
        // A user name no customer holds finds none.
        val missing = shop.post(
            "/example/customer/change-password",
            """{"username":"${username()}","password":"x"}""",
            ALICE,
        ).assertProblem(404, FrameworkCodes.NOT_FOUND)
        missing.json["result"]["transformedFrom"]["command"].asString()
            .assert().isEqualTo("example.customer.ChangePassword")
        // A user name taken is the customer's own rejection.
        shop.post("/example/customer/register-customer", """{"username":"$username","password":"x"}""", BOB)
            .assertProblem(409, CustomerRejections.USERNAME_TAKEN)
    }

    @Test
    fun `a customer of the former system is imported through the mounted command, and is found by its name`() {
        val username = username()
        val imported = shop.post(
            "/example/customer/import-customer",
            """{"username":"$username","password":"legacy","legacyId":"v9-42"}""",
            ALICE,
        )
        imported.status.assert().isEqualTo(200)
        imported.json["transformedFrom"]["command"].asString().assert().isEqualTo("example.customer.ImportCustomer")
        val id = imported.json["ref"]["id"].asString()
        shop.get("/example/customer/$id/state", ALICE).json["state"]["username"].asString()
            .assert().isEqualTo(username)
        val stream = streams(ExampleContext.CUSTOMER, id).single()
        stream.commandType.assert().isEqualTo("example.customer.RegisterCustomer")
        stream.headers.assert().isEqualTo(
            mapOf(
                CustomerImporter.LEGACY_ID to "v9-42",
                CommandTransformer.TRANSFORMED_FROM to "example.customer.ImportCustomer",
            ),
        )

        shop.post("/example/customer/change-password", """{"username":"$username","password":"new"}""", ALICE)
            .status.assert().isEqualTo(200)
    }

    @Test
    fun `a payment provider's callback pays the order it names`() {
        val created = shop.post(
            "/example/order/create-order",
            """{"items":$ONE_PEAR,"address":$ADDRESS}""",
            ALICE,
        )
        created.status.assert().isEqualTo(200)
        val order = created.json["ref"]["id"].asString()

        // The provider is a principal of its own that acts across scopes: it names the tenant, not the owner.
        val paid = shop.post(
            "/example/order/payment-notified",
            """{"merchantOrderId":"$order","transactionId":"tx-1","amount":4.00}""",
            PROVIDER,
            headers = mapOf(HeaderCallers.ADMIN to "true", WowHeaders.SCOPE to "tenant=\"${Caller.DEFAULT_TENANT}\""),
        )
        paid.status.assert().isEqualTo(200)
        paid.json["ref"]["id"].asString().assert().isEqualTo(order)
        paid.json["transformedFrom"]["command"].asString().assert().isEqualTo("example.order.PaymentNotified")
        val state = shop.get("/example/order/$order/state", ALICE).json["state"]
        state["status"].asString().assert().isEqualTo("PAID")
        state["paidAmount"].decimalValue().assert().isEqualByComparingTo(BigDecimal("4.00"))
        streams(ExampleContext.ORDER, order).last().headers[CommandTransformer.TRANSFORMED_FROM]
            .assert().isEqualTo("example.order.PaymentNotified")
    }

    @Test
    fun `two transformers of one command type stop the server from starting`() {
        val failure = runCatching { startShop(sources = listOf(SecondImporter::class.java)).close() }.exceptionOrNull()
        checkNotNull(failure) { "The shop started." }
        generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString(" | ").assert()
            .contains("More than one transformer takes example.customer.ImportCustomer")
    }

    /** A second transformer of the import, beside the shop's; passed as [PaymentsOnlyFromTheProvider] is. */
    class SecondImporter {
        @Bean
        fun secondImporter(): CommandTransformer = CustomerImporter()
    }

    private companion object {
        const val ALICE = "alice"
        const val BOB = "bob"
    }
}

/** The payment provider: a principal of its own that acts across scopes. */
internal const val PROVIDER = "payment-provider"

/** An order of one pear, at the catalog's price. */
internal const val ONE_PEAR = """[{"productId":"pear","price":4.00,"quantity":1}]"""

private const val ADDRESS =
    """{"country":"China","province":"Shanghai","city":"Shanghai","district":"Pudong","detail":"1 Century Avenue"}"""

/**
 * The command a transformer makes is authorized again (decision D182), through a shop server of its own whose
 * authorizer lets only the payment provider pay an order: a mounted command cannot widen the caller's rights.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransformationAuthorizationTest {
    private val guarded = startShop(sources = listOf(PaymentsOnlyFromTheProvider::class.java))

    @AfterAll
    fun close() {
        guarded.close()
    }

    @Test
    fun `the command a transformer makes is authorized again, so a mounted command cannot widen the caller's rights`() {
        val created = guarded.post(
            "/example/order/create-order",
            """{"items":$ONE_PEAR,"address":$ADDRESS}""",
            "alice",
        )
        val order = created.json["ref"]["id"].asString()
        val callback = """{"merchantOrderId":"$order","transactionId":"tx-2","amount":4.00}"""
        // The callback itself is anyone's to send; the payment it becomes only the provider's.
        val refused = guarded.post("/example/order/payment-notified", callback, "alice")
            .assertProblem(403, FrameworkCodes.AUTH_FORBIDDEN)
        refused.json["result"]["function"]["name"].asString().assert().isEqualTo("PayOrder")
        refused.json["result"]["transformedFrom"]["command"].asString()
            .assert().isEqualTo("example.order.PaymentNotified")
        guarded.get("/example/order/$order/state", "alice").json["state"]["status"].asString()
            .assert().isEqualTo("CREATED")
        // The provider pays it through the same callback.
        guarded.post(
            "/example/order/payment-notified",
            callback,
            PROVIDER,
            headers = mapOf(HeaderCallers.ADMIN to "true", WowHeaders.SCOPE to "tenant=\"${Caller.DEFAULT_TENANT}\""),
        ).status.assert().isEqualTo(200)
    }

    /**
     * Lets only the payment provider pay an order. A configuration only where a test passes it: no `@Configuration`, so
     * the server's component scan does not give it to the other tests.
     */
    class PaymentsOnlyFromTheProvider {
        @Bean
        fun paymentsOnlyFromTheProvider(): CommandAuthorizer = CommandAuthorizer { caller, action ->
            if (action.type.name == "example.order.PayOrder" && caller.subject != PROVIDER) {
                Authorization.Denied("Only the payment provider pays an order.")
            } else {
                Authorization.Allowed
            }
        }
    }
}
