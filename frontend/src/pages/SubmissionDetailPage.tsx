import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { getSubmission } from '../services/submissionService';
import { apiErrorCode, describeApiError } from '../services/apiClient';
import { useSubmissionStream } from '../hooks/useSubmissionStream';
import { SubmissionVerdict } from '../components/SubmissionVerdict';
import { isTerminal } from '../types/submission';
import type { SubmissionDetail } from '../types/submission';

export function SubmissionDetailPage() {
  const { submissionId } = useParams<{ submissionId: string }>();
  if (!submissionId) {
    return null;
  }
  return <SubmissionDetailView key={submissionId} submissionId={submissionId} />;
}

/**
 * One submission, in full.
 *
 * Loads the authoritative detail once — it is the only endpoint carrying the source code and
 * the per-test results — and then watches the live stream for status changes. A submission
 * opened while still QUEUED therefore updates in place rather than showing a stale status
 * until somebody reloads.
 *
 * When the watch settles, the detail is read once more: the stream carries status but not
 * test results, so that final read is what fills in the per-test strip.
 */
function SubmissionDetailView({ submissionId }: { submissionId: string }) {
  const [detail, setDetail] = useState<SubmissionDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notFound, setNotFound] = useState(false);

  const watch = useSubmissionStream(submissionId);

  const load = useCallback(
    async (signal?: AbortSignal) => {
      try {
        const fetched = await getSubmission(submissionId, signal);
        if (signal?.aborted) return;
        setDetail(fetched);
        setError(null);
      } catch (caught) {
        if (signal?.aborted) return;
        if (apiErrorCode(caught) === 'SUBMISSION_NOT_FOUND') {
          setNotFound(true);
        } else {
          setError(describeApiError(caught));
        }
      }
    },
    [submissionId],
  );

  useEffect(() => {
    const controller = new AbortController();
    // Synchronising with the server; the state updates happen after the request resolves.
    // oxlint-disable-next-line set-state-in-effect
    void load(controller.signal);
    return () => controller.abort();
  }, [load]);

  // Re-read once the verdict lands, to pick up the test results the stream does not carry.
  useEffect(() => {
    if (!watch.settled) {
      return;
    }
    const controller = new AbortController();
    // oxlint-disable-next-line set-state-in-effect
    void load(controller.signal);
    return () => controller.abort();
  }, [watch.settled, load]);

  if (notFound) {
    return (
      <div className="page">
        <section className="panel empty-state">
          <h1>Submission not found</h1>
          <p>It may not exist, or it may belong to someone else.</p>
          <Link className="button" to="/submissions">
            Back to my submissions
          </Link>
        </section>
      </div>
    );
  }

  if (error && !detail) {
    return (
      <div className="page">
        <div className="panel" role="alert">
          <p className="status status--error">Could not load the submission</p>
          <p className="status-detail">{error}</p>
        </div>
      </div>
    );
  }

  if (!detail) {
    return (
      <div className="page">
        <p className="status status--pending" role="status">
          Loading submission&hellip;
        </p>
      </div>
    );
  }

  // The live status is fresher than the fetched detail whenever both are present.
  const live = watch.status;
  const status = live?.status ?? detail.status;

  return (
    <div className="page">
      <section className="hero">
        <h1>Submission</h1>
        <p className="meta-row">
          <Link to={`/problems/${detail.problemSlug}`}>{detail.problemTitle}</Link>
          <span>{detail.language}</span>
          <span>{new Date(detail.createdAt).toLocaleString()}</span>
        </p>
        <p className="row-sub">{detail.id}</p>
      </section>

      <SubmissionVerdict
        status={status}
        testsTotal={live?.testsTotal ?? detail.testsTotal}
        testsPassed={live?.testsPassed ?? detail.testsPassed}
        failedTestIndex={live?.failedTestIndex ?? detail.failedTestIndex}
        runtimeMs={live?.runtimeMs ?? detail.runtimeMs}
        errorMessage={live?.errorMessage ?? detail.errorMessage}
        testResults={detail.testResults}
        attempts={detail.attempts}
        timedOut={watch.timedOut}
      />

      {!isTerminal(status) && watch.transport === 'polling' && (
        <p className="field-hint">Live updates unavailable; checking periodically.</p>
      )}

      <section className="panel">
        <h2>Your code</h2>
        {/* Read-only by construction: a submission is historical data, and no endpoint
            accepts an edit to one. */}
        <pre className="code-block">{detail.sourceCode}</pre>
      </section>

      <p className="form-aside">
        <Link to={`/problems/${detail.problemSlug}/solve`}>Try again</Link>
        {' · '}
        <Link to="/submissions">Back to my submissions</Link>
      </p>
    </div>
  );
}
