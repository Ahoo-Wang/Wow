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

import { createContext, useContext } from "react";
import type { DrawerProps } from "antd/es/drawer";

interface GlobalDrawerContextProps {
  openDrawer: (config: DrawerProps) => void;
  closeDrawer: () => void;
  updateDrawer: (config: Partial<DrawerProps>) => void;
}


export const GlobalDrawerContext = createContext<GlobalDrawerContextProps | null>(
  null,
);

export function useGlobalDrawer() {
  const context = useContext(GlobalDrawerContext);
  if (!context) {
    throw new Error(
      "useGlobalDrawer must be used within a GlobalDrawerProvider",
    );
  }
  return context;
}