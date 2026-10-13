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

package me.ahoo.wow.example.shop.domain.customer

import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.ErrorCode
import me.ahoo.wow.api.annotation.MountedCommand
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.decide.DecideScope
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.example.shop.api.customer.ChangePassword
import me.ahoo.wow.example.shop.api.customer.CustomerKeys
import me.ahoo.wow.example.shop.api.customer.CustomerRegistered
import me.ahoo.wow.example.shop.api.customer.PasswordChanged
import me.ahoo.wow.example.shop.api.customer.RegisterCustomer
import java.security.MessageDigest
import java.util.HexFormat

/**
 * A customer of the shop, found by its id or by its user name, which it reserves (decision D50). Its state is not
 * published: no other context reads a customer's password hash.
 *
 * Two commands come in through transformers of the server (decision D182): `ChangePassword` without an id goes to the
 * customer holding the user name it names, and [ImportCustomer], mounted under the customer's routes, becomes a
 * [RegisterCustomer].
 */
@AggregateRoot(published = false)
@Description("A customer of the shop, found by its id or by its user name")
class Customer(private val state: CustomerState) {
    /** Registers the customer once its user name is reserved for it; a name another customer holds is rejected. */
    @OnCommand(produces = [CustomerRegistered::class])
    suspend fun onCommand(command: RegisterCustomer, scope: DecideScope): Decision {
        if (!scope.reserve(CustomerKeys.USERNAME, command.username)) {
            return Decision.reject(CustomerRejections.USERNAME_TAKEN, "The user name ${command.username} is taken.")
        }
        return Decision.events(CustomerRegistered(command.username, hash(command.password)))
    }

    fun onCommand(command: ChangePassword): PasswordChanged = PasswordChanged(hash(command.password))

    private fun hash(password: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("${state.id}:$password".toByteArray()))
}

/** The customer's state, changed in place by its events. */
class CustomerState(val id: String) {
    var username: String = ""
        private set
    var passwordHash: String = ""
        private set

    fun onSourcing(event: CustomerRegistered) {
        username = event.username
        passwordHash = event.passwordHash
    }

    fun onSourcing(event: PasswordChanged) {
        passwordHash = event.passwordHash
    }
}

/** The codes a customer rejects a command with, besides the framework's (decision D121). */
object CustomerRejections {
    @ErrorCode(description = "Another customer holds the user name.", status = 409)
    const val USERNAME_TAKEN = "example.customer.username-taken"
}

/**
 * A customer of the shop's former system, imported (decision D182): mounted under the customer's routes, it is no
 * command of the customer's. The server's importer turns it into the [RegisterCustomer] of its user name and password,
 * and keeps [legacyId] in the command's headers, so the customer's event stream says where it came from.
 */
@MountedCommand(aggregate = "customer", transformsTo = ["example.customer.RegisterCustomer"])
data class ImportCustomer(
    val username: String,
    val password: String,
    val legacyId: String,
)
