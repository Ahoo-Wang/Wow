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
  Check,
  ChevronDown,
  ChevronUp,
  Clipboard,
  WrapText,
} from "lucide-react";
import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { StackTraceEditor } from "./StackTraceEditor.tsx";
import { useI18n } from "@/i18n.tsx";
import { copyTextToClipboard } from "@/utils/clipboard.ts";

type Copied = "copied" | "failed" | null;

/**
 * The lines a trace shows before it is asked for more: the exception and
 * the frames that threw it, which is what tells one failure from another.
 */
export const TRACE_HEAD = 6;

/**
 * A failure's stack trace, read the way an operator reads one: numbered
 * lines, Java highlighted, in a box of its own height so a trace of a
 * thousand lines scrolls inside it rather than stretching the detail, with
 * long lines wrapped or scrolled sideways, and the whole of it copied in one
 * press — over plain HTTP too, where the Clipboard API is not there. It
 * opens on its head (`TRACE_HEAD`): the rest of a trace of 168 lines is
 * framework, read when someone asks for it.
 */
export function StackTrace({ value }: { value: string }) {
  const { t } = useI18n();
  const [wrap, setWrap] = useState(true);
  const [whole, setWhole] = useState(false);
  const [copied, setCopied] = useState<Copied>(null);
  useEffect(() => {
    if (copied === null) return;
    const timer = window.setTimeout(() => setCopied(null), 1500);
    return () => window.clearTimeout(timer);
  }, [copied]);

  if (value.trim() === "")
    return (
      <p className="text-sm text-muted-foreground">{t("No stack trace")}</p>
    );

  const copyLabel =
    copied === "copied"
      ? t("Stack trace copied")
      : copied === "failed"
        ? t("Unable to copy stack trace")
        : t("Copy stack trace");
  const all = value.split("\n");
  const lines = all.length;
  const cut = !whole && lines > TRACE_HEAD;
  const shown = cut ? all.slice(0, TRACE_HEAD).join("\n") : value;
  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-xs text-muted-foreground tabular-nums">
          {t(lines === 1 ? "{count} line" : "{count} lines", { count: lines })}
        </span>
        <span className="flex-1" />
        <Button
          type="button"
          variant="ghost"
          size="sm"
          aria-pressed={wrap}
          onClick={() => setWrap((current) => !current)}
        >
          <WrapText data-icon="inline-start" />
          {t("Wrap lines")}
        </Button>
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={() =>
            void copyTextToClipboard(value).then((done) =>
              setCopied(done ? "copied" : "failed"),
            )
          }
        >
          {copied === "copied" ? (
            <Check data-icon="inline-start" />
          ) : (
            <Clipboard data-icon="inline-start" />
          )}
          {copyLabel}
        </Button>
        {copied !== null && (
          <span role="status" className="sr-only">
            {copyLabel}
          </span>
        )}
      </div>
      {/* The trace scrolls in its own region, which the keyboard reaches. */}
      <div className="overflow-hidden rounded-md border [&>[role=region]]:max-h-96">
        <StackTraceEditor value={shown} wrapLongLines={wrap} />
      </div>
      {lines > TRACE_HEAD && (
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="self-start"
          aria-expanded={whole}
          onClick={() => setWhole((current) => !current)}
        >
          {whole ? (
            <ChevronUp data-icon="inline-start" />
          ) : (
            <ChevronDown data-icon="inline-start" />
          )}
          {whole
            ? t("Show the first lines only")
            : t("Show all {count} lines", { count: lines })}
        </Button>
      )}
    </div>
  );
}
