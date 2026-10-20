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

/* eslint-disable react-refresh/only-export-components -- The parts and the hook they save with live together. */

import { useCallback, useState, type ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { useI18n } from "@/i18n.tsx";
import { problemText } from "@/views/catalogCommands.ts";

export function Section({
  title,
  hint,
  children,
}: {
  title: string;
  hint?: string;
  children: ReactNode;
}) {
  return (
    <section className="flex flex-col gap-2" aria-label={title}>
      <h3 className="text-sm font-semibold">{title}</h3>
      {hint ? <p className="text-xs text-muted-foreground">{hint}</p> : null}
      {children}
    </section>
  );
}

export function Facts({ rows }: { rows: [string, ReactNode][] }) {
  const { t } = useI18n();
  return (
    <dl className="grid grid-cols-[minmax(8rem,auto)_1fr] gap-x-4 gap-y-1 text-sm">
      {rows.map(([label, value]) => (
        <div key={label} className="contents">
          <dt className="text-muted-foreground">{label}</dt>
          <dd className="min-w-0 break-words">
            {value === undefined || value === null || value === ""
              ? t("none")
              : value}
          </dd>
        </div>
      ))}
    </dl>
  );
}

/** A list of the service's sentences, or nothing without one. */
export function Lines({
  title,
  lines,
}: {
  title: string;
  lines: readonly string[];
}) {
  if (lines.length === 0) return null;
  return (
    <Section title={title}>
      <ul className="list-disc pl-5 text-sm">
        {lines.map((line) => (
          <li key={line}>{line}</li>
        ))}
      </ul>
    </Section>
  );
}

/** `yes` or `no`, in the console's language. */
export function useYesNo(): (value: unknown) => string {
  const { t } = useI18n();
  return (value) => (value === true ? t("yes") : t("no"));
}

export interface Saving {
  /** Runs `command`, then reads the record again; its refusal is said. */
  save(command: () => Promise<unknown>): void;
  busy: boolean;
  /** The service's refusal of the last save, in its words. */
  error: string | null;
  saved: boolean;
}

/** A detail's edits: one at a time, the record read again after each. */
export function useSaving(refresh: () => void): Saving {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const save = useCallback(
    (command: () => Promise<unknown>) => {
      setBusy(true);
      setError(null);
      setSaved(false);
      command().then(
        () => {
          setBusy(false);
          setSaved(true);
          refresh();
        },
        (cause: unknown) => {
          setBusy(false);
          setError(problemText(cause));
        },
      );
    },
    [refresh],
  );
  return { save, busy, error, saved };
}

/** A form's save button, and what came of the last save. */
export function SaveBar({
  saving,
  disabled,
  label,
}: {
  saving: Saving;
  disabled?: boolean;
  label?: string;
}) {
  const { t } = useI18n();
  return (
    <div className="flex flex-wrap items-center gap-3">
      <Button type="submit" size="sm" disabled={disabled || saving.busy}>
        {saving.busy ? t("sending") : (label ?? t("send"))}
      </Button>
      {saving.error ? (
        <p role="alert" className="text-sm text-destructive">
          {saving.error}
        </p>
      ) : saving.saved ? (
        <p role="status" className="text-sm text-muted-foreground">
          {t("saved")}
        </p>
      ) : null}
    </div>
  );
}

/** The input style of the shadcn input, for the native controls beside it. */
export const CONTROL =
  "h-8 rounded-lg border border-input bg-transparent px-2 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:opacity-50";
