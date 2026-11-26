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

package me.ahoo.wow.mongo.timer

import me.ahoo.test.asserts.assert
import me.ahoo.wow.mongo.MongoRules
import org.bson.Document
import org.junit.jupiter.api.Test
import tools.jackson.core.JacksonException

/** How the timer store names a stored rule it cannot read. */
class MongoTimerRulesTest {
    /** The failure of decoding [rule]: the store names the value from it. */
    private fun failureOf(rule: Document): JacksonException = try {
        MongoRules.of(rule)
        throw AssertionError("${rule.toJson()} decodes.")
    } catch (failure: JacksonException) {
        failure
    }

    @Test
    fun `a later version's rule type should be named by its type`() {
        val rule = Document.parse("""{"type":"later","at":5}""")
        MongoTimerStore.unreadable(failureOf(rule), rule).assert().isEqualTo("rule.type" to "later")
    }

    @Test
    fun `a misfire this version does not know should be named by its field`() {
        val rule = Document.parse("""{"type":"cron","expression":"0 0 * * * *","timeZone":"UTC","misfire":"later"}""")
        MongoTimerStore.unreadable(failureOf(rule), rule).assert().isEqualTo("rule.misfire" to "later")
    }

    @Test
    fun `any other failure should name the rule itself`() {
        val rule = Document.parse("""{"type":"at"}""")
        MongoTimerStore.unreadable(failureOf(rule), rule).assert().isEqualTo("rule" to rule.toJson())
    }
}
