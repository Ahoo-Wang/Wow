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
 * HTTP headers the Wow server shares across commands and queries.
 *
 * Mirrors `CommonComponent.Header` in
 * `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/CommonComponent.kt`.
 * These headers carry the `Wow-` prefix, not the `Command-` prefix of
 * {@link CommandHeaders}.
 *
 * @example
 * ```typescript
 * // Send a command into a space
 * await commandClient.send({
 *   method: HttpMethod.POST,
 *   headers: { [WowHeaders.SPACE_ID]: 'store-1' },
 *   body: { ... },
 * });
 * ```
 */
export class WowHeaders {
  /**
   * Prefix of the headers shared across commands and queries
   */
  static readonly WOW_HEADERS_PREFIX = 'Wow-';

  /**
   * Space identifier request header.
   *
   * On a command route it sets the command's space; on a query route it adds
   * a space filter. It is routing data, not proof that the caller may use
   * that space. The server ignores a blank value.
   */
  static readonly SPACE_ID = `${WowHeaders.WOW_HEADERS_PREFIX}Space-Id`;

  /**
   * Error code response header, `Ok` when the request succeeded
   */
  static readonly ERROR_CODE = `${WowHeaders.WOW_HEADERS_PREFIX}Error-Code`;
}
