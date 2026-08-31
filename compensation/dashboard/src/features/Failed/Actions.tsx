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

import type { ExchangeError } from "@ahoo-wang/fetcher";
import { useExecutePromise } from "@ahoo-wang/fetcher-react";
import type { CommandResult, StateCapable } from "@ahoo-wang/fetcher-wow";
import {
  CircleCheck,
  Clipboard,
  EllipsisVertical,
  LoaderCircle,
  Play,
  ShieldAlert,
} from "lucide-react";
import { useId, useState } from "react";
import { toast } from "sonner";
import {
  ExecutionFailedStatus,
  type ExecutionFailedState,
} from "../../generated";
import { executionFailedCommandClient } from "../../services";
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
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { copyTextToClipboard } from "@/utils/clipboard.ts";
import type { OnChangedCapable } from "./types.ts";
import { commandErrorMessage } from "./commandErrors.ts";
import { getCompensationCapabilities } from "./compensationCapabilities.ts";
import { useI18n } from "@/i18n.tsx";

export interface ActionsProps
  extends StateCapable<ExecutionFailedState>, OnChangedCapable {
  disabled?: boolean;
}

export function Actions({ state, onChanged, disabled }: ActionsProps) {
  const { t } = useI18n();
  const [forceDialogOpen, setForceDialogOpen] = useState(false);
  const unavailableReasonId = useId();
  const stateCapabilities = getCompensationCapabilities(state);
  const capabilities = disabled
    ? {
        canForcePrepare: false,
        canPrepare: false,
        unavailableReason: "Refreshing current execution state." as const,
      }
    : stateCapabilities;

  const prepareState = useExecutePromise<CommandResult, ExchangeError>({
    onSuccess: () => {
      toast.success(t("Compensation prepared"));
      onChanged?.();
    },
    onError: async (error) => {
      toast.error(t("Prepare failed"), {
        description: await commandErrorMessage(error),
      });
    },
  });

  const forcePrepareState = useExecutePromise<CommandResult, ExchangeError>({
    onSuccess: () => {
      setForceDialogOpen(false);
      toast.success(t("Compensation force prepared"));
      onChanged?.();
    },
    onError: async (error) => {
      toast.error(t("Force prepare failed"), {
        description: await commandErrorMessage(error),
      });
    },
  });

  const prepare = () => {
    prepareState.execute((abortController) =>
      executionFailedCommandClient.prepareCompensation(state.id, {
        abortController,
      }),
    );
  };

  const forcePrepare = () => {
    forcePrepareState.execute((abortController) =>
      executionFailedCommandClient.forcePrepareCompensation(state.id, {
        abortController,
      }),
    );
  };

  const copyExecutionId = async () => {
    if (!(await copyTextToClipboard(state.id))) {
      toast.error(t("Unable to copy execution ID"));
      return;
    }
    toast.success(t("Execution ID copied"));
  };
  const busy = prepareState.loading || forcePrepareState.loading;
  const unavailableAction = disabled
    ? { label: t("Refreshing state"), icon: <LoaderCircle /> }
    : state.status === ExecutionFailedStatus.SUCCEEDED
      ? { label: t("Already succeeded"), icon: <CircleCheck /> }
      : { label: t("Retry limit reached"), icon: <ShieldAlert /> };

  return (
    <>
      <div className="flex w-full flex-col items-stretch gap-1.5 sm:w-auto sm:items-end">
        <div className="flex items-center gap-2">
          <Button
            type="button"
            variant={capabilities.canPrepare ? "default" : "outline"}
            className={
              capabilities.canPrepare
                ? "h-11 flex-1 bg-blue-600 px-6 text-white shadow-sm hover:bg-blue-700 sm:flex-none"
                : "h-11 flex-1 border-slate-200 bg-slate-50 px-4 text-slate-500 shadow-none sm:flex-none"
            }
            onClick={prepare}
            disabled={busy || !capabilities.canPrepare}
            aria-describedby={
              capabilities.unavailableReason ? unavailableReasonId : undefined
            }
          >
            {prepareState.loading ? (
              <LoaderCircle className="animate-spin motion-reduce:animate-none" />
            ) : !capabilities.canPrepare ? (
              unavailableAction.icon
            ) : (
              <Play />
            )}
            {capabilities.canPrepare
              ? t("Prepare compensation")
              : unavailableAction.label}
          </Button>
          <DropdownMenu>
            <DropdownMenuTrigger
              render={
                <Button
                  type="button"
                  variant="outline"
                  size="icon-lg"
                  className="h-11 w-12"
                  aria-label={t("More actions")}
                  disabled={busy}
                />
              }
            >
              <EllipsisVertical />
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-48 p-1.5">
              <DropdownMenuItem
                variant="destructive"
                className="py-2"
                disabled={disabled || !capabilities.canForcePrepare}
                onClick={() => setForceDialogOpen(true)}
              >
                <ShieldAlert />
                {t("Force prepare")}
              </DropdownMenuItem>
              <DropdownMenuItem className="py-2" onClick={copyExecutionId}>
                <Clipboard />
                {t("Copy execution ID")}
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
        {capabilities.unavailableReason ? (
          <p
            id={unavailableReasonId}
            className="max-w-72 text-right text-xs text-slate-500"
          >
            {t(capabilities.unavailableReason)}
          </p>
        ) : null}
      </div>

      <AlertDialog open={forceDialogOpen} onOpenChange={setForceDialogOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogMedia className="bg-red-50 text-red-600">
              <ShieldAlert />
            </AlertDialogMedia>
            <AlertDialogTitle>{t("Force prepare this execution?")}</AlertDialogTitle>
            <AlertDialogDescription>
              {t(
                "This bypasses the retry limit for {id}. The server still validates the current execution state. Use it only after verifying the failure context.",
                { id: state.id },
              )}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t("Cancel")}</AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              onClick={forcePrepare}
              disabled={busy || !capabilities.canForcePrepare}
            >
              {t("Force prepare")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}
