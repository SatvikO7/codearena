import { STATUS_LABELS, isTerminal } from '../types/submission';
import type { SubmissionDetail, SubmissionStatus } from '../types/submission';

/**
 * Groups verdicts for styling. Accepted is the only success; everything else is either
 * still happening or a failure, and a judge error is neither the user's fault nor a
 * verdict on their code.
 */
function toneOf(status: SubmissionStatus): 'ok' | 'error' | 'pending' | 'neutral' {
  if (status === 'ACCEPTED') return 'ok';
  if (status === 'QUEUED' || status === 'RUNNING') return 'pending';
  if (status === 'SYSTEM_ERROR') return 'neutral';
  return 'error';
}

export function StatusPill({ status }: { status: SubmissionStatus }) {
  return (
    <span className={`pill pill--${toneOf(status)}`}>
      {STATUS_LABELS[status]}
      {!isTerminal(status) && <span className="pill-spinner" aria-hidden="true" />}
    </span>
  );
}

interface Props {
  submission: SubmissionDetail;
  /** Set when the client stopped waiting, not when the judge failed. */
  timedOut?: boolean;
}

/** The verdict panel shown under the editor after a submission. */
export function SubmissionVerdict({ submission, timedOut }: Props) {
  const tone = toneOf(submission.status);

  return (
    <section className="panel verdict" aria-live="polite">
      <div className="title-row">
        <h2>Result</h2>
        <StatusPill status={submission.status} />
      </div>

      {submission.status === 'QUEUED' && (
        <p className="status-detail">Waiting for a judge worker to pick this up…</p>
      )}
      {submission.status === 'RUNNING' && (
        <p className="status-detail">Compiling and running your program against the test cases…</p>
      )}

      {timedOut && (
        <p className="status status--error" role="alert">
          Still waiting after three minutes. The submission is safe — reload this page to
          check again.
        </p>
      )}

      {isTerminal(submission.status) && (
        <dl className="detail-grid">
          {submission.testsTotal !== undefined && submission.testsTotal !== null && (
            <div>
              <dt>Tests passed</dt>
              <dd>
                {submission.testsPassed ?? 0} / {submission.testsTotal}
              </dd>
            </div>
          )}
          {submission.failedTestIndex !== undefined && submission.failedTestIndex !== null && (
            <div>
              <dt>First failure</dt>
              {/* The number only. What the test contains is never disclosed. */}
              <dd>Test {submission.failedTestIndex + 1}</dd>
            </div>
          )}
          {submission.runtimeMs !== undefined && submission.runtimeMs !== null && (
            <div>
              <dt>Runtime</dt>
              <dd>{submission.runtimeMs} ms</dd>
            </div>
          )}
          {submission.memoryKb !== undefined && submission.memoryKb !== null && (
            <div>
              <dt>Memory</dt>
              <dd>{Math.round(submission.memoryKb / 1024)} MB</dd>
            </div>
          )}
          <div>
            <dt>Language</dt>
            <dd>{submission.language}</dd>
          </div>
        </dl>
      )}

      {submission.errorMessage && (
        <div className={`message-block message-block--${tone}`}>
          <h3>Details</h3>
          {/* Pre-formatted: compiler diagnostics are column-aligned and unreadable reflowed. */}
          <pre>{submission.errorMessage}</pre>
        </div>
      )}
    </section>
  );
}
