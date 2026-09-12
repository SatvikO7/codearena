import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  createProblem,
  getAdminProblem,
  updateProblem,
} from '../../services/problemService';
import { apiFieldErrors, describeApiError } from '../../services/apiClient';
import { FormField } from '../../components/FormField';
import { StatusBadge } from '../../components/StatusBadge';
import { DIFFICULTIES, PROBLEM_TAGS } from '../../types/problem';
import type {
  AdminProblemDetail,
  Difficulty,
  ProblemPayload,
  ProblemTag,
} from '../../types/problem';

interface ExampleRow {
  input: string;
  output: string;
  explanation: string;
}

interface TestCaseRow {
  input: string;
  expectedOutput: string;
  hidden: boolean;
  weight: number;
}

const EMPTY_EXAMPLE: ExampleRow = { input: '', output: '', explanation: '' };
const EMPTY_TEST_CASE: TestCaseRow = { input: '', expectedOutput: '', hidden: true, weight: 1 };

/**
 * Create and edit a problem.
 *
 * <p>There is no status control anywhere on this form. Publishing is a separate action on
 * the management page, because the server treats it as a distinct operation with its own
 * completeness checks — a form field would suggest otherwise.
 */
export function AdminProblemFormPage() {
  const { problemId } = useParams<{ problemId: string }>();
  const isEdit = Boolean(problemId);
  const navigate = useNavigate();

  const [title, setTitle] = useState('');
  const [slug, setSlug] = useState('');
  const [statement, setStatement] = useState('');
  const [inputFormat, setInputFormat] = useState('');
  const [outputFormat, setOutputFormat] = useState('');
  const [constraints, setConstraints] = useState('');
  const [explanation, setExplanation] = useState('');
  const [difficulty, setDifficulty] = useState<Difficulty>('EASY');
  const [tags, setTags] = useState<ProblemTag[]>([]);
  const [timeLimitMs, setTimeLimitMs] = useState(1000);
  const [memoryLimitMb, setMemoryLimitMb] = useState(256);
  const [examples, setExamples] = useState<ExampleRow[]>([{ ...EMPTY_EXAMPLE }]);
  const [testCases, setTestCases] = useState<TestCaseRow[]>([{ ...EMPTY_TEST_CASE }]);

  const [existing, setExisting] = useState<AdminProblemDetail | null>(null);
  const [loading, setLoading] = useState(isEdit);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  useEffect(() => {
    if (!problemId) return;
    const controller = new AbortController();

    getAdminProblem(problemId, controller.signal)
      .then((problem) => {
        setExisting(problem);
        setTitle(problem.title);
        setSlug(problem.slug);
        setStatement(problem.statement ?? '');
        setInputFormat(problem.inputFormat ?? '');
        setOutputFormat(problem.outputFormat ?? '');
        setConstraints(problem.constraints ?? '');
        setExplanation(problem.explanation ?? '');
        setDifficulty(problem.difficulty);
        setTags(problem.tags);
        setTimeLimitMs(problem.timeLimitMs);
        setMemoryLimitMb(problem.memoryLimitMb);
        setExamples(
          problem.examples.length > 0
            ? problem.examples.map((e) => ({
                input: e.input,
                output: e.output,
                explanation: e.explanation ?? '',
              }))
            : [{ ...EMPTY_EXAMPLE }],
        );
        setTestCases(
          problem.testCases.length > 0
            ? problem.testCases.map((t) => ({
                input: t.input,
                expectedOutput: t.expectedOutput,
                hidden: t.hidden,
                weight: t.weight,
              }))
            : [{ ...EMPTY_TEST_CASE }],
        );
      })
      .catch((caught: unknown) => {
        if (!controller.signal.aborted) setError(describeApiError(caught));
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });

    return () => controller.abort();
  }, [problemId]);

  function buildPayload(): ProblemPayload {
    // Blank optional text is sent as undefined rather than "": an empty string would be
    // stored and then fail the not-blank check at publication time for no good reason.
    const blankToUndefined = (value: string) => (value.trim() === '' ? undefined : value);

    return {
      title,
      slug: blankToUndefined(slug),
      statement: blankToUndefined(statement),
      inputFormat: blankToUndefined(inputFormat),
      outputFormat: blankToUndefined(outputFormat),
      constraints: blankToUndefined(constraints),
      explanation: blankToUndefined(explanation),
      difficulty,
      tags,
      timeLimitMs,
      memoryLimitMb,
      examples: examples
        .filter((e) => e.input.trim() !== '' || e.output.trim() !== '')
        .map((e) => ({
          input: e.input,
          output: e.output,
          explanation: blankToUndefined(e.explanation),
        })),
      testCases: testCases
        .filter((t) => t.input.trim() !== '' || t.expectedOutput.trim() !== '')
        .map((t) => ({
          input: t.input,
          expectedOutput: t.expectedOutput,
          hidden: t.hidden,
          weight: t.weight,
        })),
    };
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setFieldErrors({});
    setSaving(true);
    try {
      const payload = buildPayload();
      const saved = problemId
        ? await updateProblem(problemId, payload)
        : await createProblem(payload);
      navigate(`/admin/problems/${saved.id}`, { replace: true });
      setExisting(saved);
    } catch (caught) {
      const violations = apiFieldErrors(caught);
      if (violations.length > 0) {
        setFieldErrors(Object.fromEntries(violations.map((v) => [v.field, v.message])));
      } else {
        setError(describeApiError(caught));
      }
    } finally {
      setSaving(false);
    }
  }

  function toggleTag(tag: ProblemTag) {
    setTags((current) =>
      current.includes(tag) ? current.filter((t) => t !== tag) : [...current, tag],
    );
  }

  if (loading) {
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
          <h1>{isEdit ? 'Edit problem' : 'New problem'}</h1>
          {existing && <StatusBadge status={existing.status} />}
        </div>
        {existing && existing.missingForPublication.length > 0 && (
          <p className="notice" role="status">
            Still needed before this can be published:{' '}
            {existing.missingForPublication.join(', ')}.
          </p>
        )}
      </section>

      <form onSubmit={handleSubmit} noValidate>
        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}

        <section className="panel">
          <h2>Basics</h2>

          <FormField id="title" label="Title" error={fieldErrors.title}>
            <input
              id="title"
              value={title}
              required
              aria-invalid={Boolean(fieldErrors.title)}
              onChange={(event) => setTitle(event.target.value)}
            />
          </FormField>

          <FormField
            id="slug"
            label="Slug"
            error={fieldErrors.slug}
            hint={
              isEdit
                ? 'Changing this breaks existing links to the problem.'
                : 'Leave blank to derive it from the title.'
            }
          >
            <input
              id="slug"
              value={slug}
              aria-invalid={Boolean(fieldErrors.slug)}
              onChange={(event) => setSlug(event.target.value)}
            />
          </FormField>

          <div className="field-row">
            <FormField id="difficulty" label="Difficulty">
              <select
                id="difficulty"
                value={difficulty}
                onChange={(event) => setDifficulty(event.target.value as Difficulty)}
              >
                {DIFFICULTIES.map((value) => (
                  <option key={value} value={value}>
                    {value}
                  </option>
                ))}
              </select>
            </FormField>

            <FormField id="timeLimitMs" label="Time limit (ms)" error={fieldErrors.timeLimitMs}>
              <input
                id="timeLimitMs"
                type="number"
                min={100}
                max={15000}
                value={timeLimitMs}
                onChange={(event) => setTimeLimitMs(Number(event.target.value))}
              />
            </FormField>

            <FormField id="memoryLimitMb" label="Memory limit (MB)" error={fieldErrors.memoryLimitMb}>
              <input
                id="memoryLimitMb"
                type="number"
                min={16}
                max={1024}
                value={memoryLimitMb}
                onChange={(event) => setMemoryLimitMb(Number(event.target.value))}
              />
            </FormField>
          </div>

          <fieldset className="tag-picker">
            <legend>Topics</legend>
            {PROBLEM_TAGS.map((tag) => (
              <label key={tag} className="tag-option">
                <input
                  type="checkbox"
                  checked={tags.includes(tag)}
                  onChange={() => toggleTag(tag)}
                />
                {tag.replace(/_/g, ' ')}
              </label>
            ))}
          </fieldset>
        </section>

        <section className="panel">
          <h2>Statement</h2>

          <FormField id="statement" label="Problem statement" error={fieldErrors.statement}>
            <textarea
              id="statement"
              rows={6}
              value={statement}
              onChange={(event) => setStatement(event.target.value)}
            />
          </FormField>

          <div className="field-row">
            <FormField id="inputFormat" label="Input format" error={fieldErrors.inputFormat}>
              <textarea
                id="inputFormat"
                rows={3}
                value={inputFormat}
                onChange={(event) => setInputFormat(event.target.value)}
              />
            </FormField>

            <FormField id="outputFormat" label="Output format" error={fieldErrors.outputFormat}>
              <textarea
                id="outputFormat"
                rows={3}
                value={outputFormat}
                onChange={(event) => setOutputFormat(event.target.value)}
              />
            </FormField>
          </div>

          <FormField id="constraints" label="Constraints" error={fieldErrors.constraints}>
            <textarea
              id="constraints"
              rows={3}
              value={constraints}
              onChange={(event) => setConstraints(event.target.value)}
            />
          </FormField>

          <FormField id="explanation" label="Notes (optional)" error={fieldErrors.explanation}>
            <textarea
              id="explanation"
              rows={3}
              value={explanation}
              onChange={(event) => setExplanation(event.target.value)}
            />
          </FormField>
        </section>

        <section className="panel">
          <div className="title-row">
            <h2>Examples</h2>
            <button
              type="button"
              className="button button--quiet"
              onClick={() => setExamples([...examples, { ...EMPTY_EXAMPLE }])}
            >
              Add example
            </button>
          </div>
          <p className="field-hint">Shown to everyone who can see the problem.</p>

          {examples.map((example, index) => (
            <div className="repeat-row" key={index}>
              <div className="field-row">
                <FormField id={`example-input-${index}`} label={`Example ${index + 1} input`}>
                  <textarea
                    id={`example-input-${index}`}
                    rows={2}
                    value={example.input}
                    onChange={(event) =>
                      setExamples(examples.map((e, i) => (i === index ? { ...e, input: event.target.value } : e)))
                    }
                  />
                </FormField>
                <FormField id={`example-output-${index}`} label="Output">
                  <textarea
                    id={`example-output-${index}`}
                    rows={2}
                    value={example.output}
                    onChange={(event) =>
                      setExamples(examples.map((e, i) => (i === index ? { ...e, output: event.target.value } : e)))
                    }
                  />
                </FormField>
              </div>
              <FormField id={`example-note-${index}`} label="Explanation (optional)">
                <input
                  id={`example-note-${index}`}
                  value={example.explanation}
                  onChange={(event) =>
                    setExamples(examples.map((e, i) => (i === index ? { ...e, explanation: event.target.value } : e)))
                  }
                />
              </FormField>
              {examples.length > 1 && (
                <button
                  type="button"
                  className="button button--quiet"
                  onClick={() => setExamples(examples.filter((_, i) => i !== index))}
                >
                  Remove example {index + 1}
                </button>
              )}
            </div>
          ))}
        </section>

        <section className="panel">
          <div className="title-row">
            <h2>Judge test cases</h2>
            <button
              type="button"
              className="button button--quiet"
              onClick={() => setTestCases([...testCases, { ...EMPTY_TEST_CASE }])}
            >
              Add test case
            </button>
          </div>
          <p className="field-hint">
            Hidden cases are never returned by the public API. Leave hidden ticked unless the
            case is meant to be visible to solvers.
          </p>

          {testCases.map((testCase, index) => (
            <div className="repeat-row" key={index}>
              <div className="field-row">
                <FormField id={`test-input-${index}`} label={`Test ${index + 1} input`}>
                  <textarea
                    id={`test-input-${index}`}
                    rows={2}
                    value={testCase.input}
                    onChange={(event) =>
                      setTestCases(testCases.map((t, i) => (i === index ? { ...t, input: event.target.value } : t)))
                    }
                  />
                </FormField>
                <FormField id={`test-output-${index}`} label="Expected output">
                  <textarea
                    id={`test-output-${index}`}
                    rows={2}
                    value={testCase.expectedOutput}
                    onChange={(event) =>
                      setTestCases(
                        testCases.map((t, i) => (i === index ? { ...t, expectedOutput: event.target.value } : t)),
                      )
                    }
                  />
                </FormField>
              </div>
              <div className="field-row">
                <label className="tag-option">
                  <input
                    type="checkbox"
                    checked={testCase.hidden}
                    onChange={(event) =>
                      setTestCases(
                        testCases.map((t, i) => (i === index ? { ...t, hidden: event.target.checked } : t)),
                      )
                    }
                  />
                  Hidden from solvers
                </label>
                <FormField id={`test-weight-${index}`} label="Weight">
                  <input
                    id={`test-weight-${index}`}
                    type="number"
                    min={1}
                    max={100}
                    value={testCase.weight}
                    onChange={(event) =>
                      setTestCases(
                        testCases.map((t, i) => (i === index ? { ...t, weight: Number(event.target.value) } : t)),
                      )
                    }
                  />
                </FormField>
              </div>
              {testCases.length > 1 && (
                <button
                  type="button"
                  className="button button--quiet"
                  onClick={() => setTestCases(testCases.filter((_, i) => i !== index))}
                >
                  Remove test {index + 1}
                </button>
              )}
            </div>
          ))}
        </section>

        <div className="form-actions">
          <button className="button" type="submit" disabled={saving}>
            {saving ? 'Saving…' : isEdit ? 'Save changes' : 'Create problem'}
          </button>
          <Link className="button button--quiet" to="/admin/problems">
            Cancel
          </Link>
        </div>
      </form>
    </div>
  );
}
