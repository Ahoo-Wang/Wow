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

import { useId, useState, type FormEvent, type ReactNode } from "react";
import { Link } from "react-router";
import { CallerGate } from "@/app/CallerGate.tsx";
import { useServices, useSettings } from "@/app/services.tsx";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import {
  DEFINITION_TEMPLATE,
  definitionText,
  parseDefinition,
} from "@/detail/datasetRecord.ts";
import {
  CONTROL,
  DefinitionProblem,
  SaveBar,
  useSaving,
} from "@/detail/parts.tsx";
import { useI18n } from "@/i18n.tsx";
import { datasetPath, sourcePath } from "@/views/bindings.ts";

/**
 * A field named by its label alone, its hint said as its description: a
 * label wrapping the hint, or a text area's content, would be read as the
 * name.
 */
function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: (props: { id: string; "aria-describedby"?: string }) => ReactNode;
}) {
  const id = useId();
  const hintId = `${id}-hint`;
  return (
    <div className="flex flex-col gap-1 text-sm">
      <label htmlFor={id}>{label}</label>
      {children({ id, ...(hint ? { "aria-describedby": hintId } : {}) })}
      {hint ? (
        <span id={hintId} className="text-xs text-muted-foreground">
          {hint}
        </span>
      ) : null}
    </div>
  );
}

function Text({
  label,
  value,
  onChange,
  hint,
  required = true,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  hint?: string;
  required?: boolean;
}) {
  return (
    <Field label={label} hint={hint}>
      {(props) => (
        <Input
          {...props}
          value={value}
          required={required}
          onChange={(event) => onChange(event.target.value)}
        />
      )}
    </Field>
  );
}

/** What was drafted, and the way to it: its record, open on its page. */
function Drafted({ id, to }: { id: string; to: string }) {
  const { t } = useI18n();
  return (
    <>
      {t("drafted", { id })}{" "}
      <Link to={to} className="text-foreground underline">
        {t("openDrafted")}
      </Link>
    </>
  );
}

function DraftCard({
  title,
  hint,
  onSubmit,
  children,
  footer,
}: {
  title: string;
  hint: string;
  onSubmit: (event: FormEvent) => void;
  children: ReactNode;
  footer: ReactNode;
}) {
  return (
    <Card className="w-full max-w-2xl">
      <form onSubmit={onSubmit} aria-label={title}>
        <CardHeader>
          <CardTitle>{title}</CardTitle>
          <CardDescription>{hint}</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-3 py-4">
          {children}
        </CardContent>
        <CardFooter className="flex-col items-start gap-2">{footer}</CardFooter>
      </form>
    </Card>
  );
}

/** Drafts an analyst's table as a source. */
function SourceDraft() {
  const { t } = useI18n();
  const { commands } = useServices();
  const settings = useSettings();
  // The catalog's own connection until another is typed: a source's id
  // starts with it, and the catalog refuses one of another connection.
  const [typed, setConnection] = useState<string | null>(null);
  const connection = typed ?? settings?.connection ?? "";
  const [database, setDatabase] = useState("");
  const [table, setTable] = useState("");
  const [drafted, setDrafted] = useState<string | null>(null);
  const saving = useSaving(() => undefined);
  const submit = (event: FormEvent) => {
    event.preventDefault();
    const id = `${connection.trim()}:${database.trim()}:${table.trim()}`;
    setDrafted(null);
    saving.save(() =>
      commands
        .draftSource(database.trim(), table.trim(), id)
        .then(() => setDrafted(id)),
    );
  };
  return (
    <DraftCard
      title={t("newSource")}
      hint={t("newSourceHint")}
      onSubmit={submit}
      footer={
        <>
          <SaveBar
            saving={saving}
            label={t("draft")}
            done={
              drafted === null ? undefined : (
                <Drafted id={drafted} to={sourcePath(drafted)} />
              )
            }
          />
        </>
      }
    >
      <Text
        label={t("connection")}
        value={connection}
        onChange={setConnection}
        hint={t("connectionHint")}
      />
      <Text label={t("database")} value={database} onChange={setDatabase} />
      <Text label={t("table")} value={table} onChange={setTable} />
    </DraftCard>
  );
}

/** Drafts a wide table over the reviewed sources. */
function DatasetDraft() {
  const { t } = useI18n();
  const { commands } = useServices();
  const [context, setContext] = useState("");
  const [name, setName] = useState("");
  const [title, setTitle] = useState("");
  const [text, setText] = useState(definitionText(DEFINITION_TEMPLATE));
  const [drafted, setDrafted] = useState<string | null>(null);
  const saving = useSaving(() => undefined);
  const parsed = parseDefinition(text);
  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!("definition" in parsed)) return;
    const id = `${context.trim()}.${name.trim()}`;
    setDrafted(null);
    saving.save(() =>
      commands
        .draftDataset(
          context.trim(),
          name.trim(),
          parsed.definition,
          title.trim() === "" ? undefined : title.trim(),
        )
        .then(() => setDrafted(id)),
    );
  };
  return (
    <DraftCard
      title={t("newDataset")}
      hint={t("newDatasetHint")}
      onSubmit={submit}
      footer={
        <>
          {"error" in parsed ? (
            <DefinitionProblem error={parsed.error} />
          ) : null}
          <SaveBar
            saving={saving}
            label={t("draft")}
            disabled={"error" in parsed}
            done={
              drafted === null ? undefined : (
                <Drafted id={drafted} to={datasetPath(drafted)} />
              )
            }
          />
        </>
      }
    >
      <Text label={t("context")} value={context} onChange={setContext} />
      <Text label={t("name")} value={name} onChange={setName} />
      <Text
        label={t("title")}
        value={title}
        onChange={setTitle}
        required={false}
      />
      <Field label={t("definition")} hint={t("definitionHint")}>
        {(props) => (
          <textarea
            {...props}
            spellCheck={false}
            className={`${CONTROL} h-64 py-1 font-mono text-xs`}
            value={text}
            onChange={(event) => setText(event.target.value)}
          />
        )}
      </Field>
    </DraftCard>
  );
}

/** Drafts a source or a dataset; each is then reviewed and published on its page. */
export default function DraftPage() {
  const { t } = useI18n();
  return (
    <CallerGate>
      {() => (
        <div className="flex flex-col items-center gap-4 overflow-auto p-6">
          <p className="max-w-2xl text-sm text-muted-foreground">
            {t("draftsHint")}
          </p>
          <SourceDraft />
          <DatasetDraft />
        </div>
      )}
    </CallerGate>
  );
}
