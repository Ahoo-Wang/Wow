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

import { useRef, useState } from 'react';

/** One pending metadata change at a time, shared by rename, order and delete controls. */
export function useViewManagerAction() {
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  async function execute(action: () => Promise<void>, after?: () => void) {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      await action();
      after?.();
    } catch (error) {
      setError(error instanceof Error ? error.message : '操作失败，请重试');
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }
  return { busy, busyRef, error, setError, execute };
}
export type ExecuteViewManagerAction = ReturnType<
  typeof useViewManagerAction
>['execute'];
