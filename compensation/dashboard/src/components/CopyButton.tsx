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
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { copyTextToClipboard } from "@/utils/clipboard.ts";
import { useI18n, type Message } from "@/i18n.tsx";

interface CopyButtonProps {
  value: string;
  label?: Message;
}

export function CopyButton({ value, label = "value" }: CopyButtonProps) {
  const [copied, setCopied] = useState(false);
  const { t } = useI18n();
  const translatedLabel = t(label);

  useEffect(() => {
    if (!copied) {
      return;
    }
    const timer = window.setTimeout(() => setCopied(false), 1400);
    return () => window.clearTimeout(timer);
  }, [copied]);

  const copy = async () => {
    if (!(await copyTextToClipboard(value))) {
      toast.error(t("Unable to copy {label}", { label: translatedLabel }));
      return;
    }
    setCopied(true);
  };

  return (
    <Tooltip>
      <TooltipTrigger
        render={
          <Button
            type="button"
            variant="ghost"
            size="icon-xs"
            aria-label={t("Copy {label}", { label: translatedLabel })}
            onClick={copy}
          />
        }
      >
        {copied ? <Check /> : <Copy />}
      </TooltipTrigger>
      <TooltipContent>
        {copied
          ? t("Copied")
          : t("Copy {label}", { label: translatedLabel })}
      </TooltipContent>
    </Tooltip>
  );
}
