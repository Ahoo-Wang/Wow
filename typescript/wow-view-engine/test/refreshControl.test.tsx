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
  render,
  renderHook,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { PagedList } from '@ahoo-wang/fetcher-wow';
import {
  DEFAULT_RUNTIME_LIMITS,
  MemoryViewStore,
  ViewEngine,
} from '../src/index.js';
import type {
  RecordData,
  RuntimeLimits,
  ViewInstance,
  ViewSource,
} from '../src/index.js';
import type { RecordViewRuntime, ViewRuntime } from '../src/runtime/index.js';
import { useAutoRefresh, type RefreshController } from '../src/react/index.js';
import {
  DataWorkbench,
  DashboardWorkbench,
  defaultMessages,
  MessagesProvider,
  RefreshControl,
  ViewSurface,
  zhCN,
} from '../src/ui/index.js';
import {
  analysisConfig,
  dashboardConfig,
  deferred,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  testEnvironment,
  testSource,
} from './fixtures.js';
import { refreshController } from './fixtures/ui.js';

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

/**
 * Auto refresh had a contract and no way in: the interval was saved with the
 * view, bounded by the limits and run by the runtime's one timer, and the
 * only way to set it was to write the config by hand. These cover the way in
 * — the split button — and hold it to the two rules it could most easily
 * break: the interval it edits is the config's own member, and an interval
 * the limits refuse is absent rather than disabled (D4).
 */

const REFRESH = defaultMessages['label.toolbar.refresh'];
const AUTO = defaultMessages['label.refresh.auto'];
const OFF = defaultMessages['label.refresh.off'];

/** An interval as the menu writes it: "30s", "5 min", "1 h". */
const every = (seconds: number): string =>
  seconds >= 3600 && seconds % 3600 === 0
    ? defaultMessages['label.refresh.hours'].replace(
        '{count}',
        String(seconds / 3600),
      )
    : seconds >= 60 && seconds % 60 === 0
      ? defaultMessages['label.refresh.minutes'].replace(
          '{count}',
          String(seconds / 60),
        )
      : defaultMessages['label.refresh.seconds'].replace(
          '{count}',
          String(seconds),
        );

/**
 * What is **left** of the interval, as the key writes it: floored to the
 * largest whole unit, so the fifth minute reads "4 min" for all of itself.
 */
const left = (seconds: number): string =>
  seconds >= 3600
    ? defaultMessages['label.refresh.hours'].replace(
        '{count}',
        String(Math.floor(seconds / 3600)),
      )
    : seconds >= 60
      ? defaultMessages['label.refresh.minutes'].replace(
          '{count}',
          String(Math.floor(seconds / 60)),
        )
      : defaultMessages['label.refresh.seconds'].replace(
          '{count}',
          String(seconds),
        );

/** The text on the key, which is the countdown while one is running. */
const reading = (container: HTMLElement): string | undefined =>
  container.querySelector('[data-slot="refresh-cadence"]')?.textContent ??
  undefined;

/**
 * A view whose timer is armed, counting on the same fake clock the ticker
 * runs on — which is what a runtime hands a control: a due time, and a
 * reading of it taken against the clock that timer runs on.
 */
function counting(
  interval: number,
  overrides: Partial<RefreshController> = {},
): RefreshController {
  const dueAt = Date.now() + interval * 1000;
  return refreshController({
    interval,
    chosen: interval,
    dueAt,
    remaining: () => Math.max(0, Math.ceil((dueAt - Date.now()) / 1000)),
    ...overrides,
  });
}

const orders: ViewInstance = {
  id: 'orders-1',
  definitionId: 'orders',
  title: 'Mine',
  scope: 'personal',
  revision: '1',
  config: recordConfig(),
};

function engineWith(options: {
  instances?: ViewInstance[];
  limits?: Partial<RuntimeLimits>;
  source?: ViewSource;
  environment?: ViewEngineEnvironment;
}): ViewEngine {
  return new ViewEngine({
    definitions: [ordersDefinition(), overviewDefinition()],
    store: new MemoryViewStore({ instances: options.instances ?? [orders] }),
    resolveSource: () => options.source ?? testSource(),
    limits: { ...DEFAULT_RUNTIME_LIMITS, ...options.limits },
    ...(options.environment ? { environment: options.environment } : {}),
  });
}

type ViewEngineEnvironment = ReturnType<typeof testEnvironment>['environment'];

/** An unsaved record view, which is a runtime without an awaited open. */
function recordRuntime(
  engine: ViewEngine,
  config = recordConfig(),
): RecordViewRuntime {
  return engine.create('orders', {
    title: 'New',
    scope: 'personal',
    config,
  });
}

/** The menu behind the `▾`, opened. */
async function openIntervals(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: AUTO }));
  return screen.findByRole('menu');
}

describe('useAutoRefresh', () => {
  it('offers only the intervals the limits admit', () => {
    const engine = engineWith({
      limits: { minRefreshInterval: 60, maxRefreshInterval: 900 },
    });
    const { result } = renderHook(() =>
      useAutoRefresh(recordRuntime(engine) as ViewRuntime),
    );

    // Absent, not disabled: the kernel refuses 30 under these limits, and an
    // option that can only be pressed to be told no teaches nothing. 900 is
    // off the ladder itself, which the limits have nothing to do with.
    expect(result.current.intervals).toEqual([60, 300]);
  });

  /** The ladder is the engine's: a product hands its own rungs in with the budgets. */
  it('offers the cadences the limits name', () => {
    const engine = engineWith({ limits: { refreshIntervals: [10, 20] } });
    const { result } = renderHook(() =>
      useAutoRefresh(recordRuntime(engine, recordConfig()) as ViewRuntime),
    );
    expect(result.current.intervals).toEqual([10, 20]);
  });

  /**
   * A view saved at an interval off the ladder still has to offer the rung
   * it is sitting on — and one the limits now refuse does not join it,
   * because that config is already refused and the way out is a rung that
   * works.
   */
  it('folds in the interval picked, unless the limits refuse it', () => {
    const engine = engineWith({
      limits: { minRefreshInterval: 30, maxRefreshInterval: 900 },
    });
    const folded = renderHook(() =>
      useAutoRefresh(
        recordRuntime(
          engine,
          recordConfig({ refresh: { interval: 45 } }),
        ) as ViewRuntime,
      ),
    );
    expect(folded.result.current.intervals).toEqual([30, 45, 60, 300]);

    const refused = renderHook(() =>
      useAutoRefresh(
        recordRuntime(
          engine,
          recordConfig({ refresh: { interval: 5 } }),
        ) as ViewRuntime,
      ),
    );
    expect(refused.result.current.intervals).toEqual([30, 60, 300]);
    // It is still what the view is *set* to, and a save would write it —
    // but it is not in force and never will be, because the same bounds
    // that keep it off the ladder had admission refuse the config.
    expect(refused.result.current.chosen).toBe(5);
    expect(refused.result.current.interval).toBeNull();
    expect(refused.result.current.unsound).toBe(true);
  });

  /**
   * `validateRefresh` refuses a fractional interval as surely as one out of
   * range, so the ladder must not offer it and the credential must not name
   * it: a cadence of 45.5 seconds is one the timer will never keep.
   */
  it('neither offers nor reports an interval the kernel would refuse', () => {
    const engine = engineWith({});
    const { result } = renderHook(() =>
      useAutoRefresh(
        recordRuntime(
          engine,
          recordConfig({ refresh: { interval: 45.5 } }),
        ) as ViewRuntime,
      ),
    );

    expect(result.current.intervals).not.toContain(45.5);
    expect(result.current.interval).toBeNull();
    // But the menu still opens on it, because `Off` is the repair.
    expect(result.current.unsound).toBe(true);
  });

  /**
   * The same for a `refresh` no reading can make sense of. Both numbers are
   * null, so nothing but `unsound` can tell "this view does not refresh
   * itself" from "this view's config cannot be read".
   */
  it('marks a refresh member admission refuses, however it is broken', () => {
    const engine = engineWith({});
    const sound = renderHook(() =>
      useAutoRefresh(recordRuntime(engine) as ViewRuntime),
    );
    expect(sound.result.current.unsound).toBe(false);

    const broken = renderHook(() =>
      useAutoRefresh(
        recordRuntime(engine, {
          ...recordConfig(),
          refresh: { interval: '30' },
        } as unknown as ReturnType<typeof recordConfig>) as ViewRuntime,
      ),
    );

    expect(broken.result.current.interval).toBeNull();
    expect(broken.result.current.chosen).toBeNull();
    expect(broken.result.current.unsound).toBe(true);
  });

  it('has nothing to offer without an open view', () => {
    const { result } = renderHook(() => useAutoRefresh(null));

    expect(result.current.intervals).toEqual([]);
    expect(result.current.interval).toBeNull();
    // Every command is a no-op rather than a throw: a workbench renders the
    // control while the view it belongs to is still opening.
    expect(() => {
      result.current.setInterval(30);
      result.current.now();
    }).not.toThrow();
  });

  /**
   * The whole point of the entry: what is chosen reaches the runtime's timer.
   * It only does so through `applied`, which is why choosing applies.
   */
  it('edits the config and applies, so the timer runs on it', async () => {
    const clock = testEnvironment();
    const source = testSource();
    const engine = engineWith({ source, environment: clock.environment });
    const runtime = recordRuntime(engine);
    const { result } = renderHook(() => useAutoRefresh(runtime as ViewRuntime));

    act(() => runtime.apply());
    await waitFor(() =>
      expect(runtime.getSnapshot().query.status).toBe('success'),
    );
    const ran = (source.paged as ReturnType<typeof vi.fn>).mock.calls.length;

    act(() => result.current.setInterval(10));
    await waitFor(() =>
      expect(runtime.getSnapshot().query.status).toBe('success'),
    );

    // The member the view saves, not a second copy of it beside the config.
    expect(runtime.getSnapshot().draft.refresh).toEqual({ interval: 10 });
    expect(runtime.getSnapshot().applied.refresh).toEqual({ interval: 10 });
    expect(clock.timers).toBe(1);

    // Applying re-ran it once; the timer is what runs it the second time.
    act(() => clock.advance(10_000));
    await waitFor(() =>
      expect((source.paged as ReturnType<typeof vi.fn>).mock.calls.length).toBe(
        ran + 2,
      ),
    );

    act(() => result.current.setInterval(null));
    await waitFor(() =>
      expect(runtime.getSnapshot().query.status).toBe('success'),
    );
    expect(clock.timers).toBe(0);
  });

  /**
   * The countdown's two members: when the runtime's timer is due, handed on
   * as it stands, and how long that is on the runtime's own clock. A control
   * that asked the system clock instead would drift away from the timer it
   * claims to be counting to the moment a host injected a clock of its own.
   */
  it('hands on the due time, and reads what is left on the runtime clock', async () => {
    const clock = testEnvironment();
    const engine = engineWith({ environment: clock.environment });
    const runtime = recordRuntime(
      engine,
      recordConfig({ refresh: { interval: 30 } }),
    );
    const { result } = renderHook(() => useAutoRefresh(runtime as ViewRuntime));

    // Nothing has run, so nothing is armed and there is nothing to count to.
    expect(result.current.dueAt).toBeNull();
    expect(result.current.remaining()).toBeNull();

    act(() => runtime.apply());
    await waitFor(() =>
      expect(runtime.getSnapshot().query.status).toBe('success'),
    );

    expect(result.current.dueAt).toBe(runtime.getSnapshot().nextRefreshAt);
    expect(result.current.remaining()).toBe(30);

    // The clock moves, the due time does not, and the reading follows the
    // clock: it is read at the moment it is asked, not at the last render.
    act(() => clock.advance(10_000));
    expect(result.current.remaining()).toBe(20);

    act(() => result.current.setInterval(null));
    await waitFor(() =>
      expect(runtime.getSnapshot().query.status).toBe('success'),
    );
    expect(result.current.dueAt).toBeNull();
    expect(result.current.remaining()).toBeNull();
  });

  /**
   * A draft the kernel refuses holds `apply` back, so the interval reaches
   * the config and not the timer — and the runtime is already holding the
   * timer for that same error, which is one of its four reasons. The UI adds
   * no fifth.
   */
  it('leaves the timer alone while the draft is refused', async () => {
    const clock = testEnvironment();
    const engine = engineWith({ environment: clock.environment });
    const runtime = recordRuntime(engine);
    const { result } = renderHook(() => useAutoRefresh(runtime as ViewRuntime));

    act(() => runtime.apply());
    await waitFor(() =>
      expect(runtime.getSnapshot().query.status).toBe('success'),
    );
    act(() =>
      runtime.edit({ pageSize: DEFAULT_RUNTIME_LIMITS.maxPageSize + 1 }),
    );
    expect(
      runtime.getSnapshot().issues.some(found => found.severity === 'error'),
    ).toBe(true);

    act(() => result.current.setInterval(10));

    expect(runtime.getSnapshot().draft.refresh).toEqual({ interval: 10 });
    expect(runtime.getSnapshot().applied.refresh).toEqual({ interval: null });
    expect(clock.timers).toBe(0);
    // And the two readings part exactly here: what is set is 10, what is in
    // force is still nothing. The control says the second one.
    expect(result.current.chosen).toBe(10);
    expect(result.current.interval).toBeNull();
  });

  /**
   * The same parting, the other way round: a view that *is* refreshing, told
   * to stop by a draft the kernel refuses. Nothing stopped — `applied` still
   * carries the interval and the timer is still armed on it — so the control
   * must go on saying so rather than reporting the switch-off that did not
   * happen.
   */
  it('goes on reporting the interval when a refused draft turns it off', async () => {
    const clock = testEnvironment();
    const engine = engineWith({ environment: clock.environment });
    const runtime = recordRuntime(
      engine,
      recordConfig({ refresh: { interval: 30 } }),
    );
    const { result } = renderHook(() => useAutoRefresh(runtime as ViewRuntime));

    act(() => runtime.apply());
    await waitFor(() =>
      expect(runtime.getSnapshot().query.status).toBe('success'),
    );
    expect(clock.timers).toBe(1);

    act(() =>
      runtime.edit({ pageSize: DEFAULT_RUNTIME_LIMITS.maxPageSize + 1 }),
    );
    act(() => result.current.setInterval(null));

    expect(runtime.getSnapshot().applied.refresh).toEqual({ interval: 30 });
    expect(result.current.chosen).toBeNull();
    expect(result.current.interval).toBe(30);
  });
});

describe('RefreshControl', () => {
  it('refreshes once from the primary half', async () => {
    const now = vi.fn();
    const user = userEvent.setup();
    render(<RefreshControl refresh={refreshController({ now })} />);

    await user.click(screen.getByRole('button', { name: REFRESH }));

    expect(now).toHaveBeenCalledTimes(1);
  });

  it('chooses an interval, and turns it off again', async () => {
    const setInterval = vi.fn();
    const user = userEvent.setup();
    const { rerender } = render(
      <RefreshControl refresh={refreshController({ setInterval })} />,
    );

    const menu = await openIntervals(user);
    await user.click(within(menu).getByRole('menuitemradio', { name: '30s' }));
    expect(setInterval).toHaveBeenCalledWith(30);

    rerender(
      <RefreshControl
        refresh={refreshController({ interval: 30, chosen: 30, setInterval })}
      />,
    );
    const again = await openIntervals(user);
    // The picked one is the one marked, so the menu says what this view is
    // set to rather than only offering what it could be.
    expect(
      within(again)
        .getByRole('menuitemradio', { name: '30s' })
        .getAttribute('aria-checked'),
    ).toBe('true');
    await user.click(within(again).getByRole('menuitemradio', { name: OFF }));
    expect(setInterval).toHaveBeenLastCalledWith(null);
  });

  /**
   * The credential D2 asks for: it says *this view refreshes itself*, in a
   * cadence rather than a dot — the dot is "edited, not applied" and belongs
   * to the editor's fold.
   */
  it('wears the cadence while an interval is in force', () => {
    const { container, rerender } = render(
      <RefreshControl refresh={refreshController()} />,
    );
    expect(container.querySelector('[data-slot="refresh-cadence"]')).toBeNull();

    rerender(<RefreshControl refresh={refreshController({ interval: 300 })} />);

    expect(
      container.querySelector('[data-slot="refresh-cadence"]')!.textContent,
    ).toBe(every(300));
    expect(
      screen
        .getByRole('button', { name: new RegExp(REFRESH) })
        .getAttribute('aria-description'),
    ).toBe(
      defaultMessages['label.refresh.on'].replace('{interval}', every(300)),
    );
  });

  /**
   * The key counts down to the next refresh rather than repeating the
   * cadence the menu already carries: the question a person asks of a screen
   * that moves by itself is *when*, and "30s" answered it only once a
   * minute by accident.
   */
  it('ticks down to the next refresh and starts again when it lands', () => {
    vi.useFakeTimers();
    const { container, rerender } = render(
      <RefreshControl refresh={counting(30)} />,
    );

    expect(reading(container)).toBe(left(30));
    act(() => vi.advanceTimersByTime(1_000));
    expect(reading(container)).toBe(left(29));
    act(() => vi.advanceTimersByTime(4_000));
    expect(reading(container)).toBe(left(25));

    // The refresh fires: the runtime clears the due time and the query goes
    // out. Zero, not blank — the count reached it, and the spinner beside
    // it says which zero this is.
    rerender(
      <RefreshControl
        refresh={refreshController({ interval: 30, chosen: 30, loading: true })}
      />,
    );
    expect(reading(container)).toBe(left(0));

    // It lands, the runtime arms the next one, and the count starts over.
    rerender(<RefreshControl refresh={counting(30)} />);
    expect(reading(container)).toBe(left(30));
  });

  /**
   * The largest whole unit, floored: "5 min" is the truth only at the top of
   * the fifth minute, and under a minute the seconds count themselves out.
   * `mm:ss` would ask to be read precisely, and nobody is timing anything.
   */
  it('reads a long interval in the largest unit it still fills', () => {
    vi.useFakeTimers();
    const { container } = render(<RefreshControl refresh={counting(300)} />);

    expect(reading(container)).toBe(left(300));
    act(() => vi.advanceTimersByTime(1_000));
    expect(reading(container)).toBe(left(299));
    expect(reading(container)).toBe('4 min');
    act(() => vi.advanceTimersByTime(240_000));
    expect(reading(container)).toBe('59s');
  });

  /**
   * A ticker is a cost, and it is paid only where there is something to
   * count: no timer armed, no interval at all, or the control gone from the
   * screen.
   */
  it('runs no ticker with nothing due, and stops the one it runs on unmount', () => {
    vi.useFakeTimers();

    // Refresh off altogether. Counted rather than spied: a spy on a global
    // timer is restored *after* the fake clock is uninstalled, which hands
    // the fake back to every suite that follows.
    const off = render(<RefreshControl refresh={refreshController()} />);
    expect(vi.getTimerCount()).toBe(0);
    off.unmount();

    // An interval in force whose timer the runtime is holding — a hidden
    // page, an editor with focus — is a cadence with nothing due, and
    // nothing to count to either.
    const held = render(
      <RefreshControl refresh={refreshController({ interval: 30 })} />,
    );
    expect(vi.getTimerCount()).toBe(0);
    // And the key says the cadence, which stays true while the clock on it
    // is paused; a frozen "7s" would be a countdown that stopped counting.
    expect(reading(held.container)).toBe(left(30));
    held.unmount();

    const running = render(<RefreshControl refresh={counting(30)} />);
    expect(vi.getTimerCount()).toBe(1);
    running.unmount();
    expect(vi.getTimerCount()).toBe(0);
  });

  /**
   * Two things about the box the count sits in: it is as wide as the widest
   * reading this interval can produce, so the buttons beside it do not walk
   * across the bar once a second, and a screen reader is told none of it —
   * the cadence reaches it once, as a sentence, through `aria-description`.
   */
  it('reserves the width of the widest reading and announces none of it', () => {
    vi.useFakeTimers();
    const { container } = render(<RefreshControl refresh={counting(300)} />);
    const box = container.querySelector('[data-slot="refresh-countdown"]')!;

    expect(box.getAttribute('aria-hidden')).toBe('true');
    // One per unit band the countdown passes through: the longest seconds
    // reading, and the longest minutes one.
    expect(
      [...box.querySelectorAll('.invisible')].map(span => span.textContent),
    ).toEqual([left(59), left(300)]);
    // The count itself is in the same cell as the reserved ones, so the box
    // never resizes while it ticks.
    const count = box.querySelector('[data-slot="refresh-cadence"]')!;
    // A **surviving class assertion**: which grid cell the countdown sits
    // in is layout, and nothing about the control's state.
    expect(count.className).toContain('col-start-1');
    expect(
      screen
        .getByRole('button', { name: new RegExp(REFRESH) })
        .getAttribute('aria-description'),
    ).toBe(
      defaultMessages['label.refresh.on'].replace('{interval}', every(300)),
    );
  });

  /**
   * What is on screen when the two part: the cadence is the interval in
   * force, and the menu marks the one picked. The other way round, the
   * button would name a cadence nothing is running to — which is why
   * `AppliedBar` reads the result rather than the draft, and why the draft
   * has a credential of its own instead of borrowing this one.
   */
  it('says the interval in force while the menu marks the one picked', async () => {
    const user = userEvent.setup();
    const { container } = render(
      <RefreshControl
        refresh={refreshController({ interval: 30, chosen: 300 })}
      />,
    );

    expect(
      container.querySelector('[data-slot="refresh-cadence"]')!.textContent,
    ).toBe(every(30));

    const menu = await openIntervals(user);
    expect(
      within(menu)
        .getByRole('menuitemradio', { name: every(300) })
        .getAttribute('aria-checked'),
    ).toBe('true');
    expect(
      within(menu)
        .getByRole('menuitemradio', { name: every(30) })
        .getAttribute('aria-checked'),
    ).toBe('false');
  });

  /** Refusing to turn it off is not the same as having turned it off. */
  it('keeps the cadence when the draft turned it off and was refused', () => {
    const { container } = render(
      <RefreshControl
        refresh={refreshController({ interval: 30, chosen: null })}
      />,
    );

    expect(
      container.querySelector('[data-slot="refresh-cadence"]')!.textContent,
    ).toBe(every(30));
  });

  /**
   * D4 again, at the other end: with nothing on offer and nothing in force
   * the chevron would open a menu whose only item is the state the view is
   * already in.
   */
  it('drops the menu when there is no interval to choose', () => {
    render(<RefreshControl refresh={refreshController({ intervals: [] })} />);

    expect(screen.queryByRole('button', { name: AUTO })).toBeNull();
    expect(screen.getByRole('button', { name: REFRESH })).toBeTruthy();
  });

  /**
   * A view that is refreshing must always be able to stop, even under
   * limits that leave the ladder empty — here the interval in force is one
   * the host's bounds admit but no rung sits on.
   */
  it('keeps the way out when the interval in force is off the ladder', async () => {
    const user = userEvent.setup();
    render(
      <RefreshControl
        refresh={refreshController({ interval: 32, chosen: 32, intervals: [] })}
      />,
    );

    const menu = await openIntervals(user);
    expect(
      within(menu)
        .getAllByRole('menuitemradio')
        .map(item => item.textContent),
    ).toEqual([OFF]);
  });

  /**
   * And a `refresh` member admission refuses must always be mendable: `Off`
   * writes `{ interval: null }`, which repairs every one of those refusals.
   * Without this the limits could leave no rung, the menu would hide itself,
   * and the config blocking Apply and Save would have nothing on screen able
   * to fix it — the trap this package keeps setting for itself.
   */
  it('keeps the menu when the stored refresh is the thing that is broken', async () => {
    const setInterval = vi.fn();
    const user = userEvent.setup();
    render(
      <RefreshControl
        refresh={refreshController({
          intervals: [],
          unsound: true,
          setInterval,
        })}
      />,
    );

    const menu = await openIntervals(user);
    await user.click(within(menu).getByRole('menuitemradio', { name: OFF }));

    expect(setInterval).toHaveBeenCalledWith(null);
  });

  /**
   * A query in flight stops the press — it would only replace itself — but
   * not the choice: an interval is a decision about the next hour, and the
   * runtime already replaces an in-flight request when `applied` moves.
   */
  it('stops the press while a query is out, not the choice', () => {
    render(<RefreshControl refresh={refreshController({ loading: true })} />);

    // The spinner names itself, so the pressed half is matched by what it
    // still says rather than by the whole of its name.
    expect(
      screen
        .getByRole('button', { name: new RegExp(REFRESH) })
        .hasAttribute('disabled'),
    ).toBe(true);
    expect(
      screen.getByRole('button', { name: AUTO }).hasAttribute('disabled'),
    ).toBe(false);
  });

  /**
   * The spinner is vendored and hardcodes `aria-label="Loading"`, which no
   * host catalogue could reach — the busy announcement stayed English under
   * `messages={zhCN}`. The catalogue test only proves the key exists in both
   * books; this one proves the call site hands it over.
   */
  it('announces the wait in the host catalogue, not in English', () => {
    render(
      <MessagesProvider messages={zhCN}>
        <RefreshControl refresh={refreshController({ loading: true })} />
      </MessagesProvider>,
    );

    expect(screen.getByRole('status').getAttribute('aria-label')).toBe(
      '加载中',
    );
    expect(screen.queryByRole('status', { name: 'Loading' })).toBeNull();
  });

  it('is reachable and operable from the keyboard', async () => {
    const setInterval = vi.fn();
    const user = userEvent.setup();
    render(<RefreshControl refresh={refreshController({ setInterval })} />);

    await user.tab();
    expect(document.activeElement).toBe(
      screen.getByRole('button', { name: REFRESH }),
    );
    await user.tab();
    expect(document.activeElement).toBe(
      screen.getByRole('button', { name: AUTO }),
    );

    await user.keyboard('{Enter}');
    const menu = await screen.findByRole('menu');
    await user.keyboard('{ArrowDown}');
    // Roving focus lands on the options themselves, so which one is chosen
    // is the user's; that one press chooses is what this holds.
    const options = within(menu).getAllByRole('menuitemradio');
    expect(options).toContain(document.activeElement);

    await user.keyboard('{Enter}');
    expect(setInterval).toHaveBeenCalledTimes(1);
  });
});

/**
 * `refresh` lives on `ViewConfigBase`, so all three kinds need a way in. They
 * do not share a surface — only Record has a result toolbar — so each entry
 * is held to being there and to editing the right view's config.
 */
describe('every workbench offers the interval', () => {
  it('the record toolbar, in the freshness group', async () => {
    const engine = engineWith({});
    const user = userEvent.setup();
    render(
      <ViewSurface>
        <DataWorkbench
          engine={engine}
          definitionId="orders"
          instanceId="orders-1"
        />
      </ViewSurface>,
    );
    await screen.findByRole('table');

    const menu = await openIntervals(user);
    await user.click(within(menu).getByRole('menuitemradio', { name: '30s' }));

    await waitFor(() =>
      expect(
        screen
          .getByRole('button', { name: new RegExp(REFRESH) })
          .getAttribute('aria-description'),
      ).toBe(
        defaultMessages['label.refresh.on'].replace('{interval}', every(30)),
      ),
    );
    const runtime = engine.openRuntimes()[0];
    expect(runtime.getSnapshot().draft.refresh).toEqual({ interval: 30 });
    // An edit like any other: what is now unsaved is the view's own config.
    expect(runtime.getSnapshot().dirty).toBe(true);
  });

  it('the analysis title bar, which has no toolbar to hold it', async () => {
    const analysis: ViewInstance = {
      ...orders,
      id: 'analysis-1',
      config: analysisConfig(),
    };
    const engine = engineWith({ instances: [analysis] });
    const user = userEvent.setup();
    render(
      <ViewSurface>
        <DataWorkbench
          engine={engine}
          definitionId="orders"
          instanceId="analysis-1"
          kinds={['analysis']}
        />
      </ViewSurface>,
    );
    await screen.findByRole('table');

    const menu = await openIntervals(user);
    await user.click(
      within(menu).getByRole('menuitemradio', { name: every(60) }),
    );

    await waitFor(() =>
      expect(engine.openRuntimes()[0].getSnapshot().draft.refresh).toEqual({
        interval: 60,
      }),
    );
  });

  /**
   * A dashboard holds one timer for the whole board and ignores what a
   * referenced view saved for itself, so its menu has to say which interval
   * it is setting rather than implying it reaches into the panels.
   */
  it('the dashboard title bar, saying whose timer it is', async () => {
    const board: ViewInstance = {
      id: 'board-1',
      definitionId: 'overview',
      title: 'Overview',
      scope: 'shared',
      revision: '1',
      config: dashboardConfig(),
    };
    const engine = engineWith({ instances: [board] });
    const user = userEvent.setup();
    const { container } = render(
      <ViewSurface>
        <DashboardWorkbench
          engine={engine}
          definitionId="overview"
          instanceId="board-1"
        />
      </ViewSurface>,
    );
    await screen.findByRole('button', { name: AUTO });

    const menu = await openIntervals(user);
    expect(
      within(menu).getByText(defaultMessages['label.refresh.panels']),
    ).toBeTruthy();
    await user.click(
      within(menu).getByRole('menuitemradio', { name: every(300) }),
    );

    await waitFor(() =>
      expect(engine.openRuntimes()[0].getSnapshot().draft.refresh).toEqual({
        interval: 300,
      }),
    );
    // And it counts down to the board's own timer, which is armed once every
    // panel has answered: the same control, reading the one runtime that
    // times this screen. (A second may pass while the panels land, so the
    // reading is the top of the count or the one below it.)
    await waitFor(() =>
      expect([left(300), left(299)]).toContain(reading(container)),
    );
  });

  /**
   * A dashboard's own `query` never leaves `idle` — it runs nothing itself —
   * so asking it whether something is out gets `false` while every panel on
   * the board is mid-request, and the press would silently replace all of
   * them. The answer has to come from the panels, the way the dashboard's
   * own timer already asks them before it fires.
   */
  it('stops the dashboard press while a panel is still querying', async () => {
    const board: ViewInstance = {
      id: 'board-2',
      definitionId: 'overview',
      title: 'Overview',
      // Personal, because the panel it holds is: a shared dashboard may not
      // reference a personal view (`dashboard.panel.scope-too-narrow`), and
      // a refused panel never queries, which is not what this is about.
      scope: 'personal',
      revision: '1',
      config: dashboardConfig({
        panels: [
          {
            id: 'orders',
            kind: 'view',
            instanceId: 'orders-1',
            bindings: [],
            layout: { x: 0, y: 0, w: 6, h: 4 },
          },
        ],
      }),
    };
    // A source that never answers, so the panel's request stays in flight.
    const outstanding = deferred<PagedList<RecordData>>();
    const engine = engineWith({
      instances: [orders, board],
      source: testSource({ paged: () => outstanding.promise }),
    });
    render(
      <ViewSurface>
        <DashboardWorkbench
          engine={engine}
          definitionId="overview"
          instanceId="board-2"
        />
      </ViewSurface>,
    );

    const press = await screen.findByRole('button', {
      name: new RegExp(REFRESH),
    });
    await waitFor(() => expect(press.hasAttribute('disabled')).toBe(true));

    // And it comes back the moment the panel has its answer.
    await act(async () => {
      outstanding.resolve({ total: 0, list: [] });
      await Promise.resolve();
    });
    await waitFor(() => expect(press.hasAttribute('disabled')).toBe(false));
  });
});
