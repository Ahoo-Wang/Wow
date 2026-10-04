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

package me.ahoo.wow.tck.wire

import org.junit.jupiter.api.Assertions.assertEquals
import java.nio.file.Files
import java.nio.file.Path

/**
 * Byte-for-byte comparison against a committed golden file.
 *
 * A golden file holds the exact text under test followed by one newline. A missing file fails the test: the v9 wire
 * goldens are frozen contracts, so they are only (re)written on purpose, by running the test with
 * `WOW_GOLDEN_UPDATE=true` and reviewing the diff together with the design decision that allows it.
 */
object WireGolden {
    const val UPDATE_ENV = "WOW_GOLDEN_UPDATE"

    /** Golden files live under the module's `src/test/resources/wire/v9/`. */
    fun path(name: String): Path = Path.of("src/test/resources/wire/v9", name)

    /** Returns the golden text (without the trailing newline). */
    fun read(name: String): String {
        val path = path(name)
        check(Files.exists(path)) {
            "Golden file [$path] is missing. Generate it with $UPDATE_ENV=true from the release whose wire it freezes."
        }
        return Files.readString(path).removeSuffix("\n")
    }

    /** Asserts [actual] equals the golden file byte for byte, or rewrites the file when [UPDATE_ENV] is `true`. */
    fun assertMatches(name: String, actual: String) {
        if (System.getenv(UPDATE_ENV) == "true") {
            val path = path(name)
            Files.createDirectories(path.parent)
            Files.writeString(path, actual + "\n")
        }
        assertEquals(read(name), actual) {
            "Wire format of [$name] changed. It is a frozen v9 contract: changing it needs a design decision."
        }
    }
}
