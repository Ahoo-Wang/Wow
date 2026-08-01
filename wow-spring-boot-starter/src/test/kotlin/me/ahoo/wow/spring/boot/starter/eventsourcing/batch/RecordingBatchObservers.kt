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

package me.ahoo.wow.spring.boot.starter.eventsourcing.batch

import me.ahoo.test.asserts.assert
import me.ahoo.wow.infra.batch.BatchObservation
import me.ahoo.wow.infra.batch.BatchObserver
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.concurrent.CopyOnWriteArrayList

internal class RecordingBatchObservers {
    private val firstObservations = CopyOnWriteArrayList<BatchObservation>()
    private val secondObservations = CopyOnWriteArrayList<BatchObservation>()
    val first = BatchObserver(firstObservations::add)
    val second = BatchObserver(secondObservations::add)

    fun verifyClose(
        coordinatorName: String,
        close: () -> Unit,
    ) {
        close()
        firstObservations.assertClosed(coordinatorName)
        secondObservations.assertClosed(coordinatorName)
    }

    private fun List<BatchObservation>.assertClosed(coordinatorName: String) {
        filterIsInstance<BatchObservation.CloseStarted>()
            .single().coordinatorName.assert().isEqualTo(coordinatorName)
    }
}

internal fun ApplicationContextRunner.withBatchObservers(
    observers: RecordingBatchObservers,
): ApplicationContextRunner =
    withBean("firstBatchObserver", BatchObserver::class.java, { observers.first })
        .withBean("secondBatchObserver", BatchObserver::class.java, { observers.second })
