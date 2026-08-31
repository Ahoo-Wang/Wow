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

import type {
  ApplyRetrySpec as ApplyRetrySpecCommand,
  RetrySpec,
} from "../../generated";
import { executionFailedCommandClient } from "../../services";
import { useGlobalDrawer } from "../../components/GlobalDrawer";
import type { OnChangedCapable } from "./types.ts";
import { useExecutePromise } from "@ahoo-wang/fetcher-react";
import type { CommandResult } from "@ahoo-wang/fetcher-wow";
import type { ExchangeError } from "@ahoo-wang/fetcher";
import { useState, type FormEvent } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { commandErrorMessage } from "./commandErrors.ts";
import { CopyButton } from "@/components/CopyButton";
import { formatSeconds } from "@/utils/durations.ts";
import { useI18n } from "@/i18n.tsx";

const INT32_MAX = 2_147_483_647;

export interface ApplyRetrySpecProps extends OnChangedCapable {
  id: string;
  retrySpec: RetrySpec;
}

interface RetrySpecDraft {
  executionTimeout: string;
  maxRetries: string;
  minBackoff: string;
}

export function ApplyRetrySpec({
  id,
  retrySpec,
  onChanged,
}: ApplyRetrySpecProps) {
  const { locale, t } = useI18n();
  const [draft, setDraft] = useState<RetrySpecDraft>({
    maxRetries: String(retrySpec.maxRetries),
    minBackoff: String(retrySpec.minBackoff),
    executionTimeout: String(retrySpec.executionTimeout),
  });
  const { closeDrawer } = useGlobalDrawer();
  const promiseState = useExecutePromise<CommandResult, ExchangeError>({
    onSuccess: () => {
      toast.success(t("Retry specification updated"));
      closeDrawer();
      onChanged?.();
    },
    onError: async (error) => {
      toast.error(t("Failed to apply retry specification"), {
        description: await commandErrorMessage(error),
      });
    },
  });

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!valid) {
      return;
    }
    const values: ApplyRetrySpecCommand = {
      maxRetries: Number(draft.maxRetries),
      minBackoff: Number(draft.minBackoff),
      executionTimeout: Number(draft.executionTimeout),
    };
    promiseState.execute((abortController) =>
      executionFailedCommandClient.applyRetrySpec(id, {
        body: values,
        abortController,
      }),
    );
  };
  const dirty =
    Number(draft.maxRetries) !== retrySpec.maxRetries ||
    Number(draft.minBackoff) !== retrySpec.minBackoff ||
    Number(draft.executionTimeout) !== retrySpec.executionTimeout;
  const valid = Object.values(draft).every(
    (value) =>
      value !== "" &&
      Number.isSafeInteger(Number(value)) &&
      Number(value) >= 0 &&
      Number(value) <= INT32_MAX,
  );

  return (
    <form className="space-y-5" onSubmit={submit}>
      <div className="space-y-2">
        <Label htmlFor="retry-id">{t("Execution ID")}</Label>
        <div className="relative">
          <Input
            id="retry-id"
            value={id}
            readOnly
            className="pr-10 font-mono text-xs"
          />
          <div className="absolute top-1/2 right-2 -translate-y-1/2">
            <CopyButton value={id} label="execution ID" />
          </div>
        </div>
      </div>
      <div className="space-y-2">
        <Label htmlFor="max-retries">{t("Max retries")}</Label>
        <Input
          id="max-retries"
          name="maxRetries"
          type="number"
          min={0}
          max={INT32_MAX}
          step={1}
          required
          value={draft.maxRetries}
          onChange={(event) =>
            setDraft((current) => ({
              ...current,
              maxRetries: event.target.value,
            }))
          }
        />
      </div>
      <div className="space-y-2">
        <Label htmlFor="min-backoff">{t("Min backoff (s)")}</Label>
        <Input
          id="min-backoff"
          name="minBackoff"
          type="number"
          min={0}
          max={INT32_MAX}
          step={1}
          required
          aria-describedby="min-backoff-preview"
          value={draft.minBackoff}
          onChange={(event) =>
            setDraft((current) => ({
              ...current,
              minBackoff: event.target.value,
            }))
          }
        />
        <p id="min-backoff-preview" className="text-xs text-slate-500">
          {draft.minBackoff
            ? formatSeconds(Number(draft.minBackoff), locale)
            : t("Enter a duration")}
        </p>
      </div>
      <div className="space-y-2">
        <Label htmlFor="execution-timeout">{t("Execution timeout (s)")}</Label>
        <Input
          id="execution-timeout"
          name="executionTimeout"
          type="number"
          min={0}
          max={INT32_MAX}
          step={1}
          required
          aria-describedby="execution-timeout-preview"
          value={draft.executionTimeout}
          onChange={(event) =>
            setDraft((current) => ({
              ...current,
              executionTimeout: event.target.value,
            }))
          }
        />
        <p id="execution-timeout-preview" className="text-xs text-slate-500">
          {draft.executionTimeout
            ? formatSeconds(Number(draft.executionTimeout), locale)
            : t("Enter a duration")}
        </p>
      </div>
      <Button
        type="submit"
        className="w-full"
        disabled={promiseState.loading || !dirty || !valid}
      >
        {promiseState.loading ? t("Applying…") : t("Apply retry spec")}
      </Button>
    </form>
  );
}
