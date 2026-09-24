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

import { issue } from '../filter/index.js';
import type { Issue, ViewKind } from '../model/index.js';

// The one shape every command failure is rendered in. It moved to the
// runtime, which needs it too; this layer keeps the name it always had.
export { toIssue } from '../runtime/index.js';

/**
 * The reason a view that opened cannot be drawn here, or nothing.
 *
 * One data definition holds record and analysis instances together, so a host
 * may name either of them to either workbench. The one that cannot render it
 * says so where every other failure to open is already reported — a body it
 * has no kernel for is an empty page, and an empty page explains nothing.
 */
export function kindMismatch(
  runtime: { kind: ViewKind } | null,
  drawn: readonly ViewKind[],
): Issue | null {
  return runtime && !drawn.includes(runtime.kind)
    ? issue('view.open.wrong-kind', [], { kind: runtime.kind })
    : null;
}
