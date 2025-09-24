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
 * Interface for path parameters used in URL construction.
 * Defines common path parameters and allows for additional custom parameters.
 * This interface is used to provide path variables for URL templating.
 */
export interface UrlPathParams {
  /**
   * Tenant identifier parameter.
   * Used in multi-tenant applications to identify the tenant context.
   */
  tenantId?: string;
  /**
   * Owner identifier parameter.
   * Used to identify the owner or user context for the resource.
   */
  ownerId?: string;
  /**
   * Generic identifier parameter.
   * Used as a general purpose ID for resources when tenantId or ownerId are not appropriate.
   */
  id?: string;

  /**
   * Index signature for additional custom path parameters.
   * Allows for any additional string key-value pairs to be included as path parameters.
   */
  [key: string]: string | undefined;
}

/**
 * Enumeration of resource attribution path specifications.
 * Defines standard path patterns for accessing resources with different attribution scopes.
 * These paths are used to construct URLs that include tenant and/or owner identifiers.
 *
 * @example
 * ```typescript
 * // Using TENANT path spec
 * const path = ResourceAttributionPathSpec.TENANT; // 'tenant/{tenantId}'
 *
 * // Using TENANT_OWNER path spec
 * const path = ResourceAttributionPathSpec.TENANT_OWNER; // 'tenant/{tenantId}/owner/{ownerId}'
 * ```
 */
export enum ResourceAttributionPathSpec {
  DEFAULT = '',
  TENANT = 'tenant/{tenantId}',
  OWNER = 'owner/{ownerId}',
  TENANT_OWNER = 'tenant/{tenantId}/owner/{ownerId}',
}