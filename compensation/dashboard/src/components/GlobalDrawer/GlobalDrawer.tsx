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

import { Drawer } from "antd";
import { useState, type ReactNode, useCallback, useMemo } from "react";
import { GlobalDrawerContext } from "./useGlobalDrawer";
import type { DrawerProps } from "antd/es/drawer";

interface GlobalDrawerProviderProps {
  children: ReactNode;
}

export function GlobalDrawerProvider({ children }: GlobalDrawerProviderProps) {
  const [drawerProps, setDrawerProps] = useState<DrawerProps>({
    open: false,
    width: "80vw",
  });

  const openDrawer = useCallback((config: DrawerProps) => {
    setDrawerProps({
      width: "80vw",
      ...config,
      open: true,
    });
  }, []);

  const closeDrawer = useCallback(() => {
    setDrawerProps((prev) => ({
      ...prev,
      children: null,
      open: false,
    }));
  }, []);

  const updateDrawer = useCallback((config: Partial<DrawerProps>) => {
    setDrawerProps((prev) => ({
      ...prev,
      ...config,
    }));
  }, []);
  const contextProps = useMemo(
    () => ({
      openDrawer,
      closeDrawer,
      updateDrawer,
    }),
    [openDrawer, closeDrawer, updateDrawer],
  );
  return (
    <GlobalDrawerContext.Provider value={contextProps}>
      {children}
      <Drawer {...drawerProps} onClose={closeDrawer} />
    </GlobalDrawerContext.Provider>
  );
}
