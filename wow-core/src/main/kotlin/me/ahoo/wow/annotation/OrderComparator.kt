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

package me.ahoo.wow.annotation

import me.ahoo.wow.api.annotation.ORDER_DEFAULT
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.infra.reflection.AnnotationScanner.scan

/**
 * TODO traverse comparison depth
 */
object OrderComparator : Comparator<Any> {

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    override fun compare(o1: Any, o2: Any): Int {
        val leftOrder: Order? = o1.javaClass.scan()
        val rightOrder: Order? = o2.javaClass.scan()

        if (leftOrder == null && rightOrder == null) {
            return 0
        }
        if (leftOrder != null) {
            leftOrder.before.forEach {
                if (it.java.isAssignableFrom(o2.javaClass)) {
                    return -1
                }
            }
            leftOrder.after.forEach {
                if (it.java.isAssignableFrom(o2.javaClass)) {
                    return 1
                }
            }
        }

        if (rightOrder != null) {
            rightOrder.before.forEach {
                if (it.java.isAssignableFrom(o1.javaClass)) {
                    return 1
                }
            }
            rightOrder.after.forEach {
                if (it.java.isAssignableFrom(o1.javaClass)) {
                    return -1
                }
            }
        }
        val leftOrderValue = leftOrder?.value ?: ORDER_DEFAULT
        val rightOrderValue = rightOrder?.value ?: ORDER_DEFAULT
        return leftOrderValue.compareTo(rightOrderValue)
    }
}
