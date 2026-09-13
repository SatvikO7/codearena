import { describe, expect, it } from 'vitest';
import {
  applyStreamEvent,
  initialStreamState,
  shouldKeepWatching,
} from './submissionStream';
import type { StreamState, SubmissionStatusEvent } from './submissionStream';
import type { SubmissionStatus } from '../types/submission';

/**
 * The convergence rules, pinned.
 *
 * <p>This is where "at-least-once delivery" is actually made safe, so these tests are the
 * specification for it. Every scenario below is one the transport can genuinely produce:
 * Redis Pub/Sub keeps nothing, so events can arrive twice, out of order, or not at all.
 */
describe('applyStreamEvent', () => {
  const at = (iso: string, status: SubmissionStatus): SubmissionStatusEvent => ({
    id: 'submission-1',
    status,
    terminal: !['QUEUED', 'RUNNING'].includes(status),
    updatedAt: iso,
  });

  const QUEUED = at('2026-01-01T10:00:00.000Z', 'QUEUED');
  const RUNNING = at('2026-01-01T10:00:01.000Z', 'RUNNING');
  const ACCEPTED = at('2026-01-01T10:00:02.000Z', 'ACCEPTED');

  it('applies the first event it sees', () => {
    const state = applyStreamEvent(initialStreamState, QUEUED);

    expect(state.current?.status).toBe('QUEUED');
    expect(state.settled).toBe(false);
  });

  it('advances through the normal lifecycle', () => {
    let state = applyStreamEvent(initialStreamState, QUEUED);
    state = applyStreamEvent(state, RUNNING);
    state = applyStreamEvent(state, ACCEPTED);

    expect(state.current?.status).toBe('ACCEPTED');
    expect(state.settled).toBe(true);
  });

  /**
   * The case the brief explicitly forbids: a client must never be shown RUNNING after it
   * has already seen ACCEPTED. Under at-least-once delivery a straggling RUNNING frame
   * genuinely can arrive last.
   */
  it('never goes backwards from a verdict to an in-progress state', () => {
    const settled = applyStreamEvent(
      applyStreamEvent(initialStreamState, QUEUED),
      ACCEPTED,
    );

    const afterStraggler = applyStreamEvent(settled, RUNNING);

    expect(afterStraggler.current?.status).toBe('ACCEPTED');
    expect(afterStraggler).toBe(settled);
  });

  it('ignores a second verdict once one has been recorded', () => {
    const settled = applyStreamEvent(initialStreamState, ACCEPTED);

    const after = applyStreamEvent(settled, at('2026-01-01T10:00:09.000Z', 'WRONG_ANSWER'));

    expect(after.current?.status)
      .toBe('ACCEPTED');
  });

  it('discards a duplicate of the applied event', () => {
    const state = applyStreamEvent(initialStreamState, RUNNING);

    const after = applyStreamEvent(state, { ...RUNNING });

    expect(after).toBe(state);
  });

  it('discards an event older than the one already applied', () => {
    const state = applyStreamEvent(initialStreamState, RUNNING);

    const after = applyStreamEvent(state, QUEUED);

    expect(after.current?.status).toBe('RUNNING');
    expect(after).toBe(state);
  });

  /** Out-of-order arrival still converges on the newest state, whatever the sequence. */
  it('converges on the newest event regardless of arrival order', () => {
    const orders: SubmissionStatusEvent[][] = [
      [QUEUED, RUNNING, ACCEPTED],
      [RUNNING, QUEUED, ACCEPTED],
      [QUEUED, ACCEPTED, RUNNING],
      [ACCEPTED, RUNNING, QUEUED],
      [RUNNING, ACCEPTED, QUEUED, RUNNING, QUEUED],
    ];

    for (const order of orders) {
      const state = order.reduce<StreamState>(applyStreamEvent, initialStreamState);

      expect(state.current?.status, `order: ${order.map((e) => e.status).join(' → ')}`)
        .toBe('ACCEPTED');
      expect(state.settled).toBe(true);
    }
  });

  /** A missing event is repaired by the snapshot, not by a rule; it must simply not break. */
  it('accepts a verdict even when the intermediate event was never delivered', () => {
    const state = applyStreamEvent(applyStreamEvent(initialStreamState, QUEUED), ACCEPTED);

    expect(state.current?.status).toBe('ACCEPTED');
    expect(state.settled).toBe(true);
  });

  /** A malformed timestamp must not be able to replace good state. */
  it('ignores an event with an unparseable timestamp', () => {
    const state = applyStreamEvent(initialStreamState, RUNNING);

    const after = applyStreamEvent(state, { ...ACCEPTED, updatedAt: 'not-a-date' });

    expect(after.current?.status).toBe('RUNNING');
    expect(after).toBe(state);
  });

  it('treats every terminal status as settling', () => {
    const terminals: SubmissionStatus[] = [
      'ACCEPTED',
      'WRONG_ANSWER',
      'COMPILATION_ERROR',
      'RUNTIME_ERROR',
      'TIME_LIMIT_EXCEEDED',
      'MEMORY_LIMIT_EXCEEDED',
      'SYSTEM_ERROR',
    ];

    for (const status of terminals) {
      const state = applyStreamEvent(initialStreamState, at('2026-01-01T11:00:00.000Z', status));

      expect(state.settled, status).toBe(true);
      expect(shouldKeepWatching(state), status).toBe(false);
    }
  });

  it('keeps watching while the submission is still in flight', () => {
    expect(shouldKeepWatching(applyStreamEvent(initialStreamState, QUEUED))).toBe(true);
    expect(shouldKeepWatching(applyStreamEvent(initialStreamState, RUNNING))).toBe(true);
  });
});
