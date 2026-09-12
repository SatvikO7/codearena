import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { getProblem } from '../services/problemService';
import { apiErrorCode, describeApiError } from '../services/apiClient';
import { DifficultyBadge } from '../components/DifficultyBadge';
import type { ProblemDetail } from '../types/problem';

type State =
  | { status: 'loading' }
  | { status: 'ready'; problem: ProblemDetail }
  | { status: 'missing' }
  | { status: 'error'; message: string };

/**
 * Keys the view on the slug so navigating between problems remounts it.
 *
 * <p>That is what lets the view below start in its loading state and never reset state
 * from inside an effect: React discards the old component instead. Resetting by calling
 * setState synchronously in an effect would render the previous problem's content for a
 * frame before the spinner appeared.
 */
export function ProblemDetailPage() {
  const { slug } = useParams<{ slug: string }>();

  if (!slug) {
    return null;
  }
  return <ProblemDetailView key={slug} slug={slug} />;
}

function ProblemDetailView({ slug }: { slug: string }) {
  const [state, setState] = useState<State>({ status: 'loading' });

  useEffect(() => {
    const controller = new AbortController();

    getProblem(slug, controller.signal)
      .then((problem) => setState({ status: 'ready', problem }))
      .catch((caught: unknown) => {
        if (controller.signal.aborted) return;
        // A draft or archived problem answers 404, exactly as a non-existent one does.
        // The client cannot tell them apart, which is the point.
        if (apiErrorCode(caught) === 'PROBLEM_NOT_FOUND') {
          setState({ status: 'missing' });
        } else {
          setState({ status: 'error', message: describeApiError(caught) });
        }
      });

    return () => controller.abort();
  }, [slug]);

  if (state.status === 'loading') {
    return (
      <div className="page">
        <p className="status status--pending" role="status">
          Loading problem&hellip;
        </p>
      </div>
    );
  }

  if (state.status === 'missing') {
    return (
      <div className="page">
        <section className="panel empty-state">
          <h1>Problem not found</h1>
          <p>No published problem has that address.</p>
          <Link className="button" to="/problems">
            Back to problems
          </Link>
        </section>
      </div>
    );
  }

  if (state.status === 'error') {
    return (
      <div className="page">
        <div className="panel" role="alert">
          <p className="status status--error">Could not load the problem</p>
          <p className="status-detail">{state.message}</p>
        </div>
      </div>
    );
  }

  const { problem } = state;

  return (
    <article className="page">
      <section className="hero">
        <div className="title-row">
          <h1>{problem.title}</h1>
          <DifficultyBadge difficulty={problem.difficulty} />
        </div>
        <p className="meta-row">
          <span>Time limit: {problem.timeLimitMs} ms</span>
          <span>Memory limit: {problem.memoryLimitMb} MB</span>
        </p>
        {problem.tags.length > 0 && (
          <p className="tag-cell">
            {problem.tags.map((tag) => (
              <span key={tag} className="tag">
                {tag.replace(/_/g, ' ')}
              </span>
            ))}
          </p>
        )}
      </section>

      <section className="panel prose">
        <h2>Statement</h2>
        <p>{problem.statement}</p>
      </section>

      <div className="split">
        <section className="panel prose">
          <h2>Input</h2>
          <p>{problem.inputFormat}</p>
        </section>
        <section className="panel prose">
          <h2>Output</h2>
          <p>{problem.outputFormat}</p>
        </section>
      </div>

      <section className="panel prose">
        <h2>Constraints</h2>
        <p>{problem.constraints}</p>
      </section>

      <section className="panel">
        <h2>Examples</h2>
        {problem.examples.map((example) => (
          <div className="example" key={example.position}>
            <h3>Example {example.position + 1}</h3>
            <div className="split">
              <div>
                <h4>Input</h4>
                <pre>{example.input}</pre>
              </div>
              <div>
                <h4>Output</h4>
                <pre>{example.output}</pre>
              </div>
            </div>
            {example.explanation && <p className="example-note">{example.explanation}</p>}
          </div>
        ))}
      </section>

      {problem.explanation && (
        <section className="panel prose">
          <h2>Notes</h2>
          <p>{problem.explanation}</p>
        </section>
      )}

      <p className="form-aside solve-cta">
        <Link className="button" to={`/problems/${problem.slug}/solve`}>
          Solve this problem
        </Link>
        <Link to="/problems">&larr; Back to problems</Link>
      </p>
    </article>
  );
}
