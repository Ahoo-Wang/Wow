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

import { Check, Copy } from "lucide-react";
import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { useI18n } from "@/i18n.tsx";
import { copyTextToClipboard } from "@/utils/clipboard.ts";

/**
 * An identifier and the button that copies it, for a search or a ticket —
 * over plain HTTP too, where the Clipboard API is not there. The button is
 * named by what it copies (「复制 EF-01」) and says when it did.
 */
export function CopyValue({ value }: { value: string }) {
  const { t } = useI18n();
  const [copied, setCopied] = useState<"copied" | "failed" | null>(null);
  useEffect(() => {
    if (copied === null) return;
    const timer = window.setTimeout(() => setCopied(null), 1500);
    return () => window.clearTimeout(timer);
  }, [copied]);
  const label =
    copied === "copied"
      ? t("Copied")
      : copied === "failed"
        ? t("Unable to copy {value}", { value })
        : t("Copy {value}", { value });
  return (
    <span className="inline-flex min-w-0 items-center gap-1">
      <span className="min-w-0 [overflow-wrap:anywhere]">{value}</span>
      <Button
        type="button"
        variant="ghost"
        size="icon-xs"
        aria-label={label}
        onClick={() =>
          void copyTextToClipboard(value).then((done) =>
            setCopied(done ? "copied" : "failed"),
          )
        }
      >
        {copied === "copied" ? <Check /> : <Copy />}
      </Button>
    </span>
  );
}
