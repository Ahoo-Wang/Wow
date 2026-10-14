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

package me.ahoo.wow.manifest

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class FrameworkErrorsTest {
    @Test
    fun `the table should give every framework code the status of design 05 section 3`() {
        FrameworkErrors.ALL.associate { it.code to it.status }.assert().isEqualTo(
            mapOf(
                "wow.auth.unauthenticated" to 401,
                "wow.auth.forbidden" to 403,
                "wow.aggregate.forbidden" to 403,
                "wow.command.malformed" to 400,
                "wow.command.invalid" to 400,
                "wow.wait.invalid" to 400,
                "wow.command.unhandled" to 400,
                "wow.aggregate.not-found" to 404,
                "wow.aggregate.exists" to 409,
                "wow.aggregate.version-mismatch" to 409,
                "wow.command.request-id-reused" to 409,
                "wow.command.contended" to 409,
                "wow.aggregate.deleted" to 410,
                "wow.command.busy" to 429,
                "wow.command.unknown" to 503,
                "wow.node.closing" to 503,
                "wow.storage.unavailable" to 503,
                "wow.schedule.not-due" to 503,
                "wow.subscription.parked" to 500,
                "wow.wait.timeout" to 504,
                "wow.query.malformed" to 400,
                "wow.query.invalid" to 400,
                "wow.query.failed" to 500,
                "wow.query.timeout" to 503,
            ),
        )
        FrameworkErrors.DOMAIN_REJECTION_STATUS.assert().isEqualTo(422)
        FrameworkErrors.PRECONDITION_FAILED_STATUS.assert().isEqualTo(412)
    }

    @Test
    fun `every code should be once in the table, with a description and the framework's prefix`() {
        FrameworkErrors.BY_CODE.size.assert().isEqualTo(FrameworkErrors.ALL.size)
        FrameworkErrors.ALL.forEach {
            it.code.assert().startsWith(FrameworkErrors.PREFIX)
            it.description.assert().isNotBlank()
        }
        FrameworkErrors.BY_CODE[FrameworkErrors.AGGREGATE_VERSION_MISMATCH]?.description.assert().contains("412")
    }
}
