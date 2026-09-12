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
  memoryKb?: number;
  createdAt: string;
  finishedAt?: string;
}

/** The detail view. Source is present because the caller is the author. */
export interface SubmissionDetail extends SubmissionSummary {
  sourceCode: string;
  failedTestIndex?: number;
  errorMessage?: string;
  startedAt?: string;
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
