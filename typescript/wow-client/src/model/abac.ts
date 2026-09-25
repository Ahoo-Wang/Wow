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
/** The key of an ABAC tag, such as `dept` or `role`; never blank. */
export type AbacTagKey = string;
/**
 * The values of an ABAC tag. An empty list is no tag, and `['*']` matches
 * every value; see {@link WILDCARD_ABAC_TAG_VALUES}.
 */
export type AbacTagValue = string[];
/**
 * Attribute-based access control tags of a principal or a resource, by key:
 * `{ dept: ['eng', 'pm'], role: ['admin'] }`. No tags is a public resource.
 */
export type AbacTags = Record<AbacTagKey, AbacTagValue>;
/** No tags. Frozen: copy it before adding one. */
export const EMPTY_ABAC_TAGS: Readonly<AbacTags> = Object.freeze({});
/** The tag values that match any value. Frozen: copy it before changing it. */
export const WILDCARD_ABAC_TAG_VALUES: readonly string[] = Object.freeze(['*']);

/** Something that carries ABAC tags; wow-api's `AbacTaggable`. */
export interface AbacTaggable {
  /** The tags, by key. */
  tags: AbacTags;
}

/**
 * A command that applies ABAC tags; wow-api's `ApplyAbacTags`. The server
 * refuses a blank tag key. {@link ApplyResourceTags} is the built-in one.
 */
// eslint-disable-next-line @typescript-eslint/no-empty-object-type
export interface ApplyAbacTags extends AbacTaggable {}

/**
 * The event that ABAC tags were applied; wow-api's `AbacTagsApplied`, which
 * consumers read to refresh authorization caches.
 */
// eslint-disable-next-line @typescript-eslint/no-empty-object-type
export interface AbacTagsApplied extends AbacTaggable {}
