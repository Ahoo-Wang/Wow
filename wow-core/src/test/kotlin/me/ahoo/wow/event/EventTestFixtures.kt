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

package me.ahoo.wow.event

import me.ahoo.wow.api.annotation.AggregateId
import me.ahoo.wow.api.annotation.AggregateName
import me.ahoo.wow.api.annotation.Event
import me.ahoo.wow.api.annotation.Name
import me.ahoo.wow.api.event.IgnoreSourcing
import me.ahoo.wow.api.exception.ErrorInfo

const val FIXTURE_EVENT_NAME = "fixture_named_event"
const val FIXTURE_EVENT_REVISION = "fixture-revision"
const val FIXTURE_NAMED_AGGREGATE = "event.fixture"
const val NAMED_EVENT = "NamedEvent"
const val REVISED_EVENT = "RevisedEvent"

@Name(FIXTURE_EVENT_NAME)
data class FixtureNamedEvent(val value: String = "value")

@Name(FIXTURE_EVENT_NAME)
@Event(revision = FIXTURE_EVENT_REVISION)
data class FixtureRevisedEvent(val value: String = "value")

data class FixtureRoutedEvent(
    @AggregateId val id: String,
    @AggregateName val aggregate: String = FIXTURE_NAMED_AGGREGATE,
)

data class FixtureIgnoredErrorEvent(
    override val errorCode: String,
    override val errorMsg: String,
) : IgnoreSourcing,
    ErrorInfo

@Name(NAMED_EVENT)
class MockNamedEvent

@Name("")
class MockNamedEmptyEvent

@Name(NAMED_EVENT)
@Event(revision = REVISED_EVENT)
class MockNamedAndRevisedEvent

class ErrorIgnoreEvent(
    override val errorCode: String,
    override val errorMsg: String,
) : IgnoreSourcing,
    ErrorInfo
