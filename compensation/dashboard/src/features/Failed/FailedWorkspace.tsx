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

import { useRef, useState, type ReactNode } from "react";
import {
  ResizableHandle,
  ResizablePanel,
  ResizablePanelGroup,
} from "@/components/ui/resizable";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import { useI18n } from "@/i18n.tsx";

const SPLIT_LAYOUT_KEY = "compensation-dashboard:failed-view-layout";
const DEFAULT_SPLIT_LAYOUT = {
  "execution-list": 40,
  "execution-details": 60,
};

interface FailedWorkspaceProps {
  desktop: boolean;
  details: ReactNode;
  detailsOpen: boolean;
  master: ReactNode;
  onCloseDetails: () => void;
}

function loadSplitLayout(): Record<string, number> {
  try {
    const stored = window.localStorage.getItem(SPLIT_LAYOUT_KEY);
    if (!stored) {
      return DEFAULT_SPLIT_LAYOUT;
    }
    const layout = JSON.parse(stored) as Record<string, unknown>;
    const list = layout["execution-list"];
    const details = layout["execution-details"];
    if (
      typeof list !== "number" ||
      typeof details !== "number" ||
      list < 30 ||
      list > 52 ||
      Math.abs(list + details - 100) > 0.1
    ) {
      return DEFAULT_SPLIT_LAYOUT;
    }
    return { "execution-list": list, "execution-details": details };
  } catch {
    return DEFAULT_SPLIT_LAYOUT;
  }
}

function saveSplitLayout(layout: Record<string, number>) {
  try {
    window.localStorage.setItem(SPLIT_LAYOUT_KEY, JSON.stringify(layout));
  } catch {
    // Persisting a UI preference must never interrupt the workspace.
  }
}

export function FailedWorkspace({
  desktop,
  details,
  detailsOpen,
  master,
  onCloseDetails,
}: FailedWorkspaceProps) {
  const { t } = useI18n();
  const mobileDetailsFocusRef = useRef<HTMLDivElement>(null);
  const [splitLayout] = useState(loadSplitLayout);

  if (!desktop) {
    return (
      <div className="h-full min-h-0">
        {master}
        <Sheet
          open={detailsOpen}
          onOpenChange={(open) => {
            if (!open) {
              onCloseDetails();
            }
          }}
        >
          <SheetContent
            className="w-full gap-0 p-0 sm:max-w-none"
            style={{ width: "100%", maxWidth: "none" }}
            showCloseButton
            initialFocus={mobileDetailsFocusRef}
          >
            <SheetHeader className="sr-only">
              <SheetTitle>{t("Execution failed details")}</SheetTitle>
              <SheetDescription>
                {t("Inspect context and prepare compensation.")}
              </SheetDescription>
            </SheetHeader>
            <div
              ref={mobileDetailsFocusRef}
              tabIndex={-1}
              aria-label={t("Execution details panel")}
              className="min-h-0 flex-1 overflow-hidden outline-none"
            >
              {details}
            </div>
          </SheetContent>
        </Sheet>
      </div>
    );
  }

  return (
    <ResizablePanelGroup
      id="failed-executions-layout"
      orientation="horizontal"
      className="h-full min-h-0"
      defaultLayout={splitLayout}
      onLayoutChanged={(layout, meta) => {
        if (meta.isUserInteraction) {
          saveSplitLayout(layout);
        }
      }}
    >
      <ResizablePanel id="execution-list" minSize="30" maxSize="52">
        {master}
      </ResizablePanel>
      <ResizableHandle
        withHandle
        aria-label={t("Resize execution list and details")}
        className="z-20 bg-slate-200"
      />
      <ResizablePanel id="execution-details" minSize="48">
        <div className="h-full min-h-0">{details}</div>
      </ResizablePanel>
    </ResizablePanelGroup>
  );
}
