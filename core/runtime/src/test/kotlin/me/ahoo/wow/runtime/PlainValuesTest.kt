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

package me.ahoo.wow.runtime

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAction
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.CommandInput
import me.ahoo.wow.runtime.admission.CommandTarget
import me.ahoo.wow.runtime.cluster.CommandWire
import me.ahoo.wow.runtime.cluster.LeaseGroup
import me.ahoo.wow.runtime.cluster.ReplyTo
import me.ahoo.wow.runtime.snapshot.SnapshotPlacement
import me.ahoo.wow.runtime.subscription.ParkedNotice
import me.ahoo.wow.runtime.subscription.RetryPolicy
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The runtime's plain values (finding R5.A-24): each made by `of`, equal to itself and to one of the same fields,
 * unequal to another, copied by `copyWith` where Wow copies it, and said by name.
 */
class PlainValuesTest {
    private val type = CommandType.of("shop.order.Pay", String::class.java)
    private val ref = AggregateType("shop", "order").ref("t", "o-1")

    private fun <T : Any> checks(value: T, same: T, other: T, named: String) {
        value.assert().isEqualTo(value).isEqualTo(same).isNotEqualTo(other).isNotEqualTo(named)
        value.hashCode().assert().isEqualTo(same.hashCode())
        value.toString().assert().startsWith(named)
    }

    @Test
    fun `the admission's values`() {
        checks(
            CommandAction.of(type, ref, Scope.NONE, Creation.NONE),
            CommandAction.of(type, ref, Scope.NONE, Creation.NONE),
            CommandAction.of(type, ref, Scope.NONE, Creation.REQUIRED),
            "CommandAction(",
        )
        checks(
            Authorization.Denied.of("no"),
            Authorization.Denied.of("no"),
            Authorization.Denied.of("no", "x"),
            "Denied("
        )
        val node = WowJson.mapper.readTree("""{"a":1}""")
        checks(
            CommandBody.Tree.of(node),
            CommandBody.Tree.of(node),
            CommandBody.Tree.of(WowJson.mapper.readTree("{}")),
            "Tree("
        )
        checks(CommandBody.Value.of("v"), CommandBody.Value.of("v"), CommandBody.Value.of("w"), "Value(")
        val input = CommandInput.of(type, "body", "o-1", emptyMap(), "r-1", Caller.ANONYMOUS)
        checks(
            input,
            CommandInput.of(type, "body", "o-1", emptyMap(), "r-1", Caller.ANONYMOUS),
            input.copyWith(body = "other"),
            "CommandInput("
        )
        input.copyWith(requestId = "r-2").requestId.assert().isEqualTo("r-2")
        checks(
            CommandTarget.of(type, "o-1", "b"),
            CommandTarget.of(type, "o-1", "b", emptyMap()),
            CommandTarget.of(type, null, "b"),
            "CommandTarget("
        )
    }

    @Test
    fun `the cluster's values`() {
        val body = WowJson.mapper.readTree("{}")
        val wire = CommandWire.of("shop.order.Pay", "c-1", "r-1", ref, body)
        checks(
            wire,
            CommandWire.of("shop.order.Pay", "c-1", "r-1", ref, body),
            wire.copyWith(id = "c-2"),
            "CommandWire("
        )
        val reply = ReplyTo.of("shop", "saga", ref, 1)
        checks(reply, ReplyTo.of("shop", "saga", ref, 1, false), reply.copyWith(committed = true), "ReplyTo(")
        checks(LeaseGroup.of("svc", "shop"), LeaseGroup.of("svc", "shop"), LeaseGroup.of("svc", "bank"), "svc/shop")
    }

    @Test
    fun `the snapshot, subscription and retry values`() {
        checks(
            SnapshotPlacement.of(listOf("a", "b")),
            SnapshotPlacement.of(listOf("a", "b"), "a"),
            SnapshotPlacement.of(listOf("a", "b"), "b"),
            "SnapshotPlacement("
        )
        val entry = ParkedAggregate("s", ref, 1, "failed", 1L)
        val notice = ParkedNotice.of(ParkedNotice.Kind.PARKED, "g", entry, ParkedRetrySpec(), 1L)
        checks(
            notice,
            ParkedNotice.of(ParkedNotice.Kind.PARKED, "g", entry, ParkedRetrySpec(), 1L),
            notice.copyWith(kind = ParkedNotice.Kind.EXHAUSTED),
            "ParkedNotice("
        )
        val retry = RetryPolicy.of(maxAttempts = 3)
        checks(retry, RetryPolicy.of(maxAttempts = 3), retry.copyWith(timeout = 1.seconds), "RetryPolicy(")
    }
}
