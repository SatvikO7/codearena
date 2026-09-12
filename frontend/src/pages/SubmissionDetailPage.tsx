import { Link, useParams } from 'react-router-dom';
import { useSubmissionPolling } from '../hooks/useSubmissionPolling';
import { SubmissionVerdict } from '../components/SubmissionVerdict';

/**
 * One submission.
 *
 * Reuses the polling hook so a submission opened while still QUEUED updates in place
 * rather than showing a stale status until the page is reloaded.
 */
export function SubmissionDetailPage() {
  const { submissionId } = useParams<{ submissionId: string }>();
  const { submission, error, timedOut } = useSubmissionPolling(submissionId ?? null);

  if (error && !submission) {
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

  if (!submission) {
    return (
      <div className="page">
        <p className="status status--pending" role="status">
          Loading submission&hellip;
        </p>
      </div>
    );
  }

  return (
    <div className="page">
      <section className="hero">
        <h1>Submission</h1>
        <p className="meta-row">
          <Link to={`/problems/${submission.problemSlug}`}>{submission.problemTitle}</Link>
          <span>{new Date(submission.createdAt).toLocaleString()}</span>
        </p>
      </section>

      <SubmissionVerdict submission={submission} timedOut={timedOut} />

      <section className="panel">
        <h2>Your code</h2>
        <pre className="code-block">{submission.sourceCode}</pre>
      </section>

      <p className="form-aside">
        <Link to="/submissions">&larr; Back to my submissions</Link>
      </p>
    </div>
  );
}
