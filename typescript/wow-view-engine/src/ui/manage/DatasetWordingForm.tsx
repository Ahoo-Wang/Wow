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

import { useId, useState, type ReactNode } from 'react';
import { ArrowDownIcon, ArrowUpIcon } from 'lucide-react';
import type { DatasetOverrides } from '../../runtime/datasetResources.js';
import type { FieldSpec } from '../../runtime/define/spec.js';
import type { ManagedWording } from '../../react/index.js';
import { Button } from '../components/button.js';
import { Checkbox } from '../components/checkbox.js';
import { Input } from '../components/input.js';
import { Label } from '../components/label.js';
import {
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from '../components/tabs.js';
import { IconButton } from '../kit/IconButton.js';
import { useViewMessages } from '../kit/MessagesProvider.js';

/** One field as the form holds it. */
interface FieldRow {
  name: string;
  /** The label the descriptor derives, shown where none is written. */
  derived: string;
  /** The label written; empty keeps the derived one. */
  label: string;
  shown: boolean;
  /** A narrowing written in code or stored (`FieldSpec`), kept as it is. */
  spec?: FieldSpec;
}

/**
 * The rows the form starts from: the fields the wording names first, in its
 * order, then the rest in the descriptor's — the order `defineDataset`
 * derives the definition in (D86).
 */
function rowsOf(
  fields: readonly { name: string; label: string }[],
  written: DatasetOverrides['fields'] = {},
): FieldRow[] {
  const derived = new Map(fields.map(field => [field.name, field.label]));
  const named = Object.keys(written).filter(name => derived.has(name));
  const rest = fields
    .map(field => field.name)
    .filter(name => !(name in written));
  return [...named, ...rest].map(name => {
    const value = written[name];
    const spec =
      typeof value === 'object' && value !== null ? value : undefined;
    return {
      name,
      derived: derived.get(name) ?? name,
      label: typeof value === 'string' ? value : (spec?.label ?? ''),
      shown: value !== false,
      ...(spec ? { spec } : {}),
    };
  });
}

/** The wording the form says: every field in its order, so the order is kept. */
function wordingOf(
  title: string,
  noun: string,
  rows: readonly FieldRow[],
): DatasetOverrides {
  const fields: Record<string, FieldSpec | string | false> = {};
  for (const row of rows) {
    const label = row.label.trim();
    fields[row.name] = !row.shown
      ? false
      : row.spec
        ? { ...row.spec, ...(label ? { label } : {}) }
        : label || {};
  }
  return {
    ...(title.trim() ? { title: title.trim() } : {}),
    ...(noun.trim() ? { recordNoun: noun.trim() } : {}),
    fields,
  };
}

/**
 * The wording tab of the view manager (D91): what a dataset is called, what
 * its records are called, and each field's name, place and visibility, for
 * every reader of the tenant. An empty box keeps the word the dataset's
 * descriptor gives, shown as its placeholder; a save stores the whole
 * wording and registers the dataset again in it, so views opened from then
 * on read the new words.
 */
export function DatasetWordingForm({ wording }: { wording: ManagedWording }) {
  const messages = useViewMessages();
  const { editor } = wording;
  const ids = useId();
  const [title, setTitle] = useState(editor.wording.title ?? '');
  const [noun, setNoun] = useState(editor.wording.recordNoun ?? '');
  const [rows, setRows] = useState(() =>
    rowsOf(editor.fields, editor.wording.fields),
  );
  const [saved, setSaved] = useState(false);

  const change = (index: number, next: Partial<FieldRow>) => {
    setSaved(false);
    setRows(current =>
      current.map((row, at) => (at === index ? { ...row, ...next } : row)),
    );
  };
  const move = (index: number, by: -1 | 1) => {
    setSaved(false);
    setRows(current => {
      const next = [...current];
      const [row] = next.splice(index, 1);
      next.splice(index + by, 0, row);
      return next;
    });
  };

  return (
    <form
      data-slot="dataset-wording"
      className="fve:flex fve:flex-col fve:gap-4"
      onSubmit={event => {
        event.preventDefault();
        void wording
          .save(wordingOf(title, noun, rows))
          .then(done => setSaved(done));
      }}
    >
      <p className="fve:text-muted-foreground fve:text-sm">
        {messages.label('label.wording.description')}
      </p>
      <div className="fve:grid fve:gap-3 fve:sm:grid-cols-2">
        <div className="fve:flex fve:flex-col fve:gap-1.5">
          <Label htmlFor={`${ids}-title`}>
            {messages.label('label.wording.title')}
          </Label>
          <Input
            id={`${ids}-title`}
            value={title}
            placeholder={editor.derivedTitle}
            onChange={event => {
              setSaved(false);
              setTitle(event.target.value);
            }}
          />
        </div>
        <div className="fve:flex fve:flex-col fve:gap-1.5">
          <Label htmlFor={`${ids}-noun`}>
            {messages.label('label.wording.noun')}
          </Label>
          <Input
            id={`${ids}-noun`}
            value={noun}
            onChange={event => {
              setSaved(false);
              setNoun(event.target.value);
            }}
          />
        </div>
      </div>
      <fieldset className="fve:flex fve:flex-col fve:gap-1">
        <legend className="fve:mb-1.5 fve:text-sm fve:font-medium">
          {messages.label('label.wording.fields')}
        </legend>
        <ul className="fve:flex fve:flex-col fve:gap-1">
          {rows.map((row, index) => (
            <li
              key={row.name}
              data-slot="dataset-wording-field"
              data-hidden={row.shown ? undefined : ''}
              className="fve:flex fve:items-center fve:gap-2"
            >
              <Checkbox
                checked={row.shown}
                aria-label={messages.label('label.wording.field.shown', {
                  field: row.derived,
                })}
                onCheckedChange={shown => change(index, { shown })}
              />
              <Input
                value={row.label}
                placeholder={row.derived}
                aria-label={messages.label('label.wording.field.label', {
                  field: row.derived,
                })}
                className="fve:min-w-0 fve:flex-1"
                onChange={event => change(index, { label: event.target.value })}
              />
              <code className="fve:text-muted-foreground fve:hidden fve:max-w-[10rem] fve:truncate fve:text-xs fve:sm:block">
                {row.name}
              </code>
              <IconButton
                type="button"
                variant="ghost"
                size="icon-sm"
                disabled={index === 0}
                label={messages.label('label.wording.field.up', {
                  field: row.derived,
                })}
                onClick={() => move(index, -1)}
              >
                <ArrowUpIcon />
              </IconButton>
              <IconButton
                type="button"
                variant="ghost"
                size="icon-sm"
                disabled={index === rows.length - 1}
                label={messages.label('label.wording.field.down', {
                  field: row.derived,
                })}
                onClick={() => move(index, 1)}
              >
                <ArrowDownIcon />
              </IconButton>
            </li>
          ))}
        </ul>
      </fieldset>
      <div className="fve:flex fve:items-center fve:gap-3">
        <Button type="submit" disabled={wording.saving}>
          {messages.label('label.wording.save')}
        </Button>
        <span role="status" className="fve:text-sm">
          {wording.error !== undefined
            ? messages.label('label.wording.failed')
            : saved
              ? messages.label('label.wording.saved')
              : null}
        </span>
      </div>
    </form>
  );
}

/**
 * The manager's body with the dataset's wording beside its views (D91): a
 * tab of its own, since none of it is about one view.
 */
export function WithWordingTab({
  wording,
  children,
}: {
  wording: ManagedWording;
  children: ReactNode;
}) {
  const messages = useViewMessages();
  return (
    <Tabs
      defaultValue="views"
      className="fve:flex fve:min-h-0 fve:flex-col fve:gap-3"
    >
      <TabsList>
        <TabsTrigger value="views">
          {messages.label('label.manage.tab.views')}
        </TabsTrigger>
        <TabsTrigger value="wording">
          {messages.label('label.manage.tab.wording')}
        </TabsTrigger>
      </TabsList>
      <TabsContent value="views" className="fve:flex fve:min-h-0 fve:flex-col">
        {children}
      </TabsContent>
      <TabsContent
        value="wording"
        className="fve:relative fve:min-h-0 fve:overflow-y-auto fve:px-1"
      >
        <DatasetWordingForm wording={wording} />
      </TabsContent>
    </Tabs>
  );
}
