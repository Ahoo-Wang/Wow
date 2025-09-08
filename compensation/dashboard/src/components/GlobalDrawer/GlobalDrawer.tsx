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

import type { DrawerProps } from "antd/es/drawer";
import { Drawer } from "antd";
import { createContext, useContext, useState, type ReactNode } from "react";

interface GlobalDrawerContextProps {
  openDrawer: (config: DrawerConfig) => void;
  closeDrawer: () => void;
  updateDrawer: (config: Partial<DrawerConfig>) => void;
}

interface DrawerConfig extends DrawerProps {
  content?: ReactNode;
}

const GlobalDrawerContext = createContext<GlobalDrawerContextProps | null>(
  null,
);

interface GlobalDrawerProviderProps {
  children: ReactNode;
}

export function GlobalDrawerProvider({ children }: GlobalDrawerProviderProps) {
  const [drawerConfig, setDrawerConfig] = useState<DrawerConfig>({
    open: false,
    width: "80vw",
    content: null,
  });

  const openDrawer = (config: DrawerConfig) => {
    setDrawerConfig({
      width: "80vw",
      ...config,
      open: true,
    });
  };

  const closeDrawer = () => {
    setDrawerConfig((prev) => ({
      ...prev,
      open: false,
    }));
  };

  const updateDrawer = (config: Partial<DrawerConfig>) => {
    setDrawerConfig((prev) => ({
      ...prev,
      ...config,
    }));
  };

  return (
    <GlobalDrawerContext.Provider
      value={{
        openDrawer,
        closeDrawer,
        updateDrawer,
      }}
    >
      {children}
      <Drawer {...drawerConfig} onClose={closeDrawer}>
        {drawerConfig.content}
      </Drawer>
    </GlobalDrawerContext.Provider>
  );
}

export function useGlobalDrawer() {
  const context = useContext(GlobalDrawerContext);
  if (!context) {
    throw new Error(
      "useGlobalDrawer must be used within a GlobalDrawerProvider",
    );
  }
  return context;
}
