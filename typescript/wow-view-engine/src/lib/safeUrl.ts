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

/** Shared protocol policy for configured content and record links; React-independent. */
export function safeUrl(
  value: unknown,
  protocols: readonly string[] = ['http:', 'https:', 'mailto:', 'tel:'],
): string | undefined {
  if (typeof value !== 'string' || !value.trim()) return;
  try {
    const href = value.trim();
    const url = new URL(href, 'https://view-engine.invalid/');
    if (protocols.includes(url.protocol)) return href;
  } catch {
    // Invalid addresses remain non-interactive.
  }
}
