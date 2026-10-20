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

import { useState, type FormEvent, type ReactNode } from "react";
import { Link } from "react-router";
import { CallerGate } from "@/app/CallerGate.tsx";
import { useServices } from "@/app/services.tsx";
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
import { CONTROL, SaveBar, useSaving } from "@/detail/parts.tsx";
import { useI18n, type Message } from "@/i18n.tsx";
import { DATASETS_PATH, SOURCES_PATH } from "@/views/bindings.ts";

/** The catalog's default connection, as `application.yaml` names it. */
const DEFAULT_CONNECTION = "default";

function Text({
  label,
  value,
  onChange,
  hint,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  hint?: string;
}) {
  return (
    <label className="flex flex-col gap-1 text-sm">
      <span>{label}</span>
      <Input
        value={value}
        required
        onChange={(event) => onChange(event.target.value)}
      />
      {hint ? (
        <span className="text-xs text-muted-foreground">{hint}</span>
      ) : null}
    </label>
  );
}

function Drafted({
  id,
  to,
  label,
}: {
  id: string | null;
  to: string;
  label: Message;
}) {
  const { t } = useI18n();
  if (id === null) return null;
  return (
    <p role="status" className="text-sm">
      {t("drafted", { id })}{" "}
      <Link to={to} className="underline">
        {t(label)}
      </Link>
    </p>
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
  const [connection, setConnection] = useState(DEFAULT_CONNECTION);
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
          <SaveBar saving={saving} label={t("draft")} />
          <Drafted id={drafted} to={SOURCES_PATH} label="openSources" />
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
            <p role="alert" className="text-sm text-destructive">
              {t("definitionInvalid", { message: parsed.error })}
            </p>
          ) : null}
          <SaveBar
            saving={saving}
            label={t("draft")}
            disabled={"error" in parsed}
          />
          <Drafted id={drafted} to={DATASETS_PATH} label="openDatasets" />
        </>
      }
    >
      <Text label={t("context")} value={context} onChange={setContext} />
      <Text label={t("name")} value={name} onChange={setName} />
      <label className="flex flex-col gap-1 text-sm">
        <span>{t("title")}</span>
        <Input
          value={title}
          onChange={(event) => setTitle(event.target.value)}
        />
      </label>
      <label className="flex flex-col gap-1 text-sm">
        <span>{t("definition")}</span>
        <textarea
          spellCheck={false}
          className={`${CONTROL} h-64 py-1 font-mono text-xs`}
          value={text}
          onChange={(event) => setText(event.target.value)}
        />
        <span className="text-xs text-muted-foreground">
          {t("definitionHint")}
        </span>
      </label>
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
