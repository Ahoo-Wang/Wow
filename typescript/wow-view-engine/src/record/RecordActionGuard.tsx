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
import type { ReactNode, SyntheticEvent } from 'react';
/** Capture also guards React portals; inert alone only protects physical DOM descendants. */
export function RecordActionGuard({
  disabled,
  isCurrent,
  children,
}: {
  disabled?: boolean;
  isCurrent?(): boolean;
  children: ReactNode;
}) {
  const guard = (event: SyntheticEvent) => {
    if (disabled || isCurrent?.() === false) {
      event.preventDefault();
      event.stopPropagation();
    }
  };
  // Unmount stale extensions, including their portals, so dialogs cannot trap focus.
  if (disabled) return <span aria-disabled="true">当前结果操作暂不可用</span>;
  return (
    <span
      className="fve:contents"
      inert={disabled || undefined}
      aria-disabled={disabled || undefined}
      onClickCapture={guard}
      onPointerDownCapture={guard}
      onKeyDownCapture={guard}
      onSubmitCapture={guard}
    >
      {children}
    </span>
  );
}
