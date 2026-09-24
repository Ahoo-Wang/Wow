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

import { useCallback, useMemo, useRef, useState, type ReactNode } from 'react';
import { isOwnedPanel } from '../../dashboard/index.js';
import type { Issue, ViewAudience, ViewInstance } from '../../model/index.js';
import { toIssue, type DashboardController } from '../../react/index.js';
import type { DashboardRuntime, ViewEngine } from '../../runtime/index.js';
import { panelNames } from '../DashboardPanel.js';
import type { MessageFormatters } from '../MessagesProvider.js';
import { SaveAsDialog } from '../SaveAsDialog.js';
import type { DashboardEditExtensions, NewPanelSpot } from './extensions.js';
import { NewAnalysisDialog, type NewAnalysis } from './NewAnalysisDialog.js';
import { PresentationDialog } from './PresentationDialog.js';

export interface DashboardExtensionsOptions {
  engine: ViewEngine;
  /** The board, while one is open. */
  board: DashboardRuntime | null;
  dashboard: DashboardController;
  /**
   * The wording in force where the board is drawn — the workbench's own,
   * merged over the host's — for what the dialogs announce.
   */
  messages: MessageFormatters;
  /** The tab bar, drawn under the edit bar and over the panels. */
  tabBar?: ReactNode;
  /**
   * The board's one voice (`useAnnouncer`): what a dialog did is said where
   * everything else on the board is, never in a second region of its own.
   */
  say(message: string): void;
  optionsFor?: Parameters<typeof NewAnalysisDialog>[0]['optionsFor'];
}

/** What is open: nothing, or one of the dialogs and what it is for. */
type Open =
  | { kind: 'new'; spot: NewPanelSpot }
  | { kind: 'look'; panelId: string }
  | { kind: 'promote'; panelId: string }
  | { kind: 'share'; panelId: string; view: ViewInstance };

/**
 * The control a dialog was asked for from: the trigger of the menu that is
 * open as its item is pressed (the item goes with the menu), else what has
 * the focus — an empty board's own button.
 */
function opener(): HTMLElement | null {
  if (typeof document === 'undefined') return null;
  const trigger = document.querySelector<HTMLElement>(
    '[aria-haspopup="menu"][aria-expanded="true"]',
  );
  const active = document.activeElement;
  return trigger ?? (active instanceof HTMLElement ? active : null);
}

/**
 * The board's extensions (`DashboardEditExtensions`, D22 C–E) as the default
 * workbench fills them: 「新建分析」 opens the dialog a board-owned analysis
 * is made in, 「改这里的展示」 the visualization panel for one panel's look,
 * 「恢复为视图的样子」 puts the view's own look back, 「另存为视图」 the save-as
 * dialog that promotes an owned analysis, 「复制为共享视图并替换」 the same
 * dialog copying a personal view for a shared board, and the tab bar. The edit bar and
 * the panel menu read them (`panelCommands`) and show an entry only while
 * the board is built; here are the commands and the dialogs they open,
 * which the workbench draws beside the board.
 *
 * Every command goes through the board's own edits (`DashboardEditing`),
 * so nothing is written until 完成 saves the board.
 */
export function useDashboardExtensions({
  engine,
  board,
  dashboard,
  messages,
  tabBar,
  optionsFor,
  say,
}: DashboardExtensionsOptions): {
  extensions: DashboardEditExtensions;
  dialogs: ReactNode;
} {
  const [open, setOpen] = useState<Open | null>(null);
  // Where the keyboard goes as a dialog closes: the control that asked,
  // noted as it asks. Read through one stable function — the dialog's focus
  // manager re-arms whenever `finalFocus` is a new function, and hands the
  // keyboard back on the way, which would take it out of an open dialog.
  const back = useRef<HTMLElement | null>(null);
  const finalFocus = useCallback(
    () => (back.current?.isConnected ? back.current : true),
    [],
  );
  const ask = useCallback((next: Open) => {
    back.current = opener();
    setOpen(next);
  }, []);
  const [saving, setSaving] = useState<{
    pending: boolean;
    error: Issue | null;
  }>({ pending: false, error: null });
  const names = panelNames(dashboard.panels, messages);
  const panelOf = (panelId: string | undefined) =>
    dashboard.panels.find(panel => panel.id === panelId) ?? null;

  // Made once per board, so a menu reading them does not change every
  // render; each reads the board when it runs.
  const commands = useMemo<DashboardEditExtensions>(
    () =>
      board
        ? {
            onAddOwnedAnalysis: spot => ask({ kind: 'new', spot }),
            onEditPresentation: panelId => ask({ kind: 'look', panelId }),
            onResetPresentation: panelId =>
              board.setPresentation(panelId, null),
            onSaveOwnedAsView: panelId => {
              setSaving({ pending: false, error: null });
              ask({ kind: 'promote', panelId });
            },
            copyAsShared: {
              offered: definitionId =>
                engine.permissions(definitionId).createShared,
              open: panelId => {
                // The view as it was when asked: the copy lands before the
                // dialog closes, and the panel then shows the copy.
                const view = board.panelRuntime(panelId)?.getSnapshot().saved;
                if (!view) return;
                setSaving({ pending: false, error: null });
                ask({ kind: 'share', panelId, view });
              },
            },
          }
        : {},
    [board, ask, engine],
  );
  const extensions = useMemo<DashboardEditExtensions>(
    () => (tabBar === undefined ? commands : { ...commands, tabBar }),
    [commands, tabBar],
  );

  const add = (analysis: NewAnalysis): boolean => {
    if (!board || open?.kind !== 'new') return false;
    const id = board.addPanel(
      {
        kind: 'view',
        title: analysis.title,
        owned: { definitionId: analysis.definitionId, config: analysis.config },
      },
      open.spot,
    );
    if (id === null) return false;
    say(
      messages.label('label.panel.new-analysis.added', {
        title: analysis.title,
      }),
    );
    return true;
  };

  const close = (next: boolean) => {
    if (!next) setOpen(null);
  };
  const looked = open?.kind === 'look' ? panelOf(open.panelId) : null;
  const promoted = open?.kind === 'promote' ? panelOf(open.panelId) : null;
  const owned =
    promoted?.panel.kind === 'view' && isOwnedPanel(promoted.panel)
      ? promoted.panel.owned
      : null;
  const ownedDefinition = owned
    ? engine.definitions.get(owned.definitionId)
    : undefined;
  const can = owned
    ? engine.permissions(owned.definitionId)
    : { createPersonal: false, createShared: false };
  // The saved view a shared board's panel stands on, while it is copied.
  const sharing = open?.kind === 'share' ? open : null;
  const source = sharing?.view ?? null;
  const boardScope: ViewAudience =
    board?.getSnapshot().scope === 'personal' ? 'personal' : 'shared';

  const dialogs = board && (
    <>
      <NewAnalysisDialog
        engine={engine}
        open={open?.kind === 'new'}
        onOpenChange={close}
        onAdd={add}
        optionsFor={optionsFor}
        finalFocus={finalFocus}
      />
      <PresentationDialog
        panel={looked}
        name={looked ? (names.get(looked.id) ?? '') : ''}
        editing={board}
        onOpenChange={close}
        finalFocus={finalFocus}
      />
      <SaveAsDialog
        open={owned !== null}
        onOpenChange={close}
        finalFocus={finalFocus}
        intent="promote"
        title={promoted ? (names.get(promoted.id) ?? '') : ''}
        description={messages.label('label.panel.save-owned.description', {
          definition: ownedDefinition?.title ?? '',
        })}
        // The board's audience first: a shared board that stood on a
        // personal view would be a panel some of its readers cannot open.
        defaultScope={boardScope}
        commands={{
          can,
          state: saving,
          saveAs: async ({ title, scope }) => {
            if (!promoted) return null;
            setSaving({ pending: true, error: null });
            try {
              const instance = await engine.saveOwnedView(board, promoted.id, {
                title,
                scope,
              });
              setSaving({ pending: false, error: null });
              say(messages.label('label.panel.save-owned.saved', { title }));
              return instance;
            } catch (error) {
              setSaving({
                pending: false,
                error: toIssue(error, 'view.save.failed'),
              });
              return null;
            }
          },
        }}
      />
      <SaveAsDialog
        open={source !== null}
        onOpenChange={close}
        finalFocus={finalFocus}
        intent="share"
        title={source?.title ?? ''}
        description={messages.label('label.panel.copy-shared.description', {
          view: source?.title ?? '',
          definition: source
            ? (engine.definitions.get(source.definitionId)?.title ?? '')
            : '',
        })}
        commands={{
          can: source
            ? engine.permissions(source.definitionId)
            : { createPersonal: false, createShared: false },
          state: saving,
          saveAs: async ({ title, scope }) => {
            if (!sharing) return null;
            setSaving({ pending: true, error: null });
            try {
              const instance = await engine.copyPanelView(
                board,
                sharing.panelId,
                {
                  title,
                  scope,
                },
              );
              setSaving({ pending: false, error: null });
              say(messages.label('label.panel.copy-shared.saved', { title }));
              return instance;
            } catch (error) {
              setSaving({
                pending: false,
                error: toIssue(error, 'view.save.failed'),
              });
              return null;
            }
          },
        }}
      />
    </>
  );
  return { extensions, dialogs };
}
