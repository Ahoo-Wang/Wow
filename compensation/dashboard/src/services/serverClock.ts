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

interface ServerClockAnchor {
  monotonicTime: number;
  serverTime: number;
}

let anchor: ServerClockAnchor | undefined;

function monotonicNow(): number {
  return globalThis.performance?.now() ?? Date.now();
}

export function synchronizeServerClock(
  serverTime: number,
  monotonicTime = monotonicNow(),
): void {
  if (!Number.isSafeInteger(serverTime) || serverTime < 0) {
    throw new Error("Dashboard runtime returned an invalid serverTime value.");
  }
  anchor = { serverTime, monotonicTime };
}

export function serverNow(monotonicTime = monotonicNow()): number | undefined {
  if (!anchor) {
    return undefined;
  }
  const elapsed = Math.floor(Math.max(0, monotonicTime - anchor.monotonicTime));
  return anchor.serverTime + elapsed;
}

export function resetServerClock(): void {
  anchor = undefined;
}
