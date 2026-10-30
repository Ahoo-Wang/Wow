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
import type { RecordDetailSectionContext } from "@ahoo-wang/wow-view-engine/react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import type { DatasetArtifact, DatasetState } from "@/generated/index.ts";
import { type Locale, useI18n } from "@/i18n.tsx";
import { isEditor } from "@/services/caller.ts";
import { DATASET_WORDS } from "@/views/datasets.ts";
import { useCaller, useServices, useSettings } from "@/app/services.tsx";
import {
  CONTROL,
  DefinitionProblem,
  Facts,
  Lines,
  SaveBar,
  Section,
  useSaving,
  useYesNo,
  type Saving,
} from "./parts.tsx";
import {
  datasetOf,
  definitionText,
  parseDefinition,
  versionsInOrder,
} from "./datasetRecord.ts";

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
        {"error" in parsed ? <DefinitionProblem error={parsed.error} /> : null}
        <SaveBar saving={saving} disabled={"error" in parsed} />
      </Section>
    </form>
  );
}

/**
 * The way back to `version`, asked first: queries read it again, its view
 * created again if it is gone. What came of it is said above the versions
 * (`RolledBack`), which outlives the list reordered by it.
 */
function RollBack({
  version,
  busy,
  onRollBack,
}: {
  version: number;
  busy: boolean;
  onRollBack: (version: number) => void;
}) {
  const { t } = useI18n();
  const [asking, setAsking] = useState(false);
  if (!asking)
    return (
      <Button
        type="button"
        size="sm"
        variant="outline"
        className="self-start"
        disabled={busy}
        onClick={() => setAsking(true)}
      >
        {t("rollBackTo", { version })}
      </Button>
    );
  return (
    <form
      className="flex flex-col gap-2"
      aria-label={t("rollBackTo", { version })}
      onSubmit={(event) => {
        event.preventDefault();
        setAsking(false);
        onRollBack(version);
      }}
    >
      <p className="text-sm">{t("rollBackAsk", { version })}</p>
      <div className="flex flex-wrap items-center gap-2">
        <Button type="submit" size="sm" disabled={busy}>
          {t("rollBack")}
        </Button>
        <Button
          type="button"
          size="sm"
          variant="ghost"
          onClick={() => setAsking(false)}
        >
          {t("cancel")}
        </Button>
      </div>
    </form>
  );
}

/** What came of the last rollback: under way, refused, or the version now served. */
function RolledBack({ saving, version }: { saving: Saving; version: number }) {
  const { t } = useI18n();
  if (saving.error)
    return (
      <p role="alert" className="text-sm text-destructive">
        {saving.error}
      </p>
    );
  return (
    <p role="status" className="text-sm text-muted-foreground">
      {saving.busy ? t("sending") : t("rolledBack", { version })}
    </p>
  );
}

/** A published version: its view, what it reads, its fields and its DDL. */
function Version({
  artifact,
  serving,
  rollBack,
}: {
  artifact: DatasetArtifact;
  serving: boolean;
  /** The way back to this version, where the dataset can be switched to it. */
  rollBack?: ReactNode;
}) {
  const { t } = useI18n();
  return (
    <div className="flex flex-col gap-2 rounded-lg border p-2 text-sm">
      <details open={serving}>
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
      {/* Outside the details: offered without opening an older version. */}
      {rollBack}
    </div>
  );
}

/**
 * A dataset read the data owner's way: what it is and whether it is served,
 * why it needs revision or an approver, its definition revised in place, and
 * the versions published, the one serving first, each other one offering
 * the way back to it.
 */
export function DatasetReading({
  row,
  complete,
  refresh,
}: RecordDetailSectionContext) {
  const { t, locale } = useI18n();
  const yesNo = useYesNo();
  const caller = useCaller();
  const settings = useSettings();
  const state = datasetOf(row.data);
  const id = String(row.key);
  const versions = versionsInOrder(state);
  const { commands } = useServices();
  const rolling = useSaving(refresh);
  // The rollback last asked for, of this dataset.
  const [rolled, setRolled] = useState<{ id: string; version: number } | null>(
    null,
  );
  const rollBack = (version: number) => {
    setRolled({ id, version });
    rolling.save(() => commands.rollback(id, version));
  };
  // Where the catalog's roles decide, only an editor rolls back; a dataset
  // never published or retired has none to go back to here.
  const rollsBack =
    (settings?.rolesDecide !== true || isEditor(caller)) &&
    (state.status === "PUBLISHED" || state.status === "NEEDS_REVISION");
  return (
    <div className="flex flex-col gap-5">
      <Facts
        rows={[
          [t("name"), `${state.context}.${state.name}`],
          [t("title"), state.title],
          [
            t("status"),
            <Badge variant="secondary">
              {statusWord(state.status, locale)}
            </Badge>,
          ],
          [
            t("serving"),
            state.current > 0 ? t("version", { version: state.current }) : null,
          ],
          // Only a dataset that needs revision may have stopped serving.
          ...(state.status === "NEEDS_REVISION"
            ? [
                [t("stillServing"), yesNo(state.stillServing)] as [
                  string,
                  ReactNode,
                ],
              ]
            : []),
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
          {rolled?.id === id ? (
            <RolledBack saving={rolling} version={rolled.version} />
          ) : null}
          {versions.map(({ artifact }) => {
            const serving = artifact.version === state.current;
            return (
              <Version
                key={artifact.version}
                artifact={artifact}
                serving={serving}
                rollBack={
                  rollsBack && !serving ? (
                    <RollBack
                      key={`${id}-${artifact.version}-${state.current}`}
                      version={artifact.version}
                      busy={rolling.busy}
                      onRollBack={rollBack}
                    />
                  ) : undefined
                }
              />
            );
          })}
        </Section>
      ) : null}
    </div>
  );
}

/** The status, in the words its list view shows it; a status it does not know, as it came. */
const STATUS_WORDS: Readonly<
  Record<string, keyof (typeof DATASET_WORDS)["en"]>
> = {
  DRAFT: "statusDraft",
  PUBLISHED: "statusPublished",
  NEEDS_REVISION: "statusNeedsRevision",
  RETIRED: "statusRetired",
};

function statusWord(status: string, locale: Locale): string {
  const word = STATUS_WORDS[status];
  return word === undefined ? status : DATASET_WORDS[locale][word];
}
