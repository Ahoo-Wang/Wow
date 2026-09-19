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

import type * as React from 'react';

export interface RowActionsProps {
  children?: React.ReactNode;
}

/**
 * The wrapper a host's per-row actions land in.
 *
 * A table cell and a card footer are very different boxes, and the buttons a
 * host hands over are its own. This layer is what keeps them looking alike in
 * both: the same gap, the same edge to align on, and one `data-slot` a test
 * or a host stylesheet can address.
 */
export function RowActions({ children }: RowActionsProps) {
  return (
    <div
      data-slot="row-actions"
      className="flex items-center justify-end gap-1"
    >
      {children}
    </div>
  );
}
