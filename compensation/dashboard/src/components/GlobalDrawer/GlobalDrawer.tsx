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

import { useState, type ReactNode, useCallback, useMemo } from "react";
import {
  GlobalDrawerContext,
  type GlobalDrawerOptions,
} from "./useGlobalDrawer";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";

interface GlobalDrawerProviderProps {
  children: ReactNode;
}

export function GlobalDrawerProvider({ children }: GlobalDrawerProviderProps) {
  const [open, setOpen] = useState(false);
  const [drawer, setDrawer] = useState<GlobalDrawerOptions | null>(null);

  const openDrawer = useCallback((config: GlobalDrawerOptions) => {
    setDrawer(config);
    setOpen(true);
  }, []);

  const closeDrawer = useCallback(() => {
    setOpen(false);
  }, []);

  const contextProps = useMemo(
    () => ({
      openDrawer,
      closeDrawer,
      isOpen: open,
    }),
    [open, openDrawer, closeDrawer],
  );
  const requestedWidth =
    typeof drawer?.width === "number" ? `${drawer.width}px` : drawer?.width;
  return (
    <GlobalDrawerContext.Provider value={contextProps}>
      {children}
      <Sheet open={open} onOpenChange={setOpen}>
        <SheetContent
          className="w-[min(92vw,560px)] gap-0 overflow-y-auto sm:max-w-none"
          style={{ width: requestedWidth, maxWidth: "92vw" }}
        >
          <SheetHeader className="border-b px-6 py-5">
            <SheetTitle>{drawer?.title}</SheetTitle>
            {drawer?.description ? (
              <SheetDescription>{drawer.description}</SheetDescription>
            ) : null}
          </SheetHeader>
          <div className="flex-1 px-6 py-5">{drawer?.children}</div>
        </SheetContent>
      </Sheet>
    </GlobalDrawerContext.Provider>
  );
}
