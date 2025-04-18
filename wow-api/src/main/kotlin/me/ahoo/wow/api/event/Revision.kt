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
package me.ahoo.wow.api.event

/**
 * Revision .
 *
 * @author ahoo wang
 */
const val DEFAULT_REVISION = "0.0.1"

/**
 * Represents a revision of an entity or event, providing a way to track changes and versions.
 *
 * This interface is particularly useful in domain-driven design (DDD) contexts, where it can be used
 * by domain events to indicate the version of the aggregate that produced the event. It ensures that
 * every event has a `revision` property, which defaults to a predefined value if not explicitly set.
 */
interface Revision {

    /**
     * Represents the revision of the domain event. This value is used to track changes or updates in the domain model, ensuring that each version of an aggregate can be uniquely identified and
     *  managed.
     *
     * The `revision` property returns a default revision string, which is typically used when a specific revision is not explicitly provided. This is useful for maintaining consistency and traceability
     *  in the domain events, especially in scenarios where versioning and auditing are critical.
     */
    val revision: String
        get() = DEFAULT_REVISION
}
