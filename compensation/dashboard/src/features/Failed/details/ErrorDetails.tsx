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

import {
  ChevronDown,
  ChevronRight,
  Clipboard,
  Maximize2,
  Minimize2,
  WrapText,
} from "lucide-react";
import { useEffect, useId, useRef, useState } from "react";
import { toast } from "sonner";
import type { ErrorDetails as ErrorDetailsModel } from "../../../generated";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { copyTextToClipboard } from "@/utils/clipboard.ts";
import { StackTraceEditor } from "./StackTraceEditor.tsx";
import { useI18n } from "@/i18n.tsx";

export interface ErrorDetailsProps {
  error: ErrorDetailsModel;
  historical?: boolean;
  defaultExpanded?: boolean;
}

export function ErrorDetails({
  error,
  historical = false,
  defaultExpanded,
}: ErrorDetailsProps) {
  const { locale, t } = useI18n();
  const panelRef = useRef<HTMLDivElement>(null);
  const expandedContentRef = useRef<HTMLDivElement>(null);
  const [expanded, setExpanded] = useState(defaultExpanded ?? !historical);
  const previousExpandedRef = useRef(expanded);
  const [fullscreen, setFullscreen] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const [wrapLongLines, setWrapLongLines] = useState(true);
  const matchStatusId = useId();

  useEffect(() => {
    const update = () =>
      setFullscreen(document.fullscreenElement === panelRef.current);
    document.addEventListener("fullscreenchange", update);
    return () => document.removeEventListener("fullscreenchange", update);
  }, []);

  useEffect(() => {
    const shouldRevealContent = expanded && !previousExpandedRef.current;
    previousExpandedRef.current = expanded;
    if (shouldRevealContent) {
      expandedContentRef.current?.scrollIntoView({ block: "nearest" });
    }
  }, [expanded]);

  const toggleExpanded = async () => {
    if (!expanded) {
      setExpanded(true);
      return;
    }

    if (document.fullscreenElement === panelRef.current) {
      try {
        await document.exitFullscreen();
      } catch {
        toast.error(t("Unable to change fullscreen mode"));
        return;
      }
    }
    setExpanded(false);
  };

  const toggleFullscreen = async () => {
    try {
      if (document.fullscreenElement) {
        await document.exitFullscreen();
      } else {
        await panelRef.current?.requestFullscreen();
      }
    } catch {
      toast.error(t("Unable to change fullscreen mode"));
    }
  };

  const copy = async () => {
    if (!(await copyTextToClipboard(error.stackTrace))) {
      toast.error(t("Unable to copy stack trace"));
      return;
    }
    toast.success(t("Stack trace copied"));
  };
  const normalizedQuery = searchQuery.trim().toLocaleLowerCase();
  const matchCount = normalizedQuery
    ? error.stackTrace
        .split("\n")
        .filter((line) => line.toLocaleLowerCase().includes(normalizedQuery))
        .length
    : 0;
  const title = historical ? t("Last failure") : t("Stack trace");
  const actionTitle = locale === "en" ? title.toLowerCase() : title;
  const collapsible = historical || defaultExpanded !== undefined;

  return (
    <section
      ref={panelRef}
      className={
        expanded
          ? "flex min-h-[360px] flex-1 flex-col overflow-hidden rounded-lg border bg-white shadow-sm"
          : "flex flex-none flex-col overflow-hidden rounded-lg border bg-white shadow-sm"
      }
      aria-labelledby="stack-trace-title"
    >
      <div className="flex min-h-13 items-center justify-between border-b px-4">
        <div className="flex min-w-0 items-center gap-2">
          {collapsible ? (
            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              aria-label={
                expanded
                  ? t("Collapse {title}", { title: actionTitle })
                  : t("Expand {title}", { title: actionTitle })
              }
              aria-expanded={expanded}
              onClick={() => void toggleExpanded()}
            >
              {expanded ? <ChevronDown /> : <ChevronRight />}
            </Button>
          ) : null}
          <div className="min-w-0">
            <h2
              id="stack-trace-title"
              className="text-sm font-semibold text-slate-900"
            >
              {title}
            </h2>
            <p className="truncate text-xs text-slate-500">
              {error.errorCode}: {error.errorMsg}
            </p>
          </div>
        </div>
        <div className="flex items-center gap-1">
          <Tooltip>
            <TooltipTrigger
              render={
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  aria-label={t("Copy stack trace")}
                  onClick={copy}
                />
              }
            >
              <Clipboard />
            </TooltipTrigger>
            <TooltipContent>{t("Copy stack trace")}</TooltipContent>
          </Tooltip>
          {expanded ? (
            <Tooltip>
              <TooltipTrigger
                render={
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon-sm"
                    aria-label={
                      fullscreen ? t("Exit fullscreen") : t("Open fullscreen")
                    }
                    onClick={toggleFullscreen}
                  />
                }
              >
                {fullscreen ? <Minimize2 /> : <Maximize2 />}
              </TooltipTrigger>
              <TooltipContent>
                {fullscreen ? t("Exit fullscreen") : t("Open fullscreen")}
              </TooltipContent>
            </Tooltip>
          ) : null}
        </div>
      </div>
      {expanded ? (
        <div ref={expandedContentRef} className="flex min-h-0 flex-1 flex-col">
          <div className="flex flex-wrap items-center gap-2 border-b bg-slate-50 px-3 py-2">
            <div className="min-w-[180px] flex-1">
              <Input
                type="search"
                aria-label={t("Search stack trace")}
                aria-describedby={matchStatusId}
                placeholder={t("Search stack trace…")}
                className="h-8 bg-white"
                value={searchQuery}
                onChange={(event) => setSearchQuery(event.target.value)}
              />
            </div>
            <span
              id={matchStatusId}
              role="status"
              aria-live="polite"
              className="text-xs text-slate-500 tabular-nums"
            >
              {normalizedQuery
                ? t(matchCount === 1 ? "{count} match" : "{count} matches", {
                    count: matchCount,
                  })
                : ""}
            </span>
            <Button
              type="button"
              variant={wrapLongLines ? "secondary" : "ghost"}
              size="icon-sm"
              aria-label={
                wrapLongLines
                  ? t("Disable line wrapping")
                  : t("Enable line wrapping")
              }
              aria-pressed={wrapLongLines}
              onClick={() => setWrapLongLines((current) => !current)}
            >
              <WrapText />
            </Button>
          </div>
          <div className="min-h-[300px] flex-1 bg-[#1e1e1e]">
            <StackTraceEditor
              value={error.stackTrace}
              searchQuery={searchQuery}
              wrapLongLines={wrapLongLines}
            />
          </div>
        </div>
      ) : null}
    </section>
  );
}
