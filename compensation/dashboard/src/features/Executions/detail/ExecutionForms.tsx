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
import { PencilIcon } from "lucide-react";
import { useId, useState, type FormEvent, type ReactNode } from "react";
import type { ApplyRetrySpec, ChangeFunction } from "@/generated";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardAction,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import {
  Field,
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
  FieldLegend,
  FieldSet,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { useI18n, type Message } from "@/i18n.tsx";
import { formatSeconds } from "@/utils/durations.ts";
import { useCommandForm, type CommandForm } from "./useCommandForm.ts";

const INT32_MAX = 2_147_483_647;

/** The kinds a compensated function can be: it handles an event. */
const KINDS = [FunctionKind.EVENT, FunctionKind.STATE_EVENT] as const;

/** Each kind as the definition's column names it (「事件」「状态事件」). */
const KIND_LABELS = {
  [FunctionKind.EVENT]: "Event",
  [FunctionKind.STATE_EVENT]: "State event",
} as const;

/**
 * A part of the execution an operator can change, read as a card: what it
 * holds, and in its header one button that opens the form for it — and
 * closes it again once the service took the change, saying so under the
 * values, no toast (console-redesign.md Q4). The detail is read far more
 * often than it is edited, so no form is open until it is asked for
 * (2026-09-27 review).
 */
export function EditableCard({
  title,
  label,
  done,
  children,
  form,
}: {
  /** The card's heading: what it holds, 「处理函数」. */
  title: string;
  /** The button's words: what the form does, 「变更函数」. */
  label: string;
  /** Said under the values once the form was sent and taken. */
  done: string;
  /** The values, read. */
  children: ReactNode;
  form(close: () => void): ReactNode;
}) {
  const heading = useId();
  const [open, setOpen] = useState(false);
  const [said, setSaid] = useState<string | null>(null);
  return (
    <Collapsible
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (next) setSaid(null);
      }}
      render={<Card size="sm" role="region" aria-labelledby={heading} />}
    >
      <CardHeader>
        <CardTitle>
          <h3 id={heading}>{title}</h3>
        </CardTitle>
        <CardAction>
          <CollapsibleTrigger
            render={<Button type="button" variant="ghost" size="sm" />}
          >
            <PencilIcon data-icon="inline-start" />
            {label}
          </CollapsibleTrigger>
        </CardAction>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {children}
        <p role="status" className="text-sm text-muted-foreground empty:hidden">
          {said}
        </p>
        <CollapsibleContent>
          {form(() => {
            setOpen(false);
            setSaid(done);
          })}
        </CollapsibleContent>
      </CardContent>
    </Collapsible>
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
    <div className="flex flex-col items-start gap-2">
      {form.refused !== null && (
        <Alert variant="destructive">
          <AlertDescription>{form.refused}</AlertDescription>
        </Alert>
      )}
      <Button type="submit" size="sm" disabled={disabled || form.sending}>
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

/** A whole number the service takes: 0 up to a 32-bit integer. */
function admitted(value: string): boolean {
  const number = Number(value);
  return (
    value.trim() !== "" &&
    Number.isSafeInteger(number) &&
    number >= 0 &&
    number <= INT32_MAX
  );
}

export interface RetrySpecFormProps {
  spec: ApplyRetrySpec;
  apply(spec: ApplyRetrySpec): Promise<void>;
  /** After the service took it: the detail reads the record again. */
  onApplied(): void;
}

/**
 * The execution's retry limit, backoff and timeout. Keyed by the values it
 * started from, so a record read again after a change starts a clean form.
 */
export function RetrySpecForm({ spec, apply, onApplied }: RetrySpecFormProps) {
  const { locale, t } = useI18n();
  const id = useId();
  const [draft, setDraft] = useState<RetrySpecDraft>({
    maxRetries: String(spec.maxRetries),
    minBackoff: String(spec.minBackoff),
    executionTimeout: String(spec.executionTimeout),
  });
  const form = useCommandForm(onApplied);
  const valid = Object.values(draft).every(admitted);
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
    <form aria-label={t("Apply retry specification")} onSubmit={submit}>
      <FieldGroup>
        <div className="grid gap-4 sm:grid-cols-3">
          {SPEC_FIELDS.map(({ name, label, seconds }) => {
            const invalid = !admitted(draft[name]);
            return (
              <Field key={name} data-invalid={invalid || undefined}>
                <FieldLabel htmlFor={`${id}-${name}`}>{t(label)}</FieldLabel>
                <Input
                  id={`${id}-${name}`}
                  name={name}
                  type="number"
                  inputMode="numeric"
                  min={0}
                  max={INT32_MAX}
                  step={1}
                  required
                  aria-invalid={invalid || undefined}
                  value={draft[name]}
                  onChange={(event) =>
                    setDraft((current) => ({
                      ...current,
                      [name]: event.target.value,
                    }))
                  }
                />
                {seconds && !invalid && (
                  <FieldDescription>
                    {formatSeconds(Number(draft[name]), locale)}
                  </FieldDescription>
                )}
                {invalid && (
                  <FieldError>
                    {seconds
                      ? t("Enter a duration")
                      : t("Enter a whole number")}
                  </FieldError>
                )}
              </Field>
            );
          })}
        </div>
        <Submit
          form={form}
          disabled={!valid || !dirty}
          label={t("Apply retry spec")}
          sendingLabel={t("Applying…")}
        />
      </FieldGroup>
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
  const id = useId();
  const [draft, setDraft] = useState<ChangeFunction>(target);
  const form = useCommandForm(onChanged);
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
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!valid || !dirty) return;
    form.send(() => change(next));
  };
  return (
    <form aria-label={t("Change function")} onSubmit={submit}>
      <FieldGroup>
        <div className="flex flex-col gap-4">
          {NAME_FIELDS.map(({ name, label }) => {
            const invalid = next[name] === "";
            return (
              <Field key={name} data-invalid={invalid || undefined}>
                <FieldLabel htmlFor={`${id}-${name}`}>{t(label)}</FieldLabel>
                <Input
                  id={`${id}-${name}`}
                  name={name}
                  required
                  aria-invalid={invalid || undefined}
                  value={draft[name]}
                  onChange={(event) =>
                    setDraft((current) => ({
                      ...current,
                      [name]: event.target.value,
                    }))
                  }
                />
                {invalid && <FieldError>{t("Required")}</FieldError>}
              </Field>
            );
          })}
        </div>
        <FieldSet>
          <FieldLegend variant="label">{t("Function kind")}</FieldLegend>
          <ToggleGroup
            aria-label={t("Function kind")}
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
                {t(KIND_LABELS[kind])}
              </ToggleGroupItem>
            ))}
          </ToggleGroup>
        </FieldSet>
        <Submit
          form={form}
          disabled={!valid || !dirty}
          label={t("Save function")}
          sendingLabel={t("Saving…")}
        />
      </FieldGroup>
    </form>
  );
}
