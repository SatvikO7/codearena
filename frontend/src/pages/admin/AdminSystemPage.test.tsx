import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AdminSystemPage } from './AdminSystemPage';
import * as auditService from '../../services/auditService';
import type { SystemStatus, WorkerStatus } from '../../types/audit';

const SERVER_TIME = '2026-09-14T12:00:00.000Z';

function worker(overrides: Partial<WorkerStatus> = {}): WorkerStatus {
  return {
    id: 'worker-1',
    version: '1.0.0',
    startedAt: '2026-09-14T11:00:00.000Z',
    lastSeenAt: '2026-09-14T11:59:55.000Z',
    healthy: true,
    draining: false,
    concurrency: 2,
    activeJobs: 1,
    judged: 120,
    infrastructureFailures: 0,
    ...overrides,
  };
}

function status(overrides: Partial<SystemStatus> = {}): SystemStatus {
  return {
    version: '1.0.0',
    serverTime: SERVER_TIME,
    uptimeSeconds: 3661,
    state: 'READY',
    database: { up: true, responseMs: 2 },
    redis: { up: true, responseMs: 1 },
    queue: {
      pending: 0,
      processing: 0,
      oldestPendingAgeSeconds: null,
      retrying: 0,
      systemErrorsLastHour: 0,
    },
    workers: [worker()],
    audit: { readable: true, eventsLastHour: 7 },
    auditEventCount: 1234,
    ...overrides,
  };
}

/**
 * The operational dashboard.
 *
 * <p>What is being tested is the reading rather than the layout: that a stale worker is
 * shown as stale rather than merely absent, that a deliberate shutdown does not look like a
 * fault, and that "we could not ask" is never rendered as "none". Those are the three ways a
 * status page misleads somebody at three in the morning.
 */
describe('AdminSystemPage', () => {
  beforeEach(() => {
    vi.spyOn(auditService, 'getSystemStatus').mockResolvedValue(status());
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  async function renderWith(value: SystemStatus) {
    vi.mocked(auditService.getSystemStatus).mockResolvedValue(value);
    render(<AdminSystemPage />);
    await waitFor(() => expect(screen.getByText('System status')).toBeInTheDocument());
  }

  it('leads with the verdict rather than with numbers', async () => {
    await renderWith(status());

    expect(screen.getByText('Ready')).toBeInTheDocument();
  });

  /**
   * The state this whole phase exists to make visible: every dependency answers, every
   * request succeeds, and nothing is being judged.
   */
  it('announces a degraded system even though nothing is down', async () => {
    await renderWith(
      status({
        state: 'DEGRADED',
        queue: {
          pending: 42,
          processing: 0,
          oldestPendingAgeSeconds: 900,
          retrying: 0,
          systemErrorsLastHour: 0,
        },
        workers: [],
      }),
    );

    expect(screen.getByText('Degraded')).toBeInTheDocument();
    expect(screen.getByText(/No workers are registered/)).toBeInTheDocument();
  });

  /**
   * A worker whose process is up and whose consumer has stopped. This is the exact case a
   * container health check reports as green.
   */
  it('shows a worker that has stopped reporting as stale', async () => {
    await renderWith(status({ workers: [worker({ healthy: false, lastSeenAt: '2026-09-14T11:50:00.000Z' })] }));

    expect(screen.getByText('Stale')).toBeInTheDocument();
    expect(screen.getByText('10m 0s ago')).toBeInTheDocument();
  });

  /** A planned rollout must not be coloured like an incident. */
  it('distinguishes a worker that is draining from one that has failed', async () => {
    await renderWith(status({ workers: [worker({ draining: true })] }));

    expect(screen.getByText('Draining')).toBeInTheDocument();
    expect(screen.queryByText('Stale')).not.toBeInTheDocument();
  });

  /**
   * Null means the store could not be asked. Rendering that as 0 would be a claim — "nothing
   * is queued" — made at the exact moment nobody knows whether anything is queued.
   */
  it('says unknown rather than zero when a depth could not be read', async () => {
    await renderWith(
      status({
        queue: {
          pending: null,
          processing: null,
          oldestPendingAgeSeconds: null,
          retrying: 0,
          systemErrorsLastHour: 0,
        },
      }),
    );

    expect(screen.getAllByText('unknown')).toHaveLength(2);
  });

  it('reports the oldest wait, which is what gives a depth meaning', async () => {
    await renderWith(
      status({
        queue: {
          pending: 12,
          processing: 2,
          oldestPendingAgeSeconds: 754,
          retrying: 1,
          systemErrorsLastHour: 3,
        },
      }),
    );

    expect(screen.getByText('12m 34s')).toBeInTheDocument();
  });

  /**
   * The worker's age is measured against the server's clock. A viewer whose own clock is
   * out by minutes must not see every worker as correspondingly staler than it is.
   */
  it('measures worker age against the server clock, not the browser', async () => {
    const browserIsAnHourFast = vi
      .spyOn(Date, 'now')
      .mockReturnValue(new Date('2026-09-14T13:00:00.000Z').getTime());

    await renderWith(status({ workers: [worker({ lastSeenAt: '2026-09-14T11:59:55.000Z' })] }));

    expect(screen.getByText('5s ago')).toBeInTheDocument();
    browserIsAnHourFast.mockRestore();
  });

  it('reports an unreadable audit log rather than an event count of zero', async () => {
    await renderWith(status({ audit: { readable: false, eventsLastHour: 0 } }));

    expect(screen.getByText('unreadable')).toBeInTheDocument();
  });
});
