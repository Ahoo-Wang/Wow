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

import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  ViewStoreError,
  systemInstanceId,
  type AnyViewRuntime,
  type ViewInstance,
  type ViewPermissions,
} from '../src/index.js';
import { useSaveCommands, useViewRuntime } from '../src/react/index.js';
import { ViewHeader } from '../src/ui/ViewHeader.js';
import { ViewSurface } from '../src/ui/ViewSurface.js';
import { WriteOutcome } from '../src/ui/WriteOutcome.js';
import {
  deferred,
  ordersDefinition,
  recordConfig,
  testSource,
} from './fixtures.js';

afterEach(cleanup);

const mine: ViewInstance = {
  id: 'orders-1',
  definitionId: 'orders',
  title: 'Mine',
  scope: 'personal',
  revision: '1',
  config: recordConfig(),
};

function permitting(
  overrides: Partial<ViewPermissions> = {},
): () => ViewPermissions {
  return () => ({
    createPersonal: true,
    createShared: true,
    reorder: true,
    setDefault: true,
    instance: () => ({ save: true, rename: true, delete: true }),
    ...overrides,
  });
}

function setup(permissions = permitting()) {
  const store = new MemoryViewStore({ instances: [mine], permissions });
  const engine = new ViewEngine({
    definitions: [ordersDefinition()],
    store,
    resolveSource: () => testSource(),
  });
  return { engine, store };
}

function Harness({
  engine,
  runtime,
}: {
  engine: ViewEngine;
  runtime: AnyViewRuntime;
}) {
  const state = useViewRuntime(runtime);
  const commands = useSaveCommands(engine, runtime);
  return (
    <ViewSurface>
      <ViewHeader state={state} kind="record" commands={commands} />
    </ViewSurface>
  );
}

/** One open view with its header on screen, ready to be edited and saved. */
async function open(
  instanceId = 'orders-1',
  permissions = permitting(),
): Promise<{
  engine: ViewEngine;
  store: MemoryViewStore;
  runtime: AnyViewRuntime;
}> {
  const { engine, store } = setup(permissions);
  const runtime = await engine.open(instanceId);
  render(<Harness engine={engine} runtime={runtime} />);
  return { engine, store, runtime };
}

/** An edit the user could have made, so there is something to save. */
function editIt(runtime: AnyViewRuntime, pageSize = 25) {
  act(() => runtime.edit({ pageSize }));
}

describe('SaveActions, the split button group', () => {
  it('has nothing to save until the view is edited', async () => {
    const { runtime } = await open();

    expect(
      screen.getByRole('button', { name: 'Save' }).hasAttribute('disabled'),
    ).toBe(true);

    editIt(runtime);
    expect(
      screen.getByRole('button', { name: 'Save' }).hasAttribute('disabled'),
    ).toBe(false);
  });

  it('saves in place and says so', async () => {
    const { store, runtime } = await open();
    editIt(runtime);

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(async () =>
      expect((await store.get('orders-1')).revision).toBe('2'),
    );
    // Said on the button, and announced separately: a screen reader does not
    // re-read the control the user has just pressed.
    expect(await screen.findByRole('button', { name: 'Saved' })).toBeDefined();
    expect(screen.getByRole('status').textContent).toBe('View saved');
  });

  it('stops saying it the moment the view is edited again', async () => {
    const { runtime } = await open();
    editIt(runtime);
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByRole('button', { name: 'Saved' });

    editIt(runtime, 40);

    expect(screen.queryByRole('status')).toBeNull();
    expect(screen.getByRole('button', { name: 'Save' })).toBeDefined();
  });

  it('stops saying it once the moment has passed', async () => {
    const { runtime } = await open();
    editIt(runtime);
    // The word is timed, so time is driven rather than waited on: the store
    // here resolves on microtasks, which a fake clock does not hold up.
    vi.useFakeTimers();
    try {
      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: 'Save' }));
      });
      expect(screen.getByRole('button', { name: 'Saved' })).toBeDefined();

      act(() => {
        vi.advanceTimersByTime(3000);
      });

      expect(screen.getByRole('button', { name: 'Save' })).toBeDefined();
      expect(screen.queryByRole('status')).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('says it is saving while the write is in flight', async () => {
    const { engine, store } = setup();
    const held = deferred<ViewInstance>();
    vi.spyOn(store, 'save').mockReturnValueOnce(held.promise);
    const runtime = await engine.open('orders-1');
    render(<Harness engine={engine} runtime={runtime} />);
    editIt(runtime);

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    // The spinner names itself, so the button reads as both at once.
    const saving = await screen.findByRole('button', { name: /Saving/ });
    expect(saving.hasAttribute('disabled')).toBe(true);
    act(() => held.resolve({ ...mine, revision: '2' }));
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: /Saving/ })).toBeNull(),
    );
  });

  /**
   * A save in flight is about to become the baseline. Reverting under it
   * would put the config the save is writing *away* back into the draft, and
   * the moment the write lands that draft is dirty over the very edits it
   * was meant to undo.
   */
  it('holds the menu shut while a write is in flight', async () => {
    const { engine, store } = setup();
    const held = deferred<ViewInstance>();
    vi.spyOn(store, 'save').mockReturnValueOnce(held.promise);
    const runtime = await engine.open('orders-1');
    render(<Harness engine={engine} runtime={runtime} />);
    editIt(runtime);

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByRole('button', { name: /Saving/ });

    expect(
      screen.getByRole('button', { name: 'More view actions' }),
    ).toHaveProperty('disabled', true);

    act(() => held.resolve({ ...mine, revision: '2' }));
    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: 'More view actions' }),
      ).toHaveProperty('disabled', false),
    );
  });

  /**
   * A refusal is over: the store never took the write, nothing is pending,
   * and the next thing the user should be able to do is try again. The
   * primary button used to stay disabled for the rest of the session.
   */
  it('lets a refused save be made again', async () => {
    const { store, runtime } = await open();
    vi.spyOn(store, 'save').mockRejectedValueOnce(
      new ViewStoreError('FORBIDDEN', 'not yours'),
    );
    editIt(runtime);

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByRole('alert');

    const save = screen.getByRole('button', { name: 'Save' });
    expect(save).toHaveProperty('disabled', false);
    fireEvent.click(save);

    await waitFor(async () =>
      expect((await store.get('orders-1')).revision).toBe('2'),
    );
  });

  it('takes the edits back from the menu', async () => {
    const { runtime } = await open();
    editIt(runtime);

    fireEvent.click(screen.getByRole('button', { name: 'More view actions' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Revert' }));

    await waitFor(() => expect(runtime.getSnapshot().dirty).toBe(false));
  });

  /**
   * A system view is nobody's to write to, so the group is a copy rather than
   * a disabled Save: a control that can never be pressed teaches nothing.
   */
  it('offers a copy when saving in place is not allowed', async () => {
    await open(systemInstanceId('orders', 'all'));

    expect(screen.queryByRole('button', { name: 'Save' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Save as' })).toBeDefined();
    expect(
      screen.queryByRole('button', { name: 'More view actions' }),
    ).toBeNull();
  });

  it('drops the menu when there is nothing to put in it', async () => {
    // May save this view, may create none: no copy to offer, and nothing
    // edited yet, so nothing to take back either.
    await open(
      'orders-1',
      permitting({ createPersonal: false, createShared: false }),
    );

    expect(screen.getByRole('button', { name: 'Save' })).toBeDefined();
    expect(
      screen.queryByRole('button', { name: 'More view actions' }),
    ).toBeNull();
  });

  /**
   * `hasErrors` judges the draft for the audience it already sits in, which
   * is what a save in place asks. The primary button asks something else
   * when the user may not write here — Save As creates a copy somewhere else
   * — and the dialog judges its own target. Disabling it for the current
   * audience's verdict locked the only way out a reader of a system view has.
   */
  it('still offers a copy when the draft is invalid where it sits', async () => {
    const { runtime } = await open(systemInstanceId('orders', 'all'));
    // Not a page size any view may have, so `hasErrors` is true.
    act(() => runtime.edit({ pageSize: 0 }));

    await waitFor(() =>
      expect(runtime.getSnapshot().issues.some(f => f.severity === 'error')),
    );
    expect(
      screen.getByRole('button', { name: 'Save as' }).hasAttribute('disabled'),
    ).toBe(false);
  });

  it('keeps only the way back when nothing may be written', async () => {
    const { runtime } = await open(
      systemInstanceId('orders', 'all'),
      permitting({ createPersonal: false, createShared: false }),
    );

    expect(screen.queryByRole('button', { name: 'Save' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Save as' })).toBeNull();

    editIt(runtime);
    expect(screen.getByRole('button', { name: 'Revert' })).toBeDefined();

    fireEvent.click(screen.getByRole('button', { name: 'Revert' }));
    await waitFor(() => expect(runtime.getSnapshot().dirty).toBe(false));
    expect(screen.queryByRole('button', { name: 'Revert' })).toBeNull();
  });
});

describe('the save-as dialog', () => {
  async function openCopy(runtime?: AnyViewRuntime) {
    void runtime;
    fireEvent.click(screen.getByRole('button', { name: 'More view actions' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Save as' }));
    return screen.findByRole('dialog');
  }

  it('keeps the dialog open and says why when the store refuses', async () => {
    const { store } = await open();
    vi.spyOn(store, 'create').mockRejectedValueOnce(
      new ViewStoreError('FORBIDDEN', 'not yours'),
    );

    const dialog = await openCopy();
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Create view' }),
    );

    const alert = await within(dialog).findByRole('alert');
    expect(alert.textContent).toContain('You may not write to this view.');
    // Still open: the reason belongs beside the form it belongs to.
    expect(
      within(dialog).getByRole('button', { name: 'Create view' }),
    ).toBeDefined();
  });

  it('will not create a view with no title', async () => {
    await open();

    const dialog = await openCopy();
    fireEvent.change(within(dialog).getByLabelText('Title'), {
      target: { value: '   ' },
    });
    expect(
      within(dialog)
        .getByRole('button', { name: 'Create view' })
        .hasAttribute('disabled'),
    ).toBe(true);
  });

  it('forgets the last answer the next time it is asked', async () => {
    await open();

    const first = await openCopy();
    fireEvent.change(within(first).getByLabelText('Title'), {
      target: { value: 'Something else' },
    });
    fireEvent.click(within(first).getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());

    const second = await openCopy();
    expect(
      (within(second).getByLabelText('Title') as HTMLInputElement).value,
    ).toBe('Mine copy');
  });
});

describe('WriteOutcome', () => {
  /** Someone else saved the view between opening it and saving it. */
  async function conflicted() {
    const opened = await open();
    await opened.store.save('orders-1', recordConfig({ pageSize: 30 }), '1', {
      requestId: 'other',
    });
    editIt(opened.runtime);
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByText('Someone else saved this view first');
    return opened;
  }

  it('puts the choice once more, with both ways of looking side by side', async () => {
    await conflicted();

    fireEvent.click(screen.getByRole('button', { name: 'Take theirs' }));

    const dialog = await screen.findByRole('dialog');
    expect(dialog.textContent).toContain('Take their version?');
    // Neither config is readable, so each is summarised by what differs.
    expect(dialog.textContent).toContain('25 per page');
    expect(dialog.textContent).toContain('30 per page');
  });

  it('adopts the server version once that choice is confirmed', async () => {
    const { runtime } = await conflicted();

    fireEvent.click(screen.getByRole('button', { name: 'Take theirs' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Take theirs' }),
    );

    await waitFor(() =>
      expect(runtime.getSnapshot().saved?.revision).toBe('2'),
    );
  });

  it('writes over the server version once that choice is confirmed', async () => {
    const { store } = await conflicted();

    fireEvent.click(screen.getByRole('button', { name: 'Keep mine' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Keep mine' }));

    await waitFor(async () =>
      expect((await store.get('orders-1')).revision).toBe('3'),
    );
  });

  it('lets the conflict be settled by making a copy instead', async () => {
    const { store } = await conflicted();

    fireEvent.click(screen.getByRole('button', { name: 'Save my copy' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('Title'), {
      target: { value: 'Mine after all' },
    });
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Create view' }),
    );

    await waitFor(async () =>
      expect((await store.list('orders')).map(item => item.title)).toContain(
        'Mine after all',
      ),
    );
  });

  /**
   * A copy is one of the three ways out of a conflict, and the only one that
   * leaves the original write untouched unless it is settled here. The host
   * opens the copy next, which releases this runtime — and a pending write
   * whose runtime is gone can never be retried, overwritten or abandoned by
   * anybody again, while the engine goes on holding the slot against the
   * next write to the same view.
   */
  it('settles the write the copy came out of', async () => {
    const { engine, store, runtime } = await conflicted();
    expect(engine.pendingWrites().size).toBe(1);

    fireEvent.click(screen.getByRole('button', { name: 'Save my copy' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('Title'), {
      target: { value: 'Mine after all' },
    });
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Create view' }),
    );

    await waitFor(async () =>
      expect((await store.list('orders')).map(item => item.title)).toContain(
        'Mine after all',
      ),
    );
    await waitFor(() => expect(runtime.getSnapshot().write).toBeNull());
    expect(engine.pendingWrites().size).toBe(0);
    // And the line that offered the three ways out is gone with it.
    expect(screen.queryByText('Someone else saved this view first')).toBeNull();
  });

  /**
   * Revert while a conflict is on screen would put the saved config back and
   * then be undone by whichever answer the user is still about to give. The
   * menu was locked on `pending` alone, and a conflict is not pending — it is
   * waiting, which is worse: nothing is in flight to finish it.
   */
  it('locks the menu while the outcome is still unsettled', async () => {
    await conflicted();

    expect(
      screen
        .getByRole('button', { name: 'More view actions' })
        .hasAttribute('disabled'),
    ).toBe(true);
  });

  it('locks the menu while the result of a write is unknown', async () => {
    const { store, runtime } = await open();
    vi.spyOn(store, 'save').mockRejectedValueOnce(new Error('socket closed'));
    editIt(runtime);

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByText('The result never came back');

    expect(
      screen
        .getByRole('button', { name: 'More view actions' })
        .hasAttribute('disabled'),
    ).toBe(true);
  });

  /**
   * The copy is how the conflict ends however the copy itself goes. Once the
   * create has an outcome, the runtime reports *that* one, and the conflict's
   * handle is unreachable from the screen: settling it only on the happy path
   * left the original write in `engine.pendingWrites()` for the session.
   */
  it('settles the write the copy came out of even when the copy fails', async () => {
    const { engine, store } = await conflicted();
    expect(engine.pendingWrites().size).toBe(1);
    vi.spyOn(store, 'create').mockRejectedValueOnce(new Error('socket closed'));

    fireEvent.click(screen.getByRole('button', { name: 'Save my copy' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Create view' }),
    );

    // The copy's own outcome is what the view reports now, and it is the
    // only write left for anyone to answer for.
    await screen.findByText('The result never came back');
    await waitFor(() => expect(engine.pendingWrites().size).toBe(1));
    expect([...engine.pendingWrites().values()][0].payload.action).toBe(
      'create',
    );
    expect(screen.queryByText('Someone else saved this view first')).toBeNull();
  });

  /**
   * Two clicks, one handle: the engine refuses the second with
   * `view.write.in-flight`, and the line would report that as the failure of
   * a click that was only impatient.
   */
  it('takes one recovery at a time', async () => {
    const { store, runtime } = await open();
    vi.spyOn(store, 'save').mockRejectedValueOnce(new Error('socket closed'));
    editIt(runtime);
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByText('The result never came back');

    const held = deferred<ViewInstance>();
    vi.spyOn(store, 'save').mockReturnValueOnce(held.promise);
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));

    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: 'Retry' }).hasAttribute('disabled'),
      ).toBe(true),
    );
    expect(
      screen.getByRole('button', { name: 'Leave it' }).hasAttribute('disabled'),
    ).toBe(true);
    await act(async () => {
      held.resolve({ ...mine, revision: '2' });
    });
  });

  it('takes one conflict answer at a time', async () => {
    const { store } = await conflicted();
    // The copy is the one way out that leaves the conflict on screen while it
    // runs, so it is the window in which a second answer could be given. The
    // dialog over it is modal, so the strip is read off the document rather
    // than through the accessibility tree.
    const held = deferred<ViewInstance>();
    vi.spyOn(store, 'create').mockReturnValueOnce(held.promise);

    // The conflict reaches the screen one render before `pending` clears:
    // the runtime's write lands first, the command's own progress after.
    // Clicking in that gap hits a disabled button and no dialog ever opens.
    const copy = screen.getByRole('button', { name: 'Save my copy' });
    await waitFor(() => expect(copy.hasAttribute('disabled')).toBe(false));
    fireEvent.click(copy);
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Create view' }),
    );

    const strip = document.querySelector('[data-slot="write-outcome"]');
    await waitFor(() =>
      expect(
        [...(strip?.querySelectorAll('button') ?? [])].map(button => [
          button.textContent,
          button.hasAttribute('disabled'),
        ]),
      ).toEqual([
        ['Take theirs', true],
        ['Save my copy', true],
        ['Keep mine', true],
      ]),
    );
    await act(async () => {
      held.resolve({ ...mine, id: 'orders-2', title: 'Mine copy' });
    });
  });

  it('offers a retry when the result never came back', async () => {
    const { store, runtime } = await open();
    vi.spyOn(store, 'save').mockRejectedValueOnce(new Error('socket closed'));
    editIt(runtime);

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByText('The result never came back');

    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await waitFor(async () =>
      expect((await store.get('orders-1')).revision).toBe('2'),
    );
  });

  it('lets an unknown result be left alone', async () => {
    const { store, runtime } = await open();
    vi.spyOn(store, 'save').mockRejectedValueOnce(new Error('socket closed'));
    editIt(runtime);

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByText('The result never came back');

    fireEvent.click(screen.getByRole('button', { name: 'Leave it' }));
    await waitFor(() =>
      expect(screen.queryByText('The result never came back')).toBeNull(),
    );
  });

  /**
   * A refusal has nothing to retry or overwrite, but it does have to go: the
   * engine holds it until it is settled, and the line sits over the view
   * until someone takes it down.
   */
  it('lets a refusal be dismissed once it has been read', async () => {
    const { engine, store, runtime } = await open();
    vi.spyOn(store, 'save').mockRejectedValueOnce(
      new ViewStoreError('FORBIDDEN', 'not yours'),
    );
    editIt(runtime);

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByText(/You may not write to this view/);
    expect(engine.pendingWrites().size).toBe(1);

    fireEvent.click(screen.getByRole('button', { name: 'Dismiss' }));

    await waitFor(() =>
      expect(screen.queryByText(/You may not write to this view/)).toBeNull(),
    );
    expect(engine.pendingWrites().size).toBe(0);
    expect(runtime.getSnapshot().write).toBeNull();
  });

  it('says why a write was refused, in words rather than a code', async () => {
    const { store, runtime } = await open();
    vi.spyOn(store, 'save').mockRejectedValueOnce(
      new ViewStoreError('FORBIDDEN', 'not yours'),
    );
    editIt(runtime);

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('You may not write to this view.');
    expect(alert.textContent).toContain('not yours');
  });

  /**
   * A host may wire only the generic callback and keep its list fresh there;
   * a recovered delete is still a recovered write, and the view it deleted is
   * still gone.
   */
  it('tells onRecovered about a recovered delete too', async () => {
    const onDeleted = vi.fn();
    const onRecovered = vi.fn();
    const commands = {
      can: { save: true, saveAs: true },
      state: {
        pending: false,
        error: null,
        dirty: false,
        blocked: true,
        lastSavedAt: null,
        write: {
          kind: 'unknown',
          requestId: 'r1',
          payload: { action: 'delete', id: 'orders-1', revision: '1' },
        },
      },
      retry: vi.fn().mockResolvedValue({ landed: true, instance: null }),
      abandon: vi.fn(),
    };
    render(
      <ViewSurface>
        <WriteOutcome
          commands={commands as never}
          title="Mine"
          onDeleted={onDeleted}
          onRecovered={onRecovered}
        />
      </ViewSurface>,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));

    await waitFor(() => expect(onDeleted).toHaveBeenCalled());
    expect(onRecovered).toHaveBeenCalledWith('delete');
  });

  it('tells onRenamed about a recovered rename', async () => {
    const onRenamed = vi.fn();
    const renamed: ViewInstance = { ...mine, title: 'Renamed', revision: '2' };
    const commands = {
      can: { save: true, saveAs: true },
      state: {
        pending: false,
        error: null,
        dirty: false,
        blocked: true,
        lastSavedAt: null,
        write: {
          kind: 'unknown',
          requestId: 'r1',
          payload: {
            action: 'rename',
            id: 'orders-1',
            revision: '1',
            title: 'Renamed',
          },
        },
      },
      retry: vi.fn().mockResolvedValue({ landed: true, instance: renamed }),
      abandon: vi.fn(),
    };
    render(
      <ViewSurface>
        <WriteOutcome
          commands={commands as never}
          title="Mine"
          onRenamed={onRenamed}
        />
      </ViewSurface>,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));

    await waitFor(() => expect(onRenamed).toHaveBeenCalledWith(renamed));
  });
});
