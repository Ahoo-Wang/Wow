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

package me.ahoo.wow.schema.golden

import java.nio.file.Files
import java.nio.file.Path

/**
 * Byte-for-byte snapshots of generated output under `src/test/resources/golden`. Generated schemas are a contract, so
 * an internal refactor must leave every golden unchanged. Regenerate with `WOW_SCHEMA_GOLDEN_UPDATE=true` and review
 * the diff.
 */
object Golden {
    private const val UPDATE_ENV = "WOW_SCHEMA_GOLDEN_UPDATE"
    private val ROOT: Path = Path.of("src/test/resources/golden")

    /** Returns the mismatch for [name], or `null` when [actual] equals the golden (or the goldens are being updated). */
    fun compare(name: String, actual: String): String? {
        val path = ROOT.resolve(name)
        if (System.getenv(UPDATE_ENV) == "true") {
            Files.createDirectories(path.parent)
            Files.writeString(path, actual)
            return null
        }
        if (!Files.exists(path)) {
            return "Missing golden $path; run with $UPDATE_ENV=true"
        }
        return if (Files.readString(path) == actual) null else "Golden mismatch: $path"
    }
}
