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
 * The default revision string used when no specific revision is provided.
 *
 * This constant represents the initial version of domain events and aggregates.
 * It follows semantic versioning format (major.minor.patch) and serves as
 * the baseline version for backward compatibility checks.
 */
const val DEFAULT_REVISION = "0.0.1"

/**
 * Interface for entities that have version/revision information.
 *
 * Revisions provide a way to track changes and versions of domain objects,
 * enabling backward compatibility, migration strategies, and proper event
 * deserialization across different versions of the domain model.
 *
 * In domain-driven design contexts, revisions are particularly important for:
 * - Domain events to indicate the aggregate version that produced them
 * - Ensuring event schema compatibility during event sourcing
 * - Supporting gradual migration of domain models
 * - Maintaining audit trails of structural changes
 *
 * @see DomainEvent for domain events that implement this interface
 * @see DEFAULT_REVISION for the default revision value
 */
interface Revision {
    /**
     * The revision/version identifier for this entity.
     *
     * The revision follows semantic versioning format (major.minor.patch) and is used to:
     * - Track structural changes in domain events and aggregates
     * - Ensure compatibility during event deserialization
     * - Support migration strategies for domain model evolution
     * - Provide audit trails for version changes
     *
     * When not explicitly provided, defaults to [DEFAULT_REVISION].
     * Custom revisions should be set when the event or aggregate structure changes
     * in ways that affect serialization or business logic.
     */
    val revision: String
        get() = DEFAULT_REVISION
}
