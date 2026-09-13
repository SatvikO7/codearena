export type Language = 'CPP' | 'JAVA' | 'PYTHON';

export type SubmissionStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'ACCEPTED'
  | 'WRONG_ANSWER'
  | 'COMPILATION_ERROR'
  | 'RUNTIME_ERROR'
  | 'TIME_LIMIT_EXCEEDED'
  | 'MEMORY_LIMIT_EXCEEDED'
  | 'SYSTEM_ERROR';

/** Mirrors SubmissionStatus.isTerminal on the server; polling stops on these. */
const TERMINAL: SubmissionStatus[] = [
  'ACCEPTED',
  'WRONG_ANSWER',
  'COMPILATION_ERROR',
  'RUNTIME_ERROR',
  'TIME_LIMIT_EXCEEDED',
  'MEMORY_LIMIT_EXCEEDED',
  'SYSTEM_ERROR',
];

export function isTerminal(status: SubmissionStatus): boolean {
  return TERMINAL.includes(status);
}

export const LANGUAGES: { value: Language; label: string }[] = [
  { value: 'CPP', label: 'C++' },
  { value: 'JAVA', label: 'Java' },
  { value: 'PYTHON', label: 'Python' },
];

/** Human wording for each verdict, so the UI never shows a raw enum. */
/**
 * A non-colour indicator for each state.
 *
 * <p>Colour alone must not carry meaning: roughly one in twelve men cannot reliably tell
 * the green "accepted" pill from the red "wrong answer" one. Every status therefore has a
 * distinct glyph as well as a distinct word.
 */
export const STATUS_GLYPHS: Record<SubmissionStatus, string> = {
  QUEUED: '○',            // ○ hollow circle: waiting
  RUNNING: '◔',           // ◔ partial circle: in progress
  ACCEPTED: '✓',          // ✓ check
  WRONG_ANSWER: '✗',      // ✗ cross
  COMPILATION_ERROR: '⚠', // ⚠ warning
  RUNTIME_ERROR: '⚠',
  TIME_LIMIT_EXCEEDED: '⏱', // ⏱ stopwatch
  MEMORY_LIMIT_EXCEEDED: '■', // ■ filled block
  SYSTEM_ERROR: '⚙',      // ⚙ gear: our fault, not yours
};

/**
 * A one-line explanation of each verdict.
 *
 * <p>SYSTEM_ERROR is worded to make clear the judge failed, not the code. Blaming a user
 * for our infrastructure is both wrong and actively unhelpful — they would go looking for a
 * bug that is not there.
 */
export const STATUS_EXPLANATIONS: Record<SubmissionStatus, string> = {
  QUEUED: 'Waiting for a judge worker to pick this up.',
  RUNNING: 'Compiling and running your program against the test cases.',
  ACCEPTED: 'Your program produced the expected output on every test.',
  WRONG_ANSWER: 'Your program ran successfully but produced the wrong output.',
  COMPILATION_ERROR: 'Your program did not compile.',
  RUNTIME_ERROR: 'Your program stopped unexpectedly while running.',
  TIME_LIMIT_EXCEEDED: 'Your program ran longer than the problem allows.',
  MEMORY_LIMIT_EXCEEDED: 'Your program used more memory than the problem allows.',
  SYSTEM_ERROR: 'The judge hit an infrastructure problem. This is not a fault in your code — try submitting again.',
};

export const STATUS_LABELS: Record<SubmissionStatus, string> = {
  QUEUED: 'Queued',
  RUNNING: 'Running',
  ACCEPTED: 'Accepted',
  WRONG_ANSWER: 'Wrong answer',
  COMPILATION_ERROR: 'Compilation error',
  RUNTIME_ERROR: 'Runtime error',
  TIME_LIMIT_EXCEEDED: 'Time limit exceeded',
  MEMORY_LIMIT_EXCEEDED: 'Memory limit exceeded',
  SYSTEM_ERROR: 'Judge error',
};

export interface SubmissionAccepted {
  submissionId: string;
  status: SubmissionStatus;
  createdAt: string;
}

export interface SubmissionSummary {
  id: string;
  problemId: string;
  problemSlug: string;
  problemTitle: string;
  language: Language;
  status: SubmissionStatus;
  testsTotal?: number;
  testsPassed?: number;
  runtimeMs?: number;
  createdAt: string;
  finishedAt?: string;
}

/** One test's outcome. Deliberately carries nothing about the test's contents. */
export interface TestResult {
  position: number;
  passed: boolean;
  /** Absent when the test never ran — judging stops at the first failure. */
  runtimeMs?: number;
  hidden: boolean;
}

/** The detail view. Source is present because the caller is the author. */
export interface SubmissionDetail extends SubmissionSummary {
  sourceCode: string;
  failedTestIndex?: number;
  errorMessage?: string;
  testResults: TestResult[];
  attempts: number;
  startedAt?: string;
  /** True once the verdict is final. */
  terminal?: boolean;
  /** When the row last changed; the basis of event convergence. */
  updatedAt: string;
}

/** Starter templates, so the editor is never an empty box. */
export const STARTER_CODE: Record<Language, string> = {
  CPP: `#include <iostream>

int main() {
    // Read from stdin, write to stdout.
    return 0;
}
`,
  JAVA: `import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        // Read from stdin, write to stdout.
    }
}
`,
  PYTHON: `import sys

def main():
    # Read from stdin, write to stdout.
    pass

main()
`,
};
