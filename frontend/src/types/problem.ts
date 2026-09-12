export type Difficulty = 'EASY' | 'MEDIUM' | 'HARD';
export type ProblemStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';

export const DIFFICULTIES: Difficulty[] = ['EASY', 'MEDIUM', 'HARD'];

/** Mirrors the ProblemTag enum and the ck_problem_tags_tag database constraint. */
export const PROBLEM_TAGS = [
  'ARRAY', 'STRING', 'HASHING', 'SORTING', 'BINARY_SEARCH', 'TWO_POINTERS',
  'STACK', 'QUEUE', 'LINKED_LIST', 'TREE', 'GRAPH', 'GREEDY',
  'DYNAMIC_PROGRAMMING', 'DSU', 'MATH', 'BIT_MANIPULATION', 'HEAP', 'RECURSION',
] as const;

export type ProblemTag = (typeof PROBLEM_TAGS)[number];

/** The paged envelope every listing endpoint returns. `page` is zero-based. */
export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
  hasNext: boolean;
  hasPrevious: boolean;
}

export interface ProblemSummary {
  id: string;
  slug: string;
  title: string;
  difficulty: Difficulty;
  tags: ProblemTag[];
  /** Present only in admin listings; the public catalogue is published by definition. */
  status?: ProblemStatus;
  createdAt: string;
  updatedAt?: string;
}

export interface ProblemExample {
  position: number;
  input: string;
  output: string;
  explanation?: string;
}

/**
 * A published problem as a solver sees it.
 *
 * Note the absence of any test-case field: the server never sends one to this endpoint,
 * and mirroring that here keeps the client from ever expecting one.
 */
export interface ProblemDetail {
  id: string;
  slug: string;
  title: string;
  statement: string;
  inputFormat: string;
  outputFormat: string;
  constraints: string;
  explanation?: string;
  difficulty: Difficulty;
  tags: ProblemTag[];
  timeLimitMs: number;
  memoryLimitMb: number;
  examples: ProblemExample[];
  createdAt: string;
}

/** A judge test case. Only ever present in admin responses. */
export interface ProblemTestCase {
  position: number;
  input: string;
  expectedOutput: string;
  hidden: boolean;
  weight: number;
}

export interface AdminProblemDetail extends Omit<ProblemDetail, 'createdAt'> {
  status: ProblemStatus;
  testCases: ProblemTestCase[];
  /** Fields still blocking publication; empty means the problem is publishable. */
  missingForPublication: string[];
  createdBy?: string;
  updatedBy?: string;
  createdAt: string;
  updatedAt: string;
}

/** The create/update payload. Deliberately has no `status`: lifecycle is its own endpoint. */
export interface ProblemPayload {
  title: string;
  slug?: string;
  statement?: string;
  inputFormat?: string;
  outputFormat?: string;
  constraints?: string;
  explanation?: string;
  difficulty: Difficulty;
  tags?: ProblemTag[];
  timeLimitMs?: number;
  memoryLimitMb?: number;
  examples?: { input: string; output: string; explanation?: string }[];
  testCases?: { input: string; expectedOutput: string; hidden: boolean; weight: number }[];
}

export interface ProblemQuery {
  page?: number;
  size?: number;
  sort?: string;
  difficulty?: Difficulty | '';
  tag?: ProblemTag | '';
  search?: string;
  status?: ProblemStatus | '';
}
