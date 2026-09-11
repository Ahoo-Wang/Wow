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

import { createContext, useContext, useState, type ReactNode } from 'react';

const OverlayVisibility = createContext(true);

/** Visibility follows the React tree, including nested portals, without resetting editor drafts. */
export function OverlayScope({
  visible,
  children,
}: {
  visible: boolean;
  children: ReactNode;
}) {
  const parent = useContext(OverlayVisibility);
  return (
    <OverlayVisibility.Provider value={parent && visible}>
      {children}
    </OverlayVisibility.Provider>
  );
}

export function useOverlayVisible() {
  return useContext(OverlayVisibility);
}

export function useOverlayOpen(defaultOpen = false) {
  const visible = useOverlayVisible();
  const [open, setOpen] = useState(defaultOpen);
  if (!visible && open) setOpen(false);
  return [visible && open, setOpen] as const;
}
