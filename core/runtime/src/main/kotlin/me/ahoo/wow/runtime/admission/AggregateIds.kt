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

package me.ahoo.wow.runtime.admission

import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * The one rule of which aggregate ids no route or store keeps as they were given, so that admission, which refuses
 * them, and `migrate-data`, which reports the 9.x aggregates that have one before the cut-over, never disagree about
 * an id.
 */
@InternalWowApi
object AggregateIds {
    /**
     * Why [id], an aggregate id, is one admission refuses whatever its length, as text, or null when it is not:
     *
     * - `.` or `..`, a relative path segment, which a proxy normalizing the path would send as another request;
     * - white space at either end, another aggregate than the one without it, to a reader;
     * - `\u0000` or another control character (`\u0000` to `\u001f`, `\u007f` to `\u009f`), which not every store
     *   keeps.
     *
     * One pass over its characters. A blank id is refused where it is read, and an id longer than admission takes is
     * refused by its length: neither is answered here.
     */
    fun unaddressable(id: String?): String? = when {
        id == null || id.isBlank() -> null
        id == "." || id == ".." -> "The aggregate id '$id' is a relative path segment, which a proxy may resolve away."
        id.first().isWhitespace() || id.last().isWhitespace() ->
            "The aggregate id '$id' begins or ends with white space, which a reader would not see."

        id.any(Char::isISOControl) ->
            "The aggregate id holds a control character (\\u0000 to \\u001f, \\u007f to \\u009f), which not every " +
                "store keeps."

        else -> null
    }
}
