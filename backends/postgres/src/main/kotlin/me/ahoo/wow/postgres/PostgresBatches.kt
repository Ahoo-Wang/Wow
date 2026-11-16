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

package me.ahoo.wow.postgres

/** How PostgreSQL's batch statements (an append across partitions, a batch of snapshot saves) bind their rows. */
internal object PostgresBatches {
    /**
     * The rows above which a batch statement binds each column as one array, one text whatever its size, rather than a
     * list of values, a text a size. Measured (2026-11-13): a statement text a size is planned again on nearly every
     * call when the sizes vary, as the append queue's merged calls of 150 to 530 rows do (0.9 to 2.5 ms of planning a
     * call), and the arrays then gain ×1.25 at 1,024 clients and ×1.04 at 4,096. Small batches repeat their sizes,
     * stay prepared, and run faster as values: with arrays at every size a one-core store lost 2.7 to 6%.
     */
    const val ARRAYS_ABOVE: Int = 64

    /** A statement whose parameters do not match what it binds: a defect of the statement, never a refused row. */
    const val INVALID_PARAMETER_VALUE: String = "22023"
}
