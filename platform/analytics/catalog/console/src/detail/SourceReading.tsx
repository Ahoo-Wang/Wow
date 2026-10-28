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
import type { FieldSensitivity, SourceState } from "@/generated/index.ts";
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
import {
  changedSensitivity,
  parseReferences,
  referencesText,
  sensitivityRows,
  sourceOf,
  type SensitivityRow,
} from "./sourceRecord.ts";

/** An analyst's table's scope and references, as the analyst declares them. */
function ScopeForm({
  id,
  state,
  refresh,
}: {
  id: string;
  state: SourceState;
  refresh: () => void;
}) {
  const { t } = useI18n();
  const { commands } = useServices();
  const saving = useSaving(refresh);
  const [tenant, setTenant] = useState(state.tenantColumn ?? "");
  const [owner, setOwner] = useState(state.ownerColumn ?? "");
  const [space, setSpace] = useState(state.spaceColumn ?? "");
  const [platform, setPlatform] = useState(state.platform === true);
  const [references, setReferences] = useState(
    referencesText(state.references ?? []),
  );
  const parsed = parseReferences(references);
  const column = (value: string) => (value.trim() === "" ? null : value.trim());

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!("references" in parsed)) return;
    saving.save(() =>
      commands.reviseSource(id, {
        tenantColumn: platform ? null : column(tenant),
        ownerColumn: platform ? null : column(owner),
        spaceColumn: platform ? null : column(space),
        platform,
        references: parsed.references,
      }),
    );
  };

  return (
    <form
      onSubmit={submit}
      aria-label={t("scope")}
      className="flex flex-col gap-3"
    >
      <Section title={t("scope")} hint={t("scopeHint")}>
        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={platform}
            onChange={(event) => setPlatform(event.target.checked)}
          />
          {t("platform")}
        </label>
        <div className="grid grid-cols-[minmax(8rem,auto)_1fr] items-center gap-2 text-sm">
          {(
            [
              ["tenantColumn", tenant, setTenant],
              ["ownerColumn", owner, setOwner],
              ["spaceColumn", space, setSpace],
            ] as const
          ).map(([key, value, set]) => (
            <label key={key} className="contents">
              <span className="text-muted-foreground">{t(key)}</span>
              <Input
                value={platform ? "" : value}
                disabled={platform}
                list="source-columns"
                onChange={(event) => set(event.target.value)}
              />
            </label>
          ))}
        </div>
        <datalist id="source-columns">
          {(state.columns ?? []).map((item) => (
            <option key={item.name} value={item.name} />
          ))}
        </datalist>
      </Section>
      <Section title={t("references")} hint={t("referencesHint")}>
        <textarea
          aria-label={t("references")}
          className={`${CONTROL} h-20 py-1 font-mono`}
          value={references}
          onChange={(event) => setReferences(event.target.value)}
        />
        {"line" in parsed ? (
          <p role="alert" className="text-sm text-destructive">
            {t("referencesInvalid", { line: parsed.line })}
          </p>
        ) : null}
      </Section>
      <SaveBar saving={saving} disabled={"line" in parsed} />
    </form>
  );
}

const LEVELS = ["", "display", "confidential"] as const;

/** Each value's protection, as an analyst reviews it. */
function SensitivityForm({
  id,
  rows,
  refresh,
}: {
  id: string;
  rows: SensitivityRow[];
  refresh: () => void;
}) {
  const { t } = useI18n();
  const { commands } = useServices();
  const saving = useSaving(refresh);
  const [edited, setEdited] = useState<Record<string, FieldSensitivity>>({});
  const changed = changedSensitivity(rows, edited);
  const edit = (row: SensitivityRow, change: Partial<FieldSensitivity>) =>
    setEdited((current) => ({
      ...current,
      [row.key]: { ...(current[row.key] ?? row.field), ...change },
    }));
  const count = (value: string) => Math.max(0, Number(value) || 0);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (changed.length === 0) return;
    // Once saved, the record read again holds what was edited.
    saving.save(() =>
      commands.reviewSensitivity(id, changed).then(() => setEdited({})),
    );
  };

  return (
    <form onSubmit={submit} aria-label={t("sensitivity")}>
      <Section title={t("sensitivity")} hint={t("sensitivityHint")}>
        <table className="w-full text-sm">
          <thead className="text-left text-muted-foreground">
            <tr>
              <th className="font-normal">{t("column")}</th>
              <th className="font-normal">{t("level")}</th>
              <th className="font-normal">{t("keepPrefix")}</th>
              <th className="font-normal">{t("keepSuffix")}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => {
              const field = edited[row.key] ?? row.field;
              return (
                <tr key={row.key}>
                  <td className="py-0.5 pr-2 font-mono">
                    {row.key}
                    {row.type ? (
                      <span className="ml-2 text-xs text-muted-foreground">
                        {row.type}
                      </span>
                    ) : null}
                    {row.reviewed ? null : (
                      <Badge variant="outline" className="ml-2">
                        {t("notReviewed")}
                      </Badge>
                    )}
                  </td>
                  <td className="pr-2">
                    <select
                      aria-label={`${t("level")} ${row.key}`}
                      className={CONTROL}
                      value={field.level ?? ""}
                      onChange={(event) =>
                        edit(row, {
                          level:
                            event.target.value === ""
                              ? null
                              : (event.target.value as
                                  "display" | "confidential"),
                        })
                      }
                    >
                      {LEVELS.map((level) => (
                        <option key={level} value={level}>
                          {level === "" ? t("levelNone") : level}
                        </option>
                      ))}
                    </select>
                  </td>
                  {(["keepPrefix", "keepSuffix"] as const).map((key) => (
                    <td key={key} className="pr-2">
                      <input
                        type="number"
                        min={0}
                        aria-label={`${t(key)} ${row.key}`}
                        className={`${CONTROL} w-16`}
                        disabled={field.level !== "display"}
                        value={field[key] ?? 0}
                        onChange={(event) =>
                          edit(row, { [key]: count(event.target.value) })
                        }
                      />
                    </td>
                  ))}
                </tr>
              );
            })}
          </tbody>
        </table>
        <SaveBar saving={saving} disabled={changed.length === 0} />
      </Section>
    </form>
  );
}

/**
 * A source read the data owner's way: what it is and how it is scoped, its
 * columns' protection, and what awaits an approver; an analyst's table's
 * scope, references and protection edited in place.
 */
export function SourceReading({
  row,
  complete,
  refresh,
}: RecordDetailSectionContext) {
  const { t } = useI18n();
  const yesNo = useYesNo();
  const state = sourceOf(row.data);
  const id = String(row.key);
  const analyst = state.kind !== "WOW";
  const enrichment = state.enrichment;
  return (
    <div className="flex flex-col gap-5">
      <Facts
        rows={[
          [t("connection"), state.connection],
          [t("table"), `${state.database}.${state.table}`],
          [t("tableEngine"), state.tableEngine],
          [t("status"), <Badge variant="secondary">{state.status}</Badge>],
          [t("approved"), yesNo(state.approved)],
          [t("missing"), yesNo(state.missing)],
          [t("platform"), yesNo(state.platform)],
          [t("tenantColumn"), state.tenantColumn],
          [t("ownerColumn"), state.ownerColumn],
          [t("spaceColumn"), state.spaceColumn],
        ]}
      />
      {enrichment ? (
        <Section title={t("enrichment")}>
          <Facts
            rows={[
              [t("aggregate"), `${enrichment.context}.${enrichment.aggregate}`],
              [t("writer"), enrichment.writer],
              [t("time"), enrichment.writtenAt],
            ]}
          />
        </Section>
      ) : null}
      <Lines title={t("pending")} lines={state.pending ?? []} />
      <Lines title={t("refusals")} lines={state.refusals ?? []} />
      {!complete ? null : analyst ? (
        <ScopeForm
          key={`scope-${id}`}
          id={id}
          state={state}
          refresh={refresh}
        />
      ) : (
        <p className="text-sm text-muted-foreground">{t("readOnlySource")}</p>
      )}
      {complete ? (
        <SensitivityForm
          key={`sensitivity-${id}`}
          id={id}
          rows={sensitivityRows(state)}
          refresh={refresh}
        />
      ) : null}
    </div>
  );
}
