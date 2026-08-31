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

import type { CommandResult } from "@ahoo-wang/fetcher-wow";
import { FunctionKind, type FunctionInfo } from "@ahoo-wang/fetcher-wow";
import type { ChangeFunction as ChangeFunctionCommand } from "../../generated";
import { executionFailedCommandClient } from "../../services";
import type { OnChangedCapable } from "./types.ts";
import { useGlobalDrawer } from "../../components/GlobalDrawer";
import { useExecutePromise } from "@ahoo-wang/fetcher-react";
import type { ExchangeError } from "@ahoo-wang/fetcher";
import { useState, type FormEvent } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { commandErrorMessage } from "./commandErrors.ts";
import { CopyButton } from "@/components/CopyButton";
import { useI18n } from "@/i18n.tsx";

export interface ChangeFunctionProps extends OnChangedCapable {
  id: string;
  functionInfo: FunctionInfo;
}

interface FunctionDraft {
  contextName: string;
  processorName: string;
  name: string;
  functionKind: FunctionKind;
}

export function ChangeFunction({
  id,
  functionInfo,
  onChanged,
}: ChangeFunctionProps) {
  const { t } = useI18n();
  const { closeDrawer } = useGlobalDrawer();
  const [draft, setDraft] = useState<FunctionDraft>({ ...functionInfo });
  const promiseState = useExecutePromise<CommandResult, ExchangeError>({
    onSuccess: () => {
      toast.success(t("Function updated"));
      closeDrawer();
      onChanged?.();
    },
    onError: async (error) => {
      toast.error(t("Failed to change function"), {
        description: await commandErrorMessage(error),
      });
    },
  });

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const values: ChangeFunctionCommand = {
      contextName: draft.contextName.trim(),
      processorName: draft.processorName.trim(),
      name: draft.name.trim(),
      functionKind: draft.functionKind,
    };
    promiseState.execute((abortController) =>
      executionFailedCommandClient.changeFunction(id, {
        body: values,
        abortController,
      }),
    );
  };
  const normalizedDraft: ChangeFunctionCommand = {
    contextName: draft.contextName.trim(),
    processorName: draft.processorName.trim(),
    name: draft.name.trim(),
    functionKind: draft.functionKind,
  };
  const dirty =
    normalizedDraft.contextName !== functionInfo.contextName ||
    normalizedDraft.processorName !== functionInfo.processorName ||
    normalizedDraft.name !== functionInfo.name ||
    normalizedDraft.functionKind !== functionInfo.functionKind;
  const valid =
    normalizedDraft.contextName.length > 0 &&
    normalizedDraft.processorName.length > 0 &&
    normalizedDraft.name.length > 0;

  return (
    <form className="space-y-5" onSubmit={submit}>
      <div className="space-y-2">
        <Label htmlFor="function-id">{t("Execution ID")}</Label>
        <div className="relative">
          <Input
            id="function-id"
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
        <Label htmlFor="context-name">{t("Context name")}</Label>
        <Input
          id="context-name"
          name="contextName"
          required
          value={draft.contextName}
          onChange={(event) =>
            setDraft((current) => ({
              ...current,
              contextName: event.target.value,
            }))
          }
        />
      </div>
      <div className="space-y-2">
        <Label htmlFor="processor-name">{t("Processor name")}</Label>
        <Input
          id="processor-name"
          name="processorName"
          required
          value={draft.processorName}
          onChange={(event) =>
            setDraft((current) => ({
              ...current,
              processorName: event.target.value,
            }))
          }
        />
      </div>
      <div className="space-y-2">
        <Label htmlFor="function-name">{t("Function name")}</Label>
        <Input
          id="function-name"
          name="name"
          required
          value={draft.name}
          onChange={(event) =>
            setDraft((current) => ({
              ...current,
              name: event.target.value,
            }))
          }
        />
      </div>
      <div className="space-y-2">
        <Label htmlFor="function-kind">{t("Function kind")}</Label>
        <Select
          name="functionKind"
          value={draft.functionKind}
          onValueChange={(value) =>
            setDraft((current) => ({
              ...current,
              functionKind: value as FunctionKind,
            }))
          }
        >
          <SelectTrigger id="function-kind" className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={FunctionKind.EVENT}>EVENT</SelectItem>
            <SelectItem value={FunctionKind.STATE_EVENT}>
              STATE_EVENT
            </SelectItem>
          </SelectContent>
        </Select>
      </div>
      <Button
        type="submit"
        className="w-full"
        disabled={promiseState.loading || !dirty || !valid}
      >
        {promiseState.loading ? t("Saving…") : t("Save function")}
      </Button>
    </form>
  );
}
