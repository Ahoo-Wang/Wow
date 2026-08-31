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
import { type Identifier, RecoverableType } from "@ahoo-wang/fetcher-wow";
import { executionFailedCommandClient } from "../../services";
import type { OnChangedCapable } from "./types.ts";
import type { MarkRecoverable } from "../../generated";
import { useExecutePromise } from "@ahoo-wang/fetcher-react";
import type { ExchangeError } from "@ahoo-wang/fetcher";
import { ShieldAlert } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogMedia,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { commandErrorMessage } from "./commandErrors.ts";
import { useI18n } from "@/i18n.tsx";

export interface MarkRecoverableProps
  extends Identifier, MarkRecoverable, OnChangedCapable {
  disabled?: boolean;
}

export function MarkRecoverable({
  id,
  recoverable,
  onChanged,
  disabled,
}: MarkRecoverableProps) {
  const { t } = useI18n();
  const label = (value: RecoverableType) =>
    t(
      value === RecoverableType.RECOVERABLE
        ? "Recoverable"
        : value === RecoverableType.UNRECOVERABLE
          ? "Unrecoverable"
          : "Unknown",
    );
  const [pending, setPending] = useState<RecoverableType>();
  const promiseState = useExecutePromise<CommandResult, ExchangeError>({
    onSuccess: () => {
      toast.success(t("Recoverability updated"));
      setPending(undefined);
      onChanged?.();
    },
    onError: async (error) => {
      toast.error(t("Failed to update recoverability"), {
        description: await commandErrorMessage(error),
      });
    },
  });
  const change = (recoverable: RecoverableType) => {
    promiseState.execute(async (abortController) => {
      return executionFailedCommandClient.markRecoverable(id, {
        body: { recoverable },
        abortController,
      });
    });
  };

  return (
    <>
      <Select
        value={recoverable}
        onValueChange={(value) => setPending(value as RecoverableType)}
        disabled={disabled || promiseState.loading}
      >
        <SelectTrigger size="sm" className="min-w-28" aria-label={t("Recoverable")}>
          <SelectValue>
            {label(recoverable)}
          </SelectValue>
        </SelectTrigger>
        <SelectContent>
          {Object.values(RecoverableType).map((value) => (
            <SelectItem key={value} value={value}>
              {label(value)}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      <AlertDialog
        open={pending !== undefined}
        onOpenChange={(open) => {
          if (!open) {
            setPending(undefined);
          }
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogMedia className="bg-amber-50 text-amber-700">
              <ShieldAlert />
            </AlertDialogMedia>
            <AlertDialogTitle>{t("Change recoverability?")}</AlertDialogTitle>
            <AlertDialogDescription>
              {t("This changes execution eligibility from {from} to {to}.", {
                from: label(recoverable),
                to: pending ? label(pending) : "",
              })}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t("Cancel")}</AlertDialogCancel>
            <AlertDialogAction
              onClick={(event) => {
                event.preventDefault();
                if (pending) {
                  change(pending);
                }
              }}
              disabled={disabled || !pending || promiseState.loading}
            >
              {t("Confirm change")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}
