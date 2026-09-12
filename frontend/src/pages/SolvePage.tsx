import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { getProblem } from '../services/problemService';
import { submitSolution } from '../services/submissionService';
import { apiErrorCode, describeApiError } from '../services/apiClient';
import { useSubmissionPolling } from '../hooks/useSubmissionPolling';
import { SubmissionVerdict } from '../components/SubmissionVerdict';
import { DifficultyBadge } from '../components/DifficultyBadge';
import { LANGUAGES, STARTER_CODE } from '../types/submission';
import type { Language } from '../types/submission';
import type { ProblemDetail } from '../types/problem';

/** Remembers the draft per problem and language, so a refresh does not discard work. */
function draftKey(slug: string, language: Language) {
  return `codearena:draft:${slug}:${language}`;
}

/**
 * Reads a saved draft, falling back to the language's starter template.
 *
 * <p>Storage can throw outright in private browsing or when site data is blocked, so every
 * access is guarded: a draft is a convenience and must never be able to break the editor.
 */
function loadDraft(slug: string, language: Language): string {
  try {
    return window.localStorage.getItem(draftKey(slug, language)) ?? STARTER_CODE[language];
  } catch {
    return STARTER_CODE[language];
  }
}

export function SolvePage() {
  const { slug } = useParams<{ slug: string }>();
  if (!slug) {
    return null;
  }
  return <SolveView key={slug} slug={slug} />;
}

function SolveView({ slug }: { slug: string }) {
  const [problem, setProblem] = useState<ProblemDetail | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [notFound, setNotFound] = useState(false);

  const [language, setLanguage] = useState<Language>('PYTHON');
  // Initialised from storage rather than restored by an effect: an effect would render the
  // template for one frame and then replace it, and would fire a cascading render.
  const [source, setSource] = useState<string>(() => loadDraft(slug, 'PYTHON'));

  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [submissionId, setSubmissionId] = useState<string | null>(null);

  const { submission, pending, timedOut } = useSubmissionPolling(submissionId);

  useEffect(() => {
    const controller = new AbortController();
    getProblem(slug, controller.signal)
      .then(setProblem)
      .catch((caught: unknown) => {
        if (controller.signal.aborted) return;
        if (apiErrorCode(caught) === 'PROBLEM_NOT_FOUND') {
          setNotFound(true);
        } else {
          setLoadError(describeApiError(caught));
        }
      });
    return () => controller.abort();
  }, [slug]);

  /** Switching language swaps in that language's draft. An event, so no effect is needed. */
  function changeLanguage(next: Language) {
    setLanguage(next);
    setSource(loadDraft(slug, next));
  }

  function updateSource(next: string) {
    setSource(next);
    try {
      window.localStorage.setItem(draftKey(slug, language), next);
    } catch {
      // Nothing to do: failing to cache a draft must not break editing.
    }
  }

  function resetToTemplate() {
    updateSource(STARTER_CODE[language]);
  }

  async function handleSubmit() {
    if (!problem || submitting || pending) {
      return;   // guards the double-click: a submission in flight blocks another
    }
    setSubmitting(true);
    setSubmitError(null);
    setSubmissionId(null);
    try {
      const accepted = await submitSolution(problem.id, language, source);
      setSubmissionId(accepted.submissionId);
    } catch (caught) {
      setSubmitError(describeApiError(caught));
    } finally {
      setSubmitting(false);
    }
  }

  if (notFound) {
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

  if (loadError) {
    return (
      <div className="page">
        <div className="panel" role="alert">
          <p className="status status--error">Could not load the problem</p>
          <p className="status-detail">{loadError}</p>
        </div>
      </div>
    );
  }

  if (!problem) {
    return (
      <div className="page">
        <p className="status status--pending" role="status">
          Loading problem&hellip;
        </p>
      </div>
    );
  }

  return (
    <div className="page">
      <section className="hero">
        <div className="title-row">
          <h1>{problem.title}</h1>
          <DifficultyBadge difficulty={problem.difficulty} />
        </div>
        <p className="meta-row">
          <span>Time limit: {problem.timeLimitMs} ms</span>
          <span>Memory limit: {problem.memoryLimitMb} MB</span>
          <Link to={`/problems/${problem.slug}`}>Read the full statement</Link>
        </p>
      </section>

      <div className="solve-layout">
        <section className="panel prose solve-statement">
          <h2>Statement</h2>
          <p>{problem.statement}</p>

          <h3>Input</h3>
          <p>{problem.inputFormat}</p>
          <h3>Output</h3>
          <p>{problem.outputFormat}</p>
          <h3>Constraints</h3>
          <p>{problem.constraints}</p>

          <h3>Examples</h3>
          {problem.examples.map((example) => (
            <div className="example" key={example.position}>
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
            </div>
          ))}
        </section>

        <div className="solve-editor">
          <section className="panel">
            <div className="editor-toolbar">
              <div className="filter-field">
                <label htmlFor="language">Language</label>
                <select
                  id="language"
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
              <button type="button" className="button button--quiet" onClick={resetToTemplate}>
                Reset to template
              </button>
            </div>

            <label className="visually-hidden" htmlFor="source">
              Source code
            </label>
            {/*
              A plain textarea with monospace styling and tab support, not an embedded IDE.
              Monaco is roughly a megabyte of JavaScript and a substantial integration; the
              editor is not what this phase is about, and a textarea that handles tabs
              correctly is genuinely usable for a competitive-programming solution.
            */}
            <textarea
              id="source"
              className="code-editor"
              spellCheck={false}
              autoCorrect="off"
              autoCapitalize="off"
              value={source}
              rows={22}
              onChange={(event) => updateSource(event.target.value)}
              onKeyDown={(event) => {
                if (event.key !== 'Tab') return;
                // Without this, Tab moves focus out of the editor, which makes indenting
                // code impossible.
                event.preventDefault();
                const target = event.currentTarget;
                const { selectionStart, selectionEnd } = target;
                const next =
                  source.slice(0, selectionStart) + '    ' + source.slice(selectionEnd);
                updateSource(next);
                requestAnimationFrame(() => {
                  target.selectionStart = selectionStart + 4;
                  target.selectionEnd = selectionStart + 4;
                });
              }}
            />

            <div className="form-actions">
              <button
                type="button"
                className="button"
                onClick={handleSubmit}
                disabled={submitting || pending || source.trim() === ''}
              >
                {submitting ? 'Submitting…' : pending ? 'Judging…' : 'Submit'}
              </button>
              <span className="field-hint">
                {source.length.toLocaleString()} characters
              </span>
            </div>

            {submitError && (
              <p className="form-error" role="alert">
                {submitError}
              </p>
            )}
          </section>

          {submissionId && !submission && (
            <p className="status status--pending" role="status">
              Submitted. Waiting for the judge&hellip;
            </p>
          )}

          {submission && <SubmissionVerdict submission={submission} timedOut={timedOut} />}

          {submissionId && (
            <p className="form-aside">
              <Link to="/submissions">See all your submissions</Link>
            </p>
          )}
        </div>
      </div>
    </div>
  );
}
