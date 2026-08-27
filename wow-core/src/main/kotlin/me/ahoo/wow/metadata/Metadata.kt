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

package me.ahoo.wow.metadata

/**
 * Marker interface for metadata objects in the Wow framework.
 * Classes implementing this interface represent parsed metadata information
 * about various components such as commands, events, aggregates, and other
 * framework elements. This interface serves as a type constraint for metadata
 * objects that can be cached and managed by metadata parsers.
 *
 * Metadata implementations typically contain information extracted from
 * annotations, class structures, or other reflective analysis of framework components.
 */
interface Metadata
