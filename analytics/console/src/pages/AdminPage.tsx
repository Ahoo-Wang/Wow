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

import { useCallback, useEffect, useState, type FormEvent } from 'react';
import {
  fieldsOf,
  RegistryClient,
  type FieldSensitivity,
  type RegistryDataset,
} from '../registry.ts';

const LEVELS = [
  { value: '', label: '不敏感' },
  { value: 'DISPLAY', label: '展示脱敏' },
  { value: 'CONFIDENTIAL', label: '机密' },
] as const;

/** A dataset's state in a word: published at a version, or a draft. */
function statusOf(dataset: RegistryDataset): string {
  return dataset.published
    ? `已发布 v${dataset.publishedVersion}`
    : dataset.publishedVersion > 0
      ? '已下线'
      : '草稿';
}

/**
 * The dataset registry for its admins (analytics design §5): draft a
 * dataset from a ClickHouse table, revise its declaration (its scope —
 * tenant and tag columns — and its fields), review every field's
 * sensitivity, publish and retire it. The server refuses each of them to
 * anyone without the admin role.
 */
export default function AdminPage({
  client = new RegistryClient(),
}: {
  client?: RegistryClient;
}) {
  const [datasets, setDatasets] = useState<RegistryDataset[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);

  const reload = useCallback(
    () =>
      client
        .list()
        .then(list => {
          setDatasets(list);
          setError(null);
        })
        .catch((reason: unknown) => setError(String(reason))),
    [client],
  );
  useEffect(() => {
    void reload();
  }, [reload]);

  /** Runs one command, then reloads; its refusal is said, not thrown. */
  const run = (command: () => Promise<unknown>) =>
    command()
      .then(reload)
      .catch((reason: unknown) =>
        setError(reason instanceof Error ? reason.message : String(reason)),
      );

  const current = datasets.find(dataset => dataset.id === selected) ?? null;
  return (
    <div className="admin">
      <h2>数据集管理</h2>
      {error ? (
        <p role="alert" className="error">
          {error}
        </p>
      ) : null}
      <DraftForm onDraft={(id, table) => run(() => client.draft(id, table))} />
      <table>
        <thead>
          <tr>
            <th>数据集</th>
            <th>表</th>
            <th>状态</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {datasets.map(dataset => (
            <tr key={dataset.id} aria-selected={dataset.id === selected}>
              <td>
                <button
                  type="button"
                  className="link"
                  onClick={() => setSelected(dataset.id)}
                >
                  {dataset.id}
                </button>
              </td>
              <td>{dataset.table}</td>
              <td>{statusOf(dataset)}</td>
              <td className="actions">
                <button
                  type="button"
                  onClick={() => run(() => client.publish(dataset.id))}
                >
                  发布
                </button>
                <button
                  type="button"
                  disabled={!dataset.published}
                  onClick={() => run(() => client.retire(dataset.id))}
                >
                  下线
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {current ? (
        <DatasetEditor
          key={current.id}
          dataset={current}
          onRevise={document => run(() => client.revise(current.id, document))}
          onReview={fields => run(() => client.review(current.id, fields))}
        />
      ) : null}
    </div>
  );
}

function DraftForm({ onDraft }: { onDraft(id: string, table: string): void }) {
  const [id, setId] = useState('');
  const [table, setTable] = useState('');
  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (id.trim() && table.trim()) onDraft(id.trim(), table.trim());
  };
  return (
    <form className="draft" onSubmit={submit} aria-label="从表起草">
      <input
        aria-label="数据集名"
        placeholder="上下文.数据集，如 analytics.orders"
        value={id}
        onChange={event => setId(event.target.value)}
      />
      <input
        aria-label="表"
        placeholder="库.表，或默认库里的表"
        value={table}
        onChange={event => setTable(event.target.value)}
      />
      <button type="submit">从表起草</button>
    </form>
  );
}

function DatasetEditor({
  dataset,
  onRevise,
  onReview,
}: {
  dataset: RegistryDataset;
  onRevise(document: Record<string, unknown>): void;
  onReview(fields: Record<string, FieldSensitivity>): void;
}) {
  const [text, setText] = useState(() =>
    JSON.stringify(dataset.document, null, 2),
  );
  const [invalid, setInvalid] = useState<string | null>(null);
  const [levels, setLevels] = useState<Record<string, string>>(() =>
    Object.fromEntries(
      fieldsOf(dataset.document).map(field => [
        field,
        dataset.reviews[field]?.level ?? '',
      ]),
    ),
  );
  const revise = () => {
    try {
      onRevise(JSON.parse(text) as Record<string, unknown>);
      setInvalid(null);
    } catch (reason) {
      setInvalid(`声明不是合法的 JSON：${String(reason)}`);
    }
  };
  const review = () =>
    onReview(
      Object.fromEntries(
        Object.entries(levels).map(([field, level]) => [
          field,
          level ? { level: level as FieldSensitivity['level'] } : {},
        ]),
      ),
    );
  return (
    <section className="editor" aria-label={`编辑 ${dataset.id}`}>
      <h3>{dataset.id}</h3>
      <label htmlFor="document">
        声明（范围：租户列 scope.tenant，标签列 scope.tags）
      </label>
      <textarea
        id="document"
        rows={16}
        value={text}
        onChange={event => setText(event.target.value)}
      />
      {invalid ? (
        <p role="alert" className="error">
          {invalid}
        </p>
      ) : null}
      <button type="button" onClick={revise}>
        保存修订
      </button>
      <h4>字段敏感性审核</h4>
      <table>
        <tbody>
          {Object.keys(levels).map(field => (
            <tr key={field}>
              <td>{field}</td>
              <td>
                <select
                  aria-label={`${field} 的敏感等级`}
                  value={levels[field]}
                  onChange={event =>
                    setLevels(current => ({
                      ...current,
                      [field]: event.target.value,
                    }))
                  }
                >
                  {LEVELS.map(level => (
                    <option key={level.value} value={level.value}>
                      {level.label}
                    </option>
                  ))}
                </select>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <button type="button" onClick={review}>
        提交审核
      </button>
    </section>
  );
}
