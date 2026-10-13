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

package me.ahoo.wow.runtime.subscription

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

class ParkedNotifierTest {
    private val notice = ParkedNotice(
        ParkedNotice.Kind.PARKED,
        "group",
        ParkedAggregate("sub", cart("x"), 1, "boom", 0),
        ParkedRetrySpec(),
        0,
    )

    @Test
    fun `notifiers are told in turn, one that fails not keeping the others from hearing`(): Unit = runBlocking {
        val heard = CopyOnWriteArrayList<String>()
        val notifier = ParkedNotifier.of(
            listOf(
                ParkedNotifier { heard += "first" },
                ParkedNotifier { error("down") },
                ParkedNotifier { heard += "third" },
            ),
        )
        notifier.notifyQuietly(notice)
        heard.assert().containsExactly("first", "third")
        ParkedNotifier.of(emptyList()).assert().isSameAs(ParkedNotifier.NONE)
        val single = ParkedNotifier { }
        ParkedNotifier.of(listOf(single)).assert().isSameAs(single)
        ParkedNotifier.NONE.notifyQuietly(notice)
        ParkedNotifier.NONE.notify(notice)
    }

    @Test
    fun `a cancellation is not swallowed`() {
        val cancelled = ParkedNotifier { throw CancellationException("stopped") }
        assertThrownBy<CancellationException> { runBlocking { cancelled.notifyQuietly(notice) } }
    }
}
