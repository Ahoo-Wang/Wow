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

import { RecoverableType } from "@ahoo-wang/wow-client";
import type { RecordRow } from "@ahoo-wang/wow-view-engine";
import type {
  BulkSelection,
  RecordBulkActionContext,
} from "@ahoo-wang/wow-view-engine/react";
import { EllipsisVertical, ShieldAlert } from "lucide-react";
import { useEffect, useId, useState } from "react";
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
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { useI18n, type Message } from "@/i18n.tsx";
import {
  capabilitiesChangeAt,
  RECOVERABILITY,
  recoverabilityLabel,
  rowCapabilities,
  rowRecoverable,
  titleOf,
  type ExecutionCommand,
} from "./operability.ts";

/** A command waiting on the operator's confirmation. */
export interface PendingCommand {
  command: ExecutionCommand;
  /** What the run goes over. */
  selection: BulkSelection;
  /** The picked rows on the page, which the run checks before sending. */
  rows: readonly RecordRow[];
  /** Rows the console already knows will not take it, by reason. */
  refused: readonly { reason: Message; count: number }[];
}

export interface ConfirmCommandProps {
  pending: PendingCommand | null;
  onCancel(): void;
  onConfirm(pending: PendingCommand): void;
}

/**
 * The confirmation every bulk command asks for, and a row's force prepare
 * and recoverability: how many executions, what the command does beyond
 * the obvious, and which of them the console already knows it will not send.
 */
export function ConfirmCommand({
  pending,
  onCancel,
  onConfirm,
}: ConfirmCommandProps) {
  const { t } = useI18n();
  // Keep the words while the dialog animates closed, rather than blanking.
  const [shown, setShown] = useState(pending);
  if (pending && pending !== shown) setShown(pending);
  const open = pending !== null;
  if (!shown) return null;
  const { command, selection, refused } = shown;
  const count = selection.keys.length;
  const one = count === 1;
  const value =
    command.kind === "markRecoverable"
      ? t(recoverabilityLabel(command.recoverable))
      : "";
  const title =
    command.kind === "prepare"
      ? t(one ? "Prepare {count} execution?" : "Prepare {count} executions?", {
          count,
        })
      : command.kind === "forcePrepare"
        ? t(
            one
              ? "Force prepare {count} execution?"
              : "Force prepare {count} executions?",
            { count },
          )
        : t(
            one
              ? "Mark {count} execution as {value}?"
              : "Mark {count} executions as {value}?",
            { count, value },
          );
  const consequence: Message =
    command.kind === "prepare"
      ? "Each one is prepared within its retry spec."
      : command.kind === "forcePrepare"
        ? "This bypasses the retry limit. The server still validates each execution's state."
        : command.recoverable === RecoverableType.UNRECOVERABLE
          ? "The scheduler stops retrying unrecoverable executions."
          : "This changes whether the scheduler retries them.";
  const destructive =
    command.kind === "forcePrepare" ||
    (command.kind === "markRecoverable" &&
      command.recoverable === RecoverableType.UNRECOVERABLE);
  return (
    <AlertDialog
      open={open}
      onOpenChange={(next) => {
        if (!next) onCancel();
      }}
    >
      <AlertDialogContent>
        <AlertDialogHeader>
          {destructive ? (
            <AlertDialogMedia className="bg-destructive/10 text-destructive">
              <ShieldAlert />
            </AlertDialogMedia>
          ) : null}
          <AlertDialogTitle>{title}</AlertDialogTitle>
          <AlertDialogDescription>
            {t(consequence)}{" "}
            {t("The ones the server refuses stay selected, with its reason.")}
          </AlertDialogDescription>
        </AlertDialogHeader>
        {refused.length > 0 ? (
          <div className="text-sm text-muted-foreground">
            <p>{t("Not sent, and left selected:")}</p>
            <ul className="mt-1 list-disc pl-5">
              {refused.map(({ reason, count: times }) => (
                <li key={reason}>
                  {t("{reason} ({count})", {
                    reason: t(reason),
                    count: times,
                  })}
                </li>
              ))}
            </ul>
          </div>
        ) : null}
        <AlertDialogFooter>
          <AlertDialogCancel>{t("Cancel")}</AlertDialogCancel>
          <AlertDialogAction
            variant={destructive ? "destructive" : "default"}
            disabled={!open}
            onClick={() => onConfirm(shown)}
          >
            {titleOf(command, t)}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

/**
 * A row's capabilities, recomputed when they change on their own: a
 * prepared execution becomes preparable once it times out, so the row
 * redraws right after its `timeoutAt` rather than on a ticking clock.
 */
function useRowCapabilities(row: RecordRow) {
  const [now, setNow] = useState(Date.now);
  const changeAt = capabilitiesChangeAt(row, now);
  useEffect(() => {
    if (changeAt === null) return;
    const timer = window.setTimeout(
      () => setNow(Date.now()),
      Math.max(0, changeAt - Date.now()),
    );
    return () => window.clearTimeout(timer);
  }, [changeAt]);
  return rowCapabilities(row, now);
}

function RecoverabilityItems({
  current,
  onMark,
}: {
  current?: RecoverableType;
  onMark(value: RecoverableType): void;
}) {
  const { t } = useI18n();
  return (
    <DropdownMenuGroup>
      <DropdownMenuLabel>{t("Mark as")}</DropdownMenuLabel>
      {RECOVERABILITY.map(([value, label]) => (
        <DropdownMenuItem
          key={value}
          disabled={value === current}
          onClick={() => onMark(value)}
        >
          {t(label)}
        </DropdownMenuItem>
      ))}
    </DropdownMenuGroup>
  );
}

export interface RowCommandsProps {
  row: RecordRow;
  /** A command is running: nothing else starts until it settles. */
  running: boolean;
  onPrepare(): void;
  onConfirm(command: ExecutionCommand): void;
}

/**
 * An execution's commands in its row: prepare, which an operator presses
 * most, as a button; force prepare and recoverability behind "⋯". A command
 * the execution does not take now is disabled, and the reason is said on
 * the button (a tooltip, and its accessible description) and atop the menu,
 * where a keyboard reaches it.
 */
export function RowCommands({
  row,
  running,
  onPrepare,
  onConfirm,
}: RowCommandsProps) {
  const { t } = useI18n();
  const reasonId = useId();
  const capabilities = useRowCapabilities(row);
  const reason = capabilities.unavailableReason;
  const prepare = (
    <Button
      type="button"
      size="xs"
      variant="outline"
      disabled={running || !capabilities.canPrepare}
      aria-describedby={reason ? reasonId : undefined}
      onClick={onPrepare}
    >
      {t("Prepare")}
    </Button>
  );
  return (
    <>
      {reason ? (
        <>
          <span id={reasonId} className="sr-only">
            {t(reason)}
          </span>
          <Tooltip>
            {/* A disabled button takes no pointer; its box shows the tip. */}
            <TooltipTrigger render={<span className="inline-flex" />}>
              {prepare}
            </TooltipTrigger>
            <TooltipContent>{t(reason)}</TooltipContent>
          </Tooltip>
        </>
      ) : (
        prepare
      )}
      <DropdownMenu>
        <DropdownMenuTrigger
          render={
            <Button
              type="button"
              size="icon-xs"
              variant="ghost"
              aria-label={t("Actions for {id}", { id: String(row.key) })}
              disabled={running}
            />
          }
        >
          <EllipsisVertical />
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-56">
          {reason ? (
            <DropdownMenuGroup>
              <DropdownMenuLabel className="font-normal">
                {t(reason)}
              </DropdownMenuLabel>
            </DropdownMenuGroup>
          ) : null}
          <DropdownMenuGroup>
            <DropdownMenuItem
              variant="destructive"
              disabled={!capabilities.canForcePrepare}
              onClick={() => onConfirm({ kind: "forcePrepare" })}
            >
              <ShieldAlert />
              {t("Force prepare")}
            </DropdownMenuItem>
          </DropdownMenuGroup>
          <DropdownMenuSeparator />
          <RecoverabilityItems
            current={rowRecoverable(row)}
            onMark={(recoverable) =>
              onConfirm({ kind: "markRecoverable", recoverable })
            }
          />
        </DropdownMenuContent>
      </DropdownMenu>
    </>
  );
}

export interface BulkCommandsProps {
  context: RecordBulkActionContext;
  running: boolean;
  onConfirm(command: ExecutionCommand, context: RecordBulkActionContext): void;
}

/** The same commands over a selection, in the toolbar while rows are picked. */
export function BulkCommands({
  context,
  running,
  onConfirm,
}: BulkCommandsProps) {
  const { t } = useI18n();
  return (
    <>
      <Button
        type="button"
        size="sm"
        disabled={running}
        onClick={() => onConfirm({ kind: "prepare" }, context)}
      >
        {t("Prepare {count}", { count: context.keys.length })}
      </Button>
      <Button
        type="button"
        size="sm"
        variant="outline"
        disabled={running}
        onClick={() => onConfirm({ kind: "forcePrepare" }, context)}
      >
        {t("Force prepare")}
      </Button>
      <DropdownMenu>
        <DropdownMenuTrigger
          render={
            <Button
              type="button"
              size="sm"
              variant="outline"
              disabled={running}
            />
          }
        >
          {t("Mark recoverability")}
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start">
          <RecoverabilityItems
            onMark={(recoverable) =>
              onConfirm({ kind: "markRecoverable", recoverable }, context)
            }
          />
        </DropdownMenuContent>
      </DropdownMenu>
    </>
  );
}
