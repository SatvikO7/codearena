/**
 * The audit vocabulary, mirroring the server's.
 *
 * <p>Actions are typed as a plain string rather than a union: the server publishes the list
 * at `/api/admin/audit-events/actions`, and a hard-coded union here would silently fall out
 * of date the first time a phase adds an action — leaving a filter that cannot select the
 * events somebody is looking for.
 */

export type ActorType = 'USER' | 'ADMIN' | 'SYSTEM' | 'ANONYMOUS';

export type AuditOutcome = 'SUCCESS' | 'FAILURE' | 'DENIED';

export const AUDIT_OUTCOMES: AuditOutcome[] = ['SUCCESS', 'FAILURE', 'DENIED'];

export const ACTOR_TYPES: ActorType[] = ['USER', 'ADMIN', 'SYSTEM', 'ANONYMOUS'];

/** The entity kinds the server records against. */
export const ENTITY_TYPES = ['USER', 'PROBLEM', 'CONTEST', 'CONTEST_PROBLEM', 'SUBMISSION'];

export interface AuditEvent {
  id: string;
  /** Written by the database, never by the application or a client. */
  occurredAt: string;
  actorUserId: string | null;
  actorUsername: string | null;
  actorType: ActorType;
  action: string;
  outcome: AuditOutcome;
  entityType: string | null;
  entityId: string | null;
  /** Correlates the event with the server log lines from the same request. */
  requestId: string | null;
  /** Curated and bounded by the server; never a request body, never a secret. */
  metadata: Record<string, unknown>;
}

export interface AuditQuery {
  actorUserId?: string;
  action?: string;
  outcome?: AuditOutcome | '';
  actorType?: ActorType | '';
  entityType?: string;
  entityId?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

/** Whether a dependency answered, and how quickly. */
export interface DependencyStatus {
  up: boolean;
  responseMs: number;
}

/**
 * Null rather than zero when the store could not be reached: "unknown" is not "none".
 *
 * `oldestPendingAgeSeconds` is the field that gives the depths meaning. A deep queue that is
 * draining has a young oldest item; a queue nobody is consuming has one that grows second by
 * second. Depth alone cannot tell a busy evening from a stopped worker pool.
 */
export interface QueueDepth {
  pending: number | null;
  processing: number | null;
  oldestPendingAgeSeconds: number | null;
  retrying: number;
  systemErrorsLastHour: number;
}

/**
 * One judge worker, as it last reported itself.
 *
 * `healthy` false means the record exists and nobody has touched it — the process may well
 * be running, which is precisely the case a container health check cannot see.
 */
export interface WorkerStatus {
  id: string;
  version: string;
  startedAt: string | null;
  lastSeenAt: string | null;
  healthy: boolean;
  draining: boolean;
  concurrency: number;
  activeJobs: number;
  judged: number;
  infrastructureFailures: number;
}

export interface AuditHealth {
  readable: boolean;
  eventsLastHour: number;
}

/**
 * `state` is an operational judgement, not a health probe.
 *
 * READY, DEGRADED or UNAVAILABLE. DEGRADED is the interesting one: everything answers, every
 * request succeeds, and no submission is being judged.
 */
export interface SystemStatus {
  version: string;
  serverTime: string;
  uptimeSeconds: number;
  state: 'READY' | 'DEGRADED' | 'UNAVAILABLE';
  database: DependencyStatus;
  redis: DependencyStatus;
  queue: QueueDepth;
  workers: WorkerStatus[];
  audit: AuditHealth;
  auditEventCount: number;
}
