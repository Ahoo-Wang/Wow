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

import type { DescriptionCapable } from '../../model/index.js';

/** Something that owns packages, by package name prefix. */
export interface ScopesCapable {
  /** The packages that belong to it. */
  scopes: string[];
}

/** One aggregate of a {@link BoundedContext} in {@link WowMetadata}. */
export interface Aggregate extends ScopesCapable {
  /**
   * Aggregate type fully qualified name
   */
  type: string | null;
  /**
   * Static tenant ID
   */
  tenantId: string | null;
  /**
   * Custom ID generator name
   */
  id: string | null;
  /**
   * The packages of the commands the aggregate handles
   */
  commands: string[];
  /**
   * The packages of the domain events the aggregate raises
   */
  events: string[];
}

/** One bounded context of {@link WowMetadata}. */
export interface BoundedContext extends ScopesCapable, DescriptionCapable {
  /**
   * The context's short name, unique across the server, which Wow uses in
   * route paths and schema names in place of the context name; `null` when
   * the context has none.
   */
  alias: string | null;
  /** The context's aggregates, by aggregate name. */
  aggregates: Record<string, Aggregate>;
}

/**
 * What a Wow server declares about itself: its bounded contexts and their
 * aggregates, as `WowMetadataClient` reads it from `GET /wow/metadata`.
 */
export interface WowMetadata extends DescriptionCapable {
  /** The bounded contexts, by context name. */
  contexts: Record<string, BoundedContext>;
}
