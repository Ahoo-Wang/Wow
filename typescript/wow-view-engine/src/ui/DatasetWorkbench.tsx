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

import { cn } from 'cn';
import { useId, useState } from 'react';
import { DatabaseIcon, RefreshCwIcon } from 'lucide-react';
import type { DatasetsState } from '../react/useDatasets.js';
import type { DatasetResource } from '../runtime/datasetResources.js';
import { Alert, AlertDescription } from './components/alert.js';
import { Button } from './components/button.js';
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from './components/empty.js';
import {
  Select,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from './components/select.js';
import { Spinner } from './components/spinner.js';
import { DataWorkbench, type DataWorkbenchProps } from './DataWorkbench.js';
import { useViewMessages } from './kit/MessagesProvider.js';
import { SelectContent } from './kit/popups.js';
import { ViewSurface } from './kit/ViewSurface.js';

/**
 * A standalone dataset view's props: the datasets served, which one is
 * open, and what `DataWorkbench` takes for it.
 */
export interface DatasetWorkbenchProps extends Omit<
  DataWorkbenchProps,
  'definitionId'
> {
  /**
   * The datasets served, as `useDatasets` reads them: the picker lists
   * them, says while they are read and why the last read failed, and
   * offers `refresh`, so a dataset published since appears without a
   * reload.
   */
  datasets: DatasetsState;
  /**
   * Which dataset is open, by its definition id, as `value` is on an input:
   * left out, the workbench owns it from the first dataset served on; given
   * (`null` for the first), a host's route is in charge.
   */
  datasetId?: string | null;
  /** Told which dataset the reader picked, so a host can write it into its route. */
  onDatasetChange?(id: string): void;
  /** Classes of the root, for the host's layout. */
  className?: string;
}

/** A dataset's name in the picker: its title, else its definition id. */
function nameOf({ id, entry }: DatasetResource): string {
  return entry.title ? `${entry.title} (${id})` : id;
}

/**
 * The standalone dataset view: a picker of the datasets
 * served — from one catalog or several — over the workbench of the one
 * open. For an application whose only navigation is its datasets; a host
 * with a navigation of its own binds the datasets there instead
 * (`datasetBindings`) and opens each in a `DataWorkbench`. A board over
 * datasets is an ordinary board whose panels name them.
 */
export function DatasetWorkbench({
  datasets: served,
  datasetId,
  onDatasetChange,
  className,
  ...workbench
}: DatasetWorkbenchProps) {
  const { datasets, error, refresh } = served;
  const loading = served.status === 'loading';
  const messages = useViewMessages(workbench.messages, workbench.locale);
  const labelId = useId();
  const [own, setOwn] = useState<string | null>(null);
  const asked = datasetId === undefined ? own : datasetId;
  const open =
    datasets.find(({ id }) => id === asked)?.id ?? datasets[0]?.id ?? null;
  const pick = (id: string) => {
    if (datasetId === undefined) setOwn(id);
    onDatasetChange?.(id);
  };
  return (
    <div
      data-slot="dataset-workbench"
      className={cn('fve:flex fve:min-h-0 fve:flex-1 fve:flex-col', className)}
    >
      <ViewSurface
        engine={workbench.engine}
        colorMode={workbench.colorMode}
        preset={workbench.preset}
        density={workbench.density}
        tokens={workbench.tokens}
        messages={workbench.messages}
        locale={workbench.locale}
      >
        <div className="fve:flex fve:items-center fve:gap-2 fve:border-b fve:px-3 fve:py-2">
          <span id={labelId} className="fve:text-sm fve:text-muted-foreground">
            {messages.label('label.dataset.picker')}
          </span>
          <Select
            items={datasets.map(dataset => ({
              value: dataset.id,
              label: nameOf(dataset),
            }))}
            value={open}
            disabled={datasets.length === 0}
            onValueChange={value => {
              const next = datasets.find(({ id }) => id === value);
              if (next) pick(next.id);
            }}
          >
            <SelectTrigger size="sm" aria-labelledby={labelId}>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectGroup>
                {datasets.map(dataset => (
                  <SelectItem key={dataset.id} value={dataset.id}>
                    {nameOf(dataset)}
                  </SelectItem>
                ))}
              </SelectGroup>
            </SelectContent>
          </Select>
          {loading && (
            <Spinner aria-label={messages.label('label.dataset.loading')} />
          )}
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            aria-label={messages.label('label.dataset.refresh')}
            title={messages.label('label.dataset.refresh')}
            disabled={loading}
            onClick={refresh}
          >
            <RefreshCwIcon />
          </Button>
        </div>
        {/* Said above whatever was read before, which stays open. */}
        {error && (
          <div className="fve:px-3 fve:pt-2">
            <Alert variant="destructive" data-slot="dataset-failed">
              <AlertDescription>{messages.issue(error)}</AlertDescription>
            </Alert>
          </div>
        )}
      </ViewSurface>
      {open === null ? (
        // Nothing read yet is not "none served": the failure above says why.
        loading || error ? null : (
          <ViewSurface
            engine={workbench.engine}
            colorMode={workbench.colorMode}
            preset={workbench.preset}
            messages={workbench.messages}
            locale={workbench.locale}
          >
            <Empty data-slot="dataset-empty">
              <EmptyHeader>
                <EmptyMedia variant="icon">
                  <DatabaseIcon />
                </EmptyMedia>
                <EmptyTitle>{messages.label('label.dataset.none')}</EmptyTitle>
                <EmptyDescription>
                  {messages.label('label.dataset.none-hint')}
                </EmptyDescription>
              </EmptyHeader>
            </Empty>
          </ViewSurface>
        )
      ) : (
        // Another dataset is another workbench: its views, its sidebar.
        <DataWorkbench key={open} {...workbench} definitionId={open} />
      )}
    </div>
  );
}
