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

package me.ahoo.wow.spi.log

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PartitionLineageTest {
    @Test
    fun `a partition the log was created with has no parent and epoch 0`() {
        PartitionLineage.ROOT.parent.assert().isNull()
        PartitionLineage.ROOT.epoch.assert().isZero()
        PartitionLineage.of(null, 0).assert().isSameAs(PartitionLineage.ROOT)
        PartitionLineage.ROOT.toString().assert().isEqualTo("PartitionLineage(parent=null, epoch=0)")
    }

    @Test
    fun `a split partition names its parent and epoch, compared by value`() {
        val child = PartitionLineage.of(3, 1)
        child.parent.assert().isEqualTo(3)
        child.epoch.assert().isEqualTo(1)
        child.assert().isEqualTo(PartitionLineage.of(3, 1))
        child.hashCode().assert().isEqualTo(PartitionLineage.of(3, 1).hashCode())
        child.assert().isNotEqualTo(PartitionLineage.of(3, 2))
        child.assert().isNotEqualTo(PartitionLineage.ROOT)
        child.equals("child").assert().isFalse()
        child.toString().assert().isEqualTo("PartitionLineage(parent=3, epoch=1)")
    }

    @Test
    fun `a lineage is never negative, and only a child has an epoch`() {
        assertThrows<IllegalArgumentException> { PartitionLineage.of(-1, 1) }
        assertThrows<IllegalArgumentException> { PartitionLineage.of(0, -1) }
        assertThrows<IllegalArgumentException> { PartitionLineage.of(null, 1) }
    }

    @Test
    fun `a log that stores no lineage answers root for each of its partitions`(): Unit = runBlocking {
        val log = mockk<EventLog>()
        every { log.partitions } returns 4
        coEvery { log.lineage(any()) } coAnswers { callOriginal() }
        (0 until 4).forEach { log.lineage(it).assert().isEqualTo(PartitionLineage.ROOT) }
        assertThrows<IllegalArgumentException> { log.lineage(4) }
    }
}
