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

package me.ahoo.wow.migrate

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.reservation.ReservationScope
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class ImportedKeysTest {
    @TempDir
    lateinit var directory: Path

    private val owner = AggregateType("example", "order").ref("(0)", "o-1")
    private val scope = ReservationScope.of(owner.type, ReservationScope.SYSTEM_TENANT, "order.no")

    private fun key(name: String, key: String) = ImportedKey.of(name, key, scope, owner)

    private suspend fun FileImportedKeys.keysOf(name: String): List<ImportedKey> =
        mutableListOf<ImportedKey>().also { keys -> forEach(name) { keys += it } }

    @Test
    fun `the file keeps the keys in order, and retain rewrites only those of its PrepareKey`(): Unit = runTest {
        val path = directory.resolve("checkpoint.json.reservations.jsonl")
        val keys = FileImportedKeys(path)
        keys.keysOf("order_no").assert().isEmpty()
        keys.retain("order_no") { true }
        keys.add(emptyList())
        Files.exists(path).assert().isFalse()

        keys.add(listOf(key("order_no", "NO-1"), key("order_code", "C-1")))
        keys.add(listOf(key("order_no", "NO-2")))
        val reopened = FileImportedKeys(path)
        reopened.keysOf("order_no").assert().containsExactly(key("order_no", "NO-1"), key("order_no", "NO-2"))
        keys.keysOf("order_no").first().also {
            it.scope().assert().isEqualTo(scope)
            it.owner().assert().isEqualTo(owner)
        }

        keys.retain("order_no") { it.key == "NO-2" }
        keys.keysOf("order_no").assert().containsExactly(key("order_no", "NO-2"))
        keys.keysOf("order_code").assert().containsExactly(key("order_code", "C-1"))
    }

    @Test
    fun `a last line cut short is ignored, a broken line before it is not`(): Unit = runTest {
        val path = directory.resolve("keys.jsonl")
        val keys = FileImportedKeys(path)
        keys.add(listOf(key("order_no", "NO-1")))
        Files.writeString(path, "{\"name\":\"order_no\",\"cont", StandardOpenOption.APPEND)
        keys.keysOf("order_no").assert().containsExactly(key("order_no", "NO-1"))

        Files.writeString(path, "\n" + Files.readAllLines(path).first() + "\n", StandardOpenOption.APPEND)
        assertThrows<MigrationException> { keys.keysOf("order_no") }.message!!.assert().contains("Line 2 of $path")
    }

    @Test
    fun `in memory keys retain as the file does`(): Unit = runTest {
        val keys = InMemoryImportedKeys()
        keys.add(listOf(key("order_no", "NO-1"), key("order_code", "C-1"), key("order_no", "NO-2")))
        keys.retain("order_no") { it.key == "NO-1" }
        keys.all.assert().containsExactly(key("order_no", "NO-1"), key("order_code", "C-1"))
        mutableListOf<String>().also { seen -> keys.forEach("order_code") { seen += it.key } }
            .assert().containsExactly("C-1")
    }
}
