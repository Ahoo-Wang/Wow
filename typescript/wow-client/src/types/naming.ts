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

/**
 * Interface for named bounded contexts.
 */
export interface NamedBoundedContext {
  contextName: string;
}

/**
 * Interface for named entities.
 */
export interface Named {
  name: string;
}

/**
 * Interface for entities that have a description.
 *
 * This interface defines a contract for objects that can provide a descriptive text.
 * It is commonly used in conjunction with other naming interfaces to provide additional
 * context or information about an entity.
 */
export interface DescriptionCapable {
  description: string;
}
