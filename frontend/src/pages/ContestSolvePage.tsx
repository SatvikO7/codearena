import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { getProblem } from '../services/problemService';
import { getContest, submitToContest } from '../services/contestService';
import { formatDuration } from '../services/contestClock';
import { useContestClock } from '../hooks/useContestClock';
import { apiErrorCode, describeApiError } from '../services/apiClient';
import { useSubmissionStream } from '../hooks/useSubmissionStream';
import { SubmissionVerdict } from '../components/SubmissionVerdict';
import { ContestStatusPill } from '../components/ContestStatusPill';
import { LANGUAGES, STARTER_CODE } from '../types/submission';
import type { Language } from '../types/submission';
import type { ContestDetail, ContestProblemEntry } from '../types/contest';
import type { ProblemDetail } from '../types/problem';

/**
 * Solving a problem inside a contest.
 *
 * <h2>Why this is a separate page from {@code SolvePage}</h2>
 * Not for the layout — it is deliberately the same editor and the same verdict panel — but
 * because of where the submission goes. A contest page that posted to
 * {@code /api/problems/{id}/submissions} would quietly create a <em>practice</em> submission:
 * accepted, judged, and invisible to the standings. The contestant would see a green verdict
 * and no score, during a contest, which is the worst possible moment to discover a routing
 * mistake.
 *
 * <p>Keeping the contest path in its own page means the contest endpoint is the only one
 * this file knows how to call.
 */
export function ContestSolvePage() {
  const { contestId, problemId } = useParams<{ contestId: string; problemId: string }>();
  if (!contestId || !problemId) {
    return null;
  }
  return <ContestSolveView key={`${contestId}:${problemId}`} contestId={contestId} problemId={problemId} />;
}

function draftKey(contestId: string, problemId: string, language: Language) {
  return `codearena:contest-draft:${contestId}:${problemId}:${language}`;
}

/** Storage can throw in private browsing; a draft must never break the editor. */
function loadDraft(contestId: string, problemId: string, language: Language): string {
  try {
    return (
      window.localStorage.getItem(draftKey(contestId, problemId, language)) ??
      STARTER_CODE[language]
    );
  } catch {
    return STARTER_CODE[language];
  }
}

function ContestSolveView({ contestId, problemId }: { contestId: string; problemId: string }) {
  const [contest, setContest] = useState<ContestDetail | null>(null);
  const [problem, setProblem] = useState<ProblemDetail | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [notFound, setNotFound] = useState(false);

  const [language, setLanguage] = useState<Language>('PYTHON');
  const [source, setSource] = useState<string>(() => loadDraft(contestId, problemId, 'PYTHON'));

  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [submissionId, setSubmissionId] = useState<string | null>(null);

  const { status, pending, timedOut } = useSubmissionStream(submissionId);

  const loadContest = useCallback(
    async (signal?: AbortSignal) => {
      try {
        const fetched = await getContest(contestId, signal);
        if (signal?.aborted) return;
        setContest(fetched);
      } catch (caught) {
        if (signal?.aborted) return;
        if (apiErrorCode(caught) === 'CONTEST_NOT_FOUND') {
          setNotFound(true);
        } else {
          setLoadError(describeApiError(caught));
        }
      }
    },
    [contestId],
  );

  useEffect(() => {
    const controller = new AbortController();
    // oxlint-disable-next-line set-state-in-effect
    void loadContest(controller.signal);
    return () => controller.abort();
  }, [loadContest]);

  const refresh = useCallback(() => void loadContest(), [loadContest]);
  const clock = useContestClock(contest, refresh);

  // The contest carries the problem's slug; the statement comes from the existing problem
  // endpoint, which already knows how to serve one without disclosing its test cases.
  const entry: ContestProblemEntry | undefined = contest?.problems.find(
    (candidate) => candidate.problemId === problemId,
  );

  useEffect(() => {
    if (!entry) {
      return;
    }
    const controller = new AbortController();
    getProblem(entry.problemSlug, controller.signal)
      // oxlint-disable-next-line set-state-in-effect
      .then(setProblem)
      .catch((caught: unknown) => {
        if (!controller.signal.aborted) {
          setLoadError(describeApiError(caught));
        }
      });
    return () => controller.abort();
  }, [entry]);

  function changeLanguage(next: Language) {
    setLanguage(next);
    setSource(loadDraft(contestId, problemId, next));
  }

  function changeSource(next: string) {
    setSource(next);
    try {
      window.localStorage.setItem(draftKey(contestId, problemId, language), next);
    } catch {
      // A draft that cannot be saved is not worth an error message.
    }
  }

  async function submit() {
    if (submitting) {
      return;
    }
    setSubmitting(true);
    setSubmitError(null);
    try {
      const accepted = await submitToContest(contestId, problemId, language, source);
      setSubmissionId(accepted.submissionId);
    } catch (caught) {
      // A rejection here is usually the deadline, and the server's message says which side
      // of it the submission landed on. Re-fetch so the page stops showing a live contest.
      setSubmitError(describeApiError(caught));
      await loadContest();
    } finally {
      setSubmitting(false);
    }
  }

  if (notFound) {
    return (
      <div className="page">
        <section className="panel empty-state">
          <h1>Not found</h1>
          <p>This contest or problem is not available to you.</p>
          <Link className="button" to="/contests">
            Back to contests
          </Link>
        </section>
      </div>
    );
  }

  if (loadError && !contest) {
    return (
      <div className="page">
        <div className="panel" role="alert">
          <p className="status status--error">Could not load the contest</p>
          <p className="status-detail">{loadError}</p>
        </div>
      </div>
    );
  }

  if (!contest) {
    return (
      <div className="page">
        <p className="status status--pending" role="status">
          Loading&hellip;
        </p>
      </div>
    );
  }

  if (!entry) {
    return (
      <div className="page">
        <section className="panel empty-state">
          <h1>Problem not in this contest</h1>
          <p>
            {contest.status === 'UPCOMING'
              ? 'The problems appear when the contest starts.'
              : 'This problem is not part of this contest.'}
          </p>
          <Link className="button" to={`/contests/${contestId}`}>
            Back to the contest
          </Link>
        </section>
      </div>
    );
  }

  // The countdown may have run out a moment before the page re-fetched. Disabling on either
  // signal keeps the button honest without ever being the thing that enforces the deadline.
  const timeIsUp = clock?.status === 'ENDED';
  const canSubmit = contest.canSubmit && !timeIsUp;

  return (
    <div className="page">
      <section className="hero">
        <div className="title-row">
          <h1>
            {entry.label}. {entry.title}
          </h1>
          <ContestStatusPill status={contest.status} />
        </div>
        <p className="meta-row">
          <Link to={`/contests/${contestId}`}>{contest.title}</Link>
          <span>{entry.points} points</span>
          {clock?.target === 'end' && <span>{formatDuration(clock.remainingMs)} left</span>}
        </p>
      </section>

      {problem && (
        <section className="panel">
          <h2>Statement</h2>
          <p className="statement">{problem.statement}</p>
          {problem.inputFormat && (
            <>
              <h3>Input</h3>
              <p className="statement">{problem.inputFormat}</p>
            </>
          )}
          {problem.outputFormat && (
            <>
              <h3>Output</h3>
              <p className="statement">{problem.outputFormat}</p>
            </>
          )}
          {problem.constraints && (
            <>
              <h3>Constraints</h3>
              <p className="statement">{problem.constraints}</p>
            </>
          )}
          {problem.examples.length > 0 && (
            <>
              <h3>Examples</h3>
              {problem.examples.map((example, index) => (
                <div key={index} className="example">
                  <pre className="code-block">{example.input}</pre>
                  <pre className="code-block">{example.output}</pre>
                  {example.explanation && <p className="statement">{example.explanation}</p>}
                </div>
              ))}
            </>
          )}
        </section>
      )}

      <section className="panel">
        <h2>Your solution</h2>

        <div className="filter-row">
          <div className="filter-field">
            <label htmlFor="contest-language">Language</label>
            <select
              id="contest-language"
              value={language}
              onChange={(event) => changeLanguage(event.target.value as Language)}
            >
              {LANGUAGES.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </div>
        </div>

        <label htmlFor="contest-source" className="visually-hidden">
          Source code
        </label>
        <textarea
          id="contest-source"
          className="code-editor"
          value={source}
          spellCheck={false}
          rows={18}
          onChange={(event) => changeSource(event.target.value)}
        />

        <div className="form-actions">
          <button type="button" className="button" onClick={submit} disabled={!canSubmit || submitting}>
            {submitting ? 'Submitting…' : 'Submit'}
          </button>
        </div>

        {!contest.registered && (
          <p className="field-hint">You are not registered for this contest.</p>
        )}
        {timeIsUp && (
          <p className="field-hint">
            The contest has ended. Submissions are closed.
          </p>
        )}
        {submitError && (
          <p className="form-error" role="alert">
            {submitError}
          </p>
        )}
      </section>

      {submissionId && (
        <SubmissionVerdict
          status={status?.status ?? 'QUEUED'}
          testsTotal={status?.testsTotal}
          testsPassed={status?.testsPassed}
          failedTestIndex={status?.failedTestIndex}
          runtimeMs={status?.runtimeMs}
          errorMessage={status?.errorMessage}
          timedOut={timedOut}
        />
      )}

      {submissionId && !pending && (
        <p className="form-aside">
          <Link to={`/contests/${contestId}`}>Back to the contest</Link>
          {' · '}
          <Link to={`/submissions/${submissionId}`}>This submission</Link>
        </p>
      )}
    </div>
  );
}
