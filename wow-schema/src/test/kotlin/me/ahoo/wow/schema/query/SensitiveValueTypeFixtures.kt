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

package me.ahoo.wow.schema.query

import com.fasterxml.jackson.annotation.JsonValue
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.annotation.OnSourcing
import me.ahoo.wow.api.query.annotation.Mask
import me.ahoo.wow.api.query.annotation.Sensitive
import me.ahoo.wow.api.query.annotation.SensitivityLevel

@JvmInline
@Sensitive(SensitivityLevel.DISPLAY, mask = Mask(keepPrefix = 3, keepSuffix = 2))
value class PhoneNumber(val value: String)

@Sensitive(SensitivityLevel.CONFIDENTIAL)
class IdCardNumber(private val value: String) {
    @JsonValue
    fun value(): String = value
}

@Sensitive(SensitivityLevel.DISPLAY)
data class NotAValueType(val value: String)

internal data class ValueTypeState(
    val phone: PhoneNumber,
    val phones: List<PhoneNumber>,
    val idCard: IdCardNumber,
    @field:Sensitive(SensitivityLevel.CONFIDENTIAL)
    val tightened: PhoneNumber,
    val plain: String,
)

internal data class LoosenedValueTypeState(
    @field:Sensitive(SensitivityLevel.DISPLAY)
    val idCard: IdCardNumber,
)

internal data class NonValueTypeState(val value: NotAValueType)

/** A state and its event share [PhoneNumber]; `mobile` and `email` are protected in one model only. */
@AggregateRoot
@Suppress("UnusedPrivateProperty")
class ContactAggregate(private val state: ContactState) {
    @OnCommand
    fun onCommand(command: ChangeContact): ContactChanged =
        ContactChanged(command.phone, command.mobile, command.email)
}

class ContactState(val id: String) {
    var phone: PhoneNumber? = null
        private set

    @field:Sensitive(SensitivityLevel.DISPLAY)
    var mobile: String? = null
        private set

    var email: String? = null
        private set

    @OnSourcing
    fun onContactChanged(event: ContactChanged) {
        phone = event.phone
        mobile = event.mobile
        email = event.email
    }
}

data class ChangeContact(val phone: PhoneNumber, val mobile: String, val email: String)

data class ContactChanged(
    val phone: PhoneNumber,
    val mobile: String,
    @field:Sensitive(SensitivityLevel.DISPLAY)
    val email: String,
)
