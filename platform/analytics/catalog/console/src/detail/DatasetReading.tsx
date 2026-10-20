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

import { useState, type FormEvent } from "react";
import type { RecordDetailSectionContext } from "@ahoo-wang/wow-view-engine/react";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import type { DatasetArtifact, DatasetState } from "@/generated/index.ts";
import { useI18n } from "@/i18n.tsx";
import { useServices } from "@/app/services.tsx";
import {
  CONTROL,
  Facts,
  Lines,
  SaveBar,
  Section,
  useSaving,
  useYesNo,
} from "./parts.tsx";
import { datasetOf, definitionText, parseDefinition } from "./datasetRecord.ts";

/** A dataset's definition and title, as an analyst revises them. */
function DefinitionForm({
  id,
  state,
  refresh,
}: {
  id: string;
  state: DatasetState;
  refresh: () => void;
}) {
  const { t } = useI18n();
  const { commands } = useServices();
  const saving = useSaving(refresh);
  const [title, setTitle] = useState(state.title ?? "");
  const [text, setText] = useState(
    definitionText(state.requested ?? state.definition),
  );
  const parsed = parseDefinition(text);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!("definition" in parsed)) return;
    saving.save(() =>
      commands.reviseDataset(
        id,
        parsed.definition,
        title.trim() === "" ? undefined : title.trim(),
      ),
    );
  };

  return (
    <form onSubmit={submit} aria-label={t("definition")}>
      <Section title={t("definition")} hint={t("definitionHint")}>
        <label className="grid grid-cols-[minmax(8rem,auto)_1fr] items-center gap-2 text-sm">
          <span className="text-muted-foreground">{t("title")}</span>
          <Input
            value={title}
            onChange={(event) => setTitle(event.target.value)}
          />
        </label>
        <textarea
          aria-label={t("definition")}
          spellCheck={false}
          className={`${CONTROL} h-72 py-1 font-mono text-xs`}
          value={text}
          onChange={(event) => setText(event.target.value)}
        />
        {"error" in parsed ? (
          <p role="alert" className="text-sm text-destructive">
            {t("definitionInvalid", { message: parsed.error })}
          </p>
        ) : null}
        <SaveBar saving={saving} disabled={"error" in parsed} />
      </Section>
    </form>
  );
}

/** A published version: its view, what it reads, its fields and its DDL. */
function Version({
  artifact,
  serving,
}: {
  artifact: DatasetArtifact;
  serving: boolean;
}) {
  const { t } = useI18n();
  return (
    <details className="rounded-lg border p-2 text-sm" open={serving}>
      <summary className="cursor-pointer">
        {t("version", { version: artifact.version })}{" "}
        <span className="font-mono text-xs text-muted-foreground">
          {artifact.database}.{artifact.view}
        </span>
        {serving ? (
          <Badge variant="secondary" className="ml-2">
            {t("serving")}
          </Badge>
        ) : null}
      </summary>
      <div className="mt-2 flex flex-col gap-2">
        <Facts
          rows={[
            [t("sources"), artifact.sources.join(", ")],
            [t("eventTime"), artifact.eventTime],
          ]}
        />
        <table className="w-full text-xs" aria-label={t("fields")}>
          <tbody>
            {artifact.fields.map((field) => (
              <tr key={field.name}>
                <td className="pr-2 font-mono">{field.name}</td>
                <td className="pr-2 text-muted-foreground">
                  {field.reference
                    ? `→ ${field.reference.context}.${field.reference.aggregate}`
                    : null}
                </td>
                <td>{field.sensitivity?.level ?? null}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <pre
          className="overflow-auto rounded bg-muted p-2 text-xs"
          aria-label={t("ddl")}
        >
          {artifact.ddl}
        </pre>
      </div>
    </details>
  );
}

/**
 * A dataset read the data owner's way: what it is and whether it is served,
 * why it needs revision or an approver, its definition revised in place, and
 * the versions published, the one serving first.
 */
export function DatasetReading({
  row,
  complete,
  refresh,
}: RecordDetailSectionContext) {
  const { t } = useI18n();
  const yesNo = useYesNo();
  const state = datasetOf(row.data);
  const id = String(row.key);
  const versions = [...(state.versions ?? [])].reverse();
  return (
    <div className="flex flex-col gap-5">
      <Facts
        rows={[
          [t("name"), `${state.context}.${state.name}`],
          [t("title"), state.title],
          [t("status"), <Badge variant="secondary">{state.status}</Badge>],
          [
            t("serving"),
            state.current > 0 ? t("version", { version: state.current }) : null,
          ],
          [t("stillServing"), yesNo(state.stillServing)],
          [t("sources"), (state.sources ?? []).join(", ")],
        ]}
      />
      <Lines title={t("problems")} lines={state.problems ?? []} />
      {state.requested ? (
        <Lines
          title={t("requestReasons")}
          lines={
            state.requestReasons?.length
              ? state.requestReasons
              : [t("requested")]
          }
        />
      ) : null}
      {complete ? (
        <DefinitionForm
          key={`definition-${id}`}
          id={id}
          state={state}
          refresh={refresh}
        />
      ) : null}
      {versions.length ? (
        <Section title={t("versions")}>
          {versions.map(({ artifact }) => (
            <Version
              key={artifact.version}
              artifact={artifact}
              serving={artifact.version === state.current}
            />
          ))}
        </Section>
      ) : null}
    </div>
  );
}
