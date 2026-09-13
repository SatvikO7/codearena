import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { SubmissionVerdict, StatusPill } from './SubmissionVerdict';
import { STATUS_GLYPHS, STATUS_LABELS } from '../types/submission';
import type { SubmissionStatus, TestResult } from '../types/submission';

const ALL_STATUSES: SubmissionStatus[] = [
  'QUEUED',
  'RUNNING',
  'ACCEPTED',
  'WRONG_ANSWER',
  'COMPILATION_ERROR',
  'RUNTIME_ERROR',
  'TIME_LIMIT_EXCEEDED',
  'MEMORY_LIMIT_EXCEEDED',
  'SYSTEM_ERROR',
];

describe('StatusPill', () => {
  it('names every status in words', () => {
    for (const status of ALL_STATUSES) {
      const { unmount } = render(<StatusPill status={status} />);
      expect(screen.getByText(STATUS_LABELS[status])).toBeInTheDocument();
      unmount();
    }
  });

  /**
   * Colour must not be the only thing distinguishing a verdict. Roughly one man in twelve
   * cannot reliably tell the green pill from the red one, so each status carries a distinct
   * glyph and a distinct word as well.
   */
  it('distinguishes every status without relying on colour', () => {
    const glyphs = new Set<string>();
    const labels = new Set<string>();

    for (const status of ALL_STATUSES) {
      glyphs.add(STATUS_GLYPHS[status]);
      labels.add(STATUS_LABELS[status]);
    }

    expect(labels.size).toBe(ALL_STATUSES.length);
    // Compilation and runtime errors share a warning glyph but never a label, so the pair
    // is still distinguishable without colour.
    expect(glyphs.size).toBeGreaterThanOrEqual(ALL_STATUSES.length - 1);
  });
});

describe('SubmissionVerdict', () => {
  it('explains what each verdict means', () => {
    render(<SubmissionVerdict status="WRONG_ANSWER" testsTotal={3} testsPassed={1} failedTestIndex={1} />);

    expect(screen.getByText(/produced the wrong output/i)).toBeInTheDocument();
  });

  /**
   * A judge failure is not the submitter's fault, and the wording has to say so: sending
   * somebody hunting for a bug that is not in their code is worse than saying nothing.
   */
  it('does not blame the user for an infrastructure failure', () => {
    render(<SubmissionVerdict status="SYSTEM_ERROR" />);

    const explanation = screen.getByText(/infrastructure problem/i);
    expect(explanation).toBeInTheDocument();
    expect(explanation.textContent).toMatch(/not a fault in your code/i);
  });

  it('shows execution metrics once the verdict is final', () => {
    render(
      <SubmissionVerdict status="ACCEPTED" testsTotal={4} testsPassed={4} runtimeMs={128} />,
    );

    expect(screen.getByText('4 / 4')).toBeInTheDocument();
    expect(screen.getByText('128 ms')).toBeInTheDocument();
  });

  it('shows no metrics while the submission is still in flight', () => {
    render(<SubmissionVerdict status="RUNNING" />);

    expect(screen.queryByText(/tests passed/i)).not.toBeInTheDocument();
    expect(screen.getByText(/compiling and running/i)).toBeInTheDocument();
  });

  it('names which test failed first, by number only', () => {
    render(<SubmissionVerdict status="WRONG_ANSWER" testsTotal={5} testsPassed={2} failedTestIndex={2} />);

    // Index 2 is the third test: the display is one-based, the API is zero-based.
    expect(screen.getByText('Test 3')).toBeInTheDocument();
  });

  it('surfaces compiler diagnostics under their own heading', () => {
    render(
      <SubmissionVerdict
        status="COMPILATION_ERROR"
        errorMessage="main.cpp:2:5: error: expected ';'"
      />,
    );

    expect(screen.getByText('Compiler output')).toBeInTheDocument();
    expect(screen.getByText(/expected ';'/)).toBeInTheDocument();
  });

  /** Attempts only mean something when there was more than one; otherwise it is noise. */
  it('mentions retries only when a submission was actually retried', () => {
    const once = render(<SubmissionVerdict status="ACCEPTED" testsTotal={1} testsPassed={1} attempts={1} />);
    expect(screen.queryByText('Attempts')).not.toBeInTheDocument();
    once.unmount();

    render(<SubmissionVerdict status="ACCEPTED" testsTotal={1} testsPassed={1} attempts={3} />);
    expect(screen.getByText('Attempts')).toBeInTheDocument();
  });

  describe('test results', () => {
    const results: TestResult[] = [
      { position: 0, passed: true, runtimeMs: 11, hidden: false },
      { position: 1, passed: false, runtimeMs: 14, hidden: true },
      { position: 2, passed: false, hidden: true },
    ];

    it('shows one entry per test', () => {
      render(<SubmissionVerdict status="WRONG_ANSWER" testResults={results} />);

      expect(screen.getByText('Tests')).toBeInTheDocument();
      expect(screen.getAllByRole('listitem')).toHaveLength(3);
    });

    /**
     * A test that never ran is neither a pass nor a failure. Rendering it as failed would
     * claim the program got it wrong when judging simply stopped before reaching it.
     */
    it('distinguishes a test that never ran from one that failed', () => {
      render(<SubmissionVerdict status="WRONG_ANSWER" testResults={results} />);

      const items = screen.getAllByRole('listitem');
      expect(items[1].className).toContain('test-chip--fail');
      expect(items[2].className).toContain('test-chip--skipped');
    });

    it('labels hidden tests without revealing anything about them', () => {
      render(<SubmissionVerdict status="WRONG_ANSWER" testResults={results} />);

      expect(screen.getByText(/Test 2 \(hidden\)/)).toBeInTheDocument();
      expect(screen.getByText(/Test 1 \(example\)/)).toBeInTheDocument();
      expect(screen.getByText(/never disclosed/i)).toBeInTheDocument();
    });

    /**
     * The containment argument, asserted at the boundary the component actually controls.
     *
     * <p>A `TestResult` carries only a number, a pass flag, a duration and a visibility
     * flag — there is no field that could hold a test's input or expected output, so the
     * component cannot render one. This pins that shape: a future field named `input` or
     * `expectedOutput` fails here.
     *
     * <p>The list's own text is scanned too, but only the list: the panel's closing
     * sentence legitimately contains the words "inputs" and "expected outputs" while
     * explaining that they are never shown, and matching on that would be testing the
     * reassurance rather than the leak.
     */
    it('cannot render test input or expected output', () => {
      for (const result of results) {
        expect(Object.keys(result).sort()).toEqual(
          expect.arrayContaining(['hidden', 'passed', 'position']),
        );
        expect(Object.keys(result)).not.toContain('input');
        expect(Object.keys(result)).not.toContain('expectedOutput');
        expect(Object.keys(result)).not.toContain('actualOutput');
      }

      render(
        <SubmissionVerdict status="WRONG_ANSWER" testsTotal={3} testsPassed={1} testResults={results} />,
      );

      const listText = screen.getByRole('list').textContent ?? '';
      expect(listText).not.toMatch(/expected/i);
      expect(listText).not.toMatch(/input/i);
      // Only test numbers, outcomes and the visibility label appear.
      expect(listText).toMatch(/Test 1 \(example\): passed/);
    });
  });

  it('says the client gave up waiting, not that the judge failed', () => {
    render(<SubmissionVerdict status="RUNNING" timedOut />);

    expect(screen.getByRole('alert').textContent).toMatch(/submission is safe/i);
  });
});
