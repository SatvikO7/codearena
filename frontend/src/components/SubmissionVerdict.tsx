import { STATUS_EXPLANATIONS, STATUS_GLYPHS, STATUS_LABELS, isTerminal } from '../types/submission';
import type { SubmissionStatus, TestResult } from '../types/submission';

/**
 * Groups verdicts for styling.
 *
 * <p>SYSTEM_ERROR is deliberately neither "ok" nor "error": it is not a judgement on the
 * code at all, and colouring it like a wrong answer would tell the user their program was
 * at fault when the judge was.
 */
function toneOf(status: SubmissionStatus): 'ok' | 'error' | 'pending' | 'neutral' {
  if (status === 'ACCEPTED') return 'ok';
  if (status === 'QUEUED' || status === 'RUNNING') return 'pending';
  if (status === 'SYSTEM_ERROR') return 'neutral';
  return 'error';
}

/**
 * A status pill.
 *
 * <p>Carries a glyph and a word as well as a colour, so it is legible to someone who cannot
 * distinguish the green from the red. The glyph is {@code aria-hidden} because the adjacent
 * label already says the same thing, and a screen reader announcing "check mark accepted"
 * is noise.
 */
export function StatusPill({ status }: { status: SubmissionStatus }) {
  return (
    <span className={`pill pill--${toneOf(status)}`}>
      <span className="pill-glyph" aria-hidden="true">
        {STATUS_GLYPHS[status]}
      </span>
      {STATUS_LABELS[status]}
      {!isTerminal(status) && <span className="pill-spinner" aria-hidden="true" />}
    </span>
  );
}

/** The per-test strip. Shows which tests passed, never what any of them contained. */
function TestResultList({ results }: { results: TestResult[] }) {
  return (
    <div className="test-results">
      <h3>Tests</h3>
      <ol className="test-strip">
        {results.map((result) => (
          <li
            key={result.position}
            className={`test-chip test-chip--${
              result.runtimeMs === undefined || result.runtimeMs === null
                ? 'skipped'
                : result.passed
                  ? 'pass'
                  : 'fail'
            }`}
            /* The tooltip names the test and its timing. It cannot name its contents:
               the API never sends them. */
            title={
              result.runtimeMs == null
                ? `Test ${result.position + 1}: not run`
                : `Test ${result.position + 1}: ${result.passed ? 'passed' : 'failed'} in ${result.runtimeMs} ms`
            }
          >
            <span aria-hidden="true">
              {result.runtimeMs == null ? '·' : result.passed ? '✓' : '✗'}
            </span>
            <span className="visually-hidden">
              Test {result.position + 1} {result.hidden ? '(hidden)' : '(example)'}:{' '}
              {result.runtimeMs == null ? 'not run' : result.passed ? 'passed' : 'failed'}
            </span>
            <span className="test-chip-number">{result.position + 1}</span>
          </li>
        ))}
      </ol>
      <p className="field-hint">
        Hidden tests show only whether they passed. Their inputs and expected outputs are
        never disclosed.
      </p>
    </div>
  );
}

interface Props {
  status: SubmissionStatus;
  testsTotal?: number;
  testsPassed?: number;
  failedTestIndex?: number;
  runtimeMs?: number;
  errorMessage?: string;
  testResults?: TestResult[];
  attempts?: number;
  /** Set when the client stopped waiting, not when the judge failed. */
  timedOut?: boolean;
}

/** The verdict panel. Used on both the solve page and the submission detail page. */
export function SubmissionVerdict({
  status,
  testsTotal,
  testsPassed,
  failedTestIndex,
  runtimeMs,
  errorMessage,
  testResults,
  attempts,
  timedOut,
}: Props) {
  const tone = toneOf(status);
  const terminal = isTerminal(status);

  return (
    <section className="panel verdict" aria-live="polite">
      <div className="title-row">
        <h2>Result</h2>
        <StatusPill status={status} />
      </div>

      <p className="status-detail">{STATUS_EXPLANATIONS[status]}</p>

      {timedOut && (
        <p className="form-error" role="alert">
          Still waiting after five minutes. The submission is safe and the judge is still
          working on it — reload this page to check again.
        </p>
      )}

      {terminal && (
        <dl className="detail-grid">
          {testsTotal != null && (
            <div>
              <dt>Tests passed</dt>
              <dd>
                {testsPassed ?? 0} / {testsTotal}
              </dd>
            </div>
          )}
          {failedTestIndex != null && (
            <div>
              <dt>First failure</dt>
              {/* The number only. What the test contains is never disclosed. */}
              <dd>Test {failedTestIndex + 1}</dd>
            </div>
          )}
          {runtimeMs != null && (
            <div>
              <dt>Slowest test</dt>
              <dd>{runtimeMs} ms</dd>
            </div>
          )}
          {attempts != null && attempts > 1 && (
            <div>
              <dt>Attempts</dt>
              {/* Surfaced only when it is greater than one, where it explains a delay. */}
              <dd>{attempts}</dd>
            </div>
          )}
        </dl>
      )}

      {testResults && testResults.length > 0 && <TestResultList results={testResults} />}

      {errorMessage && (
        <div className={`message-block message-block--${tone}`}>
          <h3>{status === 'COMPILATION_ERROR' ? 'Compiler output' : 'Details'}</h3>
          {/* Pre-formatted: compiler diagnostics are column-aligned and unreadable reflowed. */}
          <pre>{errorMessage}</pre>
        </div>
      )}
    </section>
  );
}
