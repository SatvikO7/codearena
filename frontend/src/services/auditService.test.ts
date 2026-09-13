import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { searchAuditEvents, listAuditActions, getSystemStatus } from './auditService';
import { apiClient } from './apiClient';

/**
 * What the audit client actually sends.
 *
 * <p>The interesting behaviour is the filtering of empty values. The server rejects
 * `outcome=` as an invalid enum, so a page with a cleared dropdown would break outright if
 * the client forwarded it — and `action=` would narrow nothing while appearing to narrow
 * something, which is worse than an error.
 */
describe('auditService', () => {
  beforeEach(() => {
    vi.spyOn(apiClient, 'get').mockResolvedValue({ data: { items: [] } } as never);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  function paramsOf(): Record<string, unknown> {
    const call = vi.mocked(apiClient.get).mock.calls[0];
    return (call[1] as { params: Record<string, unknown> }).params;
  }

  it('sends only the filters that were chosen', async () => {
    await searchAuditEvents({
      action: 'CONTEST_CANCEL',
      outcome: '',
      actorType: '',
      entityId: '',
      page: 2,
      size: 50,
    });

    const params = paramsOf();
    expect(params).toEqual({ action: 'CONTEST_CANCEL', page: 2, size: 50 });
  });

  /** An empty string is not a filter; forwarding it is how a cleared dropdown becomes a 400. */
  it('drops empty, null and undefined filters', async () => {
    await searchAuditEvents({
      action: undefined,
      outcome: '',
      entityType: '',
      entityId: undefined,
    });

    expect(paramsOf()).toEqual({});
  });

  it('keeps a page number of zero, which is a real value rather than an absent one', async () => {
    await searchAuditEvents({ page: 0, size: 50 });

    expect(paramsOf()).toEqual({ page: 0, size: 50 });
  });

  it('passes a time range through untouched', async () => {
    await searchAuditEvents({ from: '2026-01-01T00:00:00.000Z', to: '2026-02-01T00:00:00.000Z' });

    expect(paramsOf()).toEqual({
      from: '2026-01-01T00:00:00.000Z',
      to: '2026-02-01T00:00:00.000Z',
    });
  });

  it('requests the audit endpoint', async () => {
    await searchAuditEvents({});

    expect(apiClient.get).toHaveBeenCalledWith('/api/admin/audit-events', expect.anything());
  });

  /**
   * Fetched rather than hard-coded: a list baked into the frontend goes stale the first time
   * a phase adds an action, and the symptom is a filter that cannot find what somebody wants.
   */
  it('fetches the action vocabulary from the server', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: ['AUTH_LOGIN'] } as never);

    await expect(listAuditActions()).resolves.toEqual(['AUTH_LOGIN']);
    expect(apiClient.get).toHaveBeenCalledWith('/api/admin/audit-events/actions', expect.anything());
  });

  it('requests the curated status endpoint rather than actuator', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: { version: 'test' } } as never);

    await getSystemStatus();

    expect(apiClient.get).toHaveBeenCalledWith('/api/admin/system/status', expect.anything());
  });
});
