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

package me.ahoo.wow.ops.api.parked

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass
import kotlin.time.Duration

/**
 * A retry spec's duration, as the owning service reads it: Kotlin's [Duration.parse] of the trimmed text, ISO-8601
 * (`PT3M`, `P1DT2H`) or Kotlin's own format (`3m`, `1h 30m`, `1h30m`), finite and not negative. A member left out
 * (`null`) is valid: it keeps the duration the entry is retried by now.
 */
@MustBeDocumented
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [RetryDuration.Validator::class])
annotation class RetryDuration(
    val message: String = "must be a duration such as PT3M, 3m or 1h 30m, not negative",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
) {
    /** Reads the text as [Duration.parseOrNull] does. */
    class Validator : ConstraintValidator<RetryDuration, String> {
        override fun isValid(value: String?, context: ConstraintValidatorContext): Boolean =
            value == null || Duration.parseOrNull(value.trim())?.let { it.isFinite() && !it.isNegative() } == true
    }
}
