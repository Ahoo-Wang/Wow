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

package me.ahoo.wow.test.assert

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.BigDecimalAssert
import org.assertj.core.api.BooleanAssert
import org.assertj.core.api.ByteAssert
import org.assertj.core.api.CollectionAssert
import org.assertj.core.api.DoubleAssert
import org.assertj.core.api.FloatAssert
import org.assertj.core.api.IntegerAssert
import org.assertj.core.api.IteratorAssert
import org.assertj.core.api.ListAssert
import org.assertj.core.api.LongAssert
import org.assertj.core.api.MapAssert
import org.assertj.core.api.ObjectArrayAssert
import org.assertj.core.api.ObjectAssert
import org.assertj.core.api.OptionalAssert
import org.assertj.core.api.ShortAssert
import org.assertj.core.api.StringAssert
import org.assertj.core.api.ThrowableAssert
import java.math.BigDecimal
import java.util.*
import java.util.stream.Stream

fun Boolean.assert(): BooleanAssert {
    return BooleanAssert(this)
}

fun Byte.assert(): ByteAssert {
    return ByteAssert(this)
}

fun Short.assert(): ShortAssert {
    return ShortAssert(this)
}

fun Int.assert(): IntegerAssert {
    return IntegerAssert(this)
}

fun Long.assert(): LongAssert {
    return LongAssert(this)
}

fun Float.assert(): FloatAssert {
    return FloatAssert(this)
}

fun Double.assert(): DoubleAssert {
    return DoubleAssert(this)
}

fun BigDecimal.assert(): BigDecimalAssert {
    return BigDecimalAssert(this)
}

fun String.assert(): StringAssert {
    return StringAssert(this)
}

fun <T> T.assert(): ObjectAssert<T> {
    return assertThat(this)
}

fun <T> Iterator<T>.assert(): IteratorAssert<T> {
    return assertThat(this)
}

fun <T> Collection<T>.assert(): CollectionAssert<T> {
    return CollectionAssert(this)
}

fun <T> Array<T>.assert(): ObjectArrayAssert<T> {
    return assertThat(this)
}

fun <T> List<T>.assert(): ListAssert<T> {
    return ListAssert(this)
}

fun <T : Throwable> T.assert(): ThrowableAssert<T> {
    return ThrowableAssert(this)
}

fun <T> Optional<T>.assert(): OptionalAssert<T> {
    return assertThat<T>(this)
}

fun <K, V> Map<K, V>.assert(): MapAssert<K, V> {
    return MapAssert(this)
}

fun <T> Stream<T>.assert(): ListAssert<T> {
    return assertThat(this)
}
