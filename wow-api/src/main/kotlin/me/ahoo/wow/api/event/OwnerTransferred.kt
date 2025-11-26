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
 * Represents a domain event indicating that ownership of an aggregate has been transferred to a new owner.
 *
 * This interface is typically implemented by domain events that signal a change in ownership,
 * such as transferring responsibility or control of an entity to another party.
 *
 * @property targetOwnerId The unique identifier of the new owner to whom ownership is being transferred.
 *                         This should be a non-empty string representing the owner's ID.
 */
interface OwnerTransferred {
    val targetOwnerId: String
}
