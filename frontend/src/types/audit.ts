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

/** Null rather than zero when Redis could not be reached: "unknown" is not "none". */
export interface QueueDepth {
  pending: number | null;
  processing: number | null;
}

export interface SystemStatus {
  version: string;
  serverTime: string;
  database: DependencyStatus;
  redis: DependencyStatus;
  queue: QueueDepth;
  auditEventCount: number;
}
