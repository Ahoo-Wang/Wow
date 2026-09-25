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

import { FunctionKind } from "@ahoo-wang/wow-client";
import { useId, useState, type FormEvent, type ReactNode } from "react";
import { toast } from "sonner";
import type { ApplyRetrySpec, ChangeFunction } from "@/generated";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { useI18n, type Message } from "@/i18n.tsx";
import { formatSeconds } from "@/utils/durations.ts";
import { useCommandForm, type CommandForm } from "./useCommandForm.ts";

const INT32_MAX = 2_147_483_647;

/** The kinds a compensated function can be: it handles an event. */
const KINDS = [FunctionKind.EVENT, FunctionKind.STATE_EVENT] as const;

function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: ReactNode;
  children(id: string, hintId: string | undefined): ReactNode;
}) {
  const id = useId();
  const hintId = hint === undefined ? undefined : `${id}-hint`;
  return (
    <div className="grid content-start gap-1.5">
      <Label htmlFor={id}>{label}</Label>
      {children(id, hintId)}
      {hint !== undefined && (
        <p id={hintId} className="text-xs text-muted-foreground">
          {hint}
        </p>
      )}
    </div>
  );
}

/** The send button, and the service's reason when it refused. */
function Submit({
  form,
  disabled,
  label,
  sendingLabel,
}: {
  form: CommandForm;
  disabled: boolean;
  label: string;
  sendingLabel: string;
}) {
  return (
    <div className="grid gap-2">
      {form.refused !== null && (
        <Alert variant="destructive">
          <AlertDescription>{form.refused}</AlertDescription>
        </Alert>
      )}
      <Button
        type="submit"
        size="sm"
        className="justify-self-start"
        disabled={disabled || form.sending}
      >
        {form.sending ? sendingLabel : label}
      </Button>
    </div>
  );
}

type RetrySpecDraft = Record<keyof ApplyRetrySpec, string>;

const SPEC_FIELDS: readonly {
  name: keyof ApplyRetrySpec;
  label: Message;
  seconds: boolean;
}[] = [
  { name: "maxRetries", label: "Max retries", seconds: false },
  { name: "minBackoff", label: "Min backoff (s)", seconds: true },
  { name: "executionTimeout", label: "Execution timeout (s)", seconds: true },
];

export interface RetrySpecFormProps {
  spec: ApplyRetrySpec;
  apply(spec: ApplyRetrySpec): Promise<void>;
  /** After the service took it: the detail reads the record again. */
  onApplied(): void;
}

/**
 * The execution's retry limit, backoff and timeout, as a form beside the
 * values it changes. Keyed by the values it started from, so a record read
 * again after a change starts a clean form.
 */
export function RetrySpecForm({ spec, apply, onApplied }: RetrySpecFormProps) {
  const { locale, t } = useI18n();
  const [draft, setDraft] = useState<RetrySpecDraft>({
    maxRetries: String(spec.maxRetries),
    minBackoff: String(spec.minBackoff),
    executionTimeout: String(spec.executionTimeout),
  });
  const form = useCommandForm(() => {
    toast.success(t("Retry specification updated"));
    onApplied();
  });
  const valid = Object.values(draft).every(
    (value) =>
      value.trim() !== "" &&
      Number.isSafeInteger(Number(value)) &&
      Number(value) >= 0 &&
      Number(value) <= INT32_MAX,
  );
  const dirty = SPEC_FIELDS.some(
    ({ name }) => Number(draft[name]) !== spec[name],
  );
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!valid || !dirty) return;
    const next: ApplyRetrySpec = {
      maxRetries: Number(draft.maxRetries),
      minBackoff: Number(draft.minBackoff),
      executionTimeout: Number(draft.executionTimeout),
    };
    form.send(() => apply(next));
  };
  return (
    <form
      className="grid gap-3"
      aria-label={t("Apply retry specification")}
      onSubmit={submit}
    >
      <div className="grid gap-3 sm:grid-cols-3">
        {SPEC_FIELDS.map(({ name, label, seconds }) => (
          <Field
            key={name}
            label={t(label)}
            hint={
              seconds
                ? draft[name].trim() === ""
                  ? t("Enter a duration")
                  : formatSeconds(Number(draft[name]), locale)
                : undefined
            }
          >
            {(id, hintId) => (
              <Input
                id={id}
                name={name}
                type="number"
                inputMode="numeric"
                min={0}
                max={INT32_MAX}
                step={1}
                required
                aria-describedby={hintId}
                value={draft[name]}
                onChange={(event) =>
                  setDraft((current) => ({
                    ...current,
                    [name]: event.target.value,
                  }))
                }
              />
            )}
          </Field>
        ))}
      </div>
      <Submit
        form={form}
        disabled={!valid || !dirty}
        label={t("Apply retry spec")}
        sendingLabel={t("Applying…")}
      />
    </form>
  );
}

export interface FunctionFormProps {
  target: ChangeFunction;
  change(target: ChangeFunction): Promise<void>;
  onChanged(): void;
}

const NAME_FIELDS: readonly {
  name: "contextName" | "processorName" | "name";
  label: Message;
}[] = [
  { name: "contextName", label: "Context name" },
  { name: "processorName", label: "Processor name" },
  { name: "name", label: "Function name" },
];

/**
 * The function the next retry runs — for a handler renamed or moved since
 * it failed. Keyed like `RetrySpecForm`.
 */
export function FunctionForm({ target, change, onChanged }: FunctionFormProps) {
  const { t } = useI18n();
  const [draft, setDraft] = useState<ChangeFunction>(target);
  const form = useCommandForm(() => {
    toast.success(t("Function updated"));
    onChanged();
  });
  const next: ChangeFunction = {
    contextName: draft.contextName.trim(),
    processorName: draft.processorName.trim(),
    name: draft.name.trim(),
    functionKind: draft.functionKind,
  };
  const valid = NAME_FIELDS.every(({ name }) => next[name] !== "");
  const dirty =
    NAME_FIELDS.some(({ name }) => next[name] !== target[name]) ||
    next.functionKind !== target.functionKind;
  const kindLabel = useId();
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!valid || !dirty) return;
    form.send(() => change(next));
  };
  return (
    <form
      className="grid gap-3"
      aria-label={t("Change function")}
      onSubmit={submit}
    >
      <div className="grid gap-3 sm:grid-cols-3">
        {NAME_FIELDS.map(({ name, label }) => (
          <Field key={name} label={t(label)}>
            {(id) => (
              <Input
                id={id}
                name={name}
                required
                value={draft[name]}
                onChange={(event) =>
                  setDraft((current) => ({
                    ...current,
                    [name]: event.target.value,
                  }))
                }
              />
            )}
          </Field>
        ))}
      </div>
      <div className="grid gap-1.5">
        <span id={kindLabel} className="text-sm font-medium">
          {t("Function kind")}
        </span>
        <ToggleGroup
          aria-labelledby={kindLabel}
          variant="outline"
          size="sm"
          spacing={0}
          value={[draft.functionKind]}
          onValueChange={(value: unknown[]) => {
            const [kind] = value as FunctionKind[];
            // One is always chosen: pressing the chosen one again keeps it.
            if (kind)
              setDraft((current) => ({ ...current, functionKind: kind }));
          }}
        >
          {KINDS.map((kind) => (
            <ToggleGroupItem key={kind} value={kind}>
              {kind}
            </ToggleGroupItem>
          ))}
        </ToggleGroup>
      </div>
      <Submit
        form={form}
        disabled={!valid || !dirty}
        label={t("Save function")}
        sendingLabel={t("Saving…")}
      />
    </form>
  );
}
