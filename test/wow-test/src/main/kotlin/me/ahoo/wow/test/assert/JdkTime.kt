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
import org.assertj.core.api.Assertions.assertThatTemporal
import org.assertj.core.api.DateAssert
import org.assertj.core.api.DurationAssert
import org.assertj.core.api.InstantAssert
import org.assertj.core.api.LocalDateAssert
import org.assertj.core.api.LocalDateTimeAssert
import org.assertj.core.api.LocalTimeAssert
import org.assertj.core.api.OffsetDateTimeAssert
import org.assertj.core.api.OffsetTimeAssert
import org.assertj.core.api.PeriodAssert
import org.assertj.core.api.TemporalAssert
import org.assertj.core.api.YearMonthAssert
import org.assertj.core.api.ZonedDateTimeAssert
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.Period
import java.time.YearMonth
import java.time.ZonedDateTime
import java.time.temporal.Temporal
import java.util.*

fun Date.assert(): DateAssert {
    return assertThat(this) as DateAssert
}

fun ZonedDateTime.assert(): ZonedDateTimeAssert {
    return assertThat(this) as ZonedDateTimeAssert
}

fun Temporal.assertTemporal(): TemporalAssert {
    return assertThatTemporal(this)
}

fun LocalDateTime.assert(): LocalDateTimeAssert {
    return assertThat(this) as LocalDateTimeAssert
}

fun OffsetDateTime.assert(): OffsetDateTimeAssert {
    return assertThat(this) as OffsetDateTimeAssert
}

fun OffsetTime.assert(): OffsetTimeAssert {
    return assertThat(this) as OffsetTimeAssert
}

fun LocalTime.assert(): LocalTimeAssert {
    return assertThat(this) as LocalTimeAssert
}

fun LocalDate.assert(): LocalDateAssert {
    return assertThat(this) as LocalDateAssert
}

fun YearMonth.assert(): YearMonthAssert {
    return assertThat(this) as YearMonthAssert
}

fun Instant.assert(): InstantAssert {
    return assertThat(this) as InstantAssert
}

fun Duration.assert(): DurationAssert {
    return assertThat(this) as DurationAssert
}

fun Period.assert(): PeriodAssert {
    return assertThat(this) as PeriodAssert
}
