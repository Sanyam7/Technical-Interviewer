import type {
  Architecture,
  CatalogOptions,
  CatalogSkill,
  CriterionFeedback,
  InterviewConfiguration,
  InterviewSummary,
  Question,
  Report,
  Session,
  TokenResponse,
  Transcript,
  TranscriptEntry,
  User,
} from '../api/types';

export const USER: User = {
  id: '01a11b6f-e978-77d1-8b36-beb2d9b5b25e',
  email: 'ada@example.com',
  displayName: 'Ada Lovelace',
  role: 'CANDIDATE',
};

export const TOKEN: TokenResponse = {
  accessToken: 'access-token-1',
  tokenType: 'Bearer',
  expiresIn: 900,
  user: USER,
};

export const OPTIONS: CatalogOptions = {
  roles: ['BACKEND', 'FRONTEND', 'FULL_STACK'],
  experienceLevels: ['INTERN', 'JUNIOR', 'MID', 'SENIOR', 'STAFF'],
  difficulties: ['EASY', 'MEDIUM', 'HARD'],
  durationsMinutes: [15, 30, 45, 60, 90],
  interviewTypes: [
    { value: 'TECHNICAL', sections: ['INTRODUCTION', 'QUESTIONING'] },
    { value: 'CODING', sections: ['INTRODUCTION', 'CODING'] },
    { value: 'SYSTEM_DESIGN', sections: ['INTRODUCTION', 'SYSTEM_DESIGN'] },
    { value: 'FULL_LOOP', sections: ['INTRODUCTION', 'QUESTIONING', 'CODING', 'SYSTEM_DESIGN'] },
  ],
  codingLanguages: ['java', 'javascript', 'typescript', 'python', 'go'],
  executableLanguages: ['java', 'javascript', 'typescript'],
  strategies: [
    {
      value: 'FUNDAMENTALS_FIRST',
      mix: { fundamentals: 60, practical: 25, project: 15 },
      requiresResume: false,
    },
    {
      value: 'BALANCED',
      mix: { fundamentals: 40, practical: 30, project: 30 },
      requiresResume: false,
    },
    {
      value: 'RESUME_FOCUSED',
      mix: { fundamentals: 20, practical: 20, project: 60 },
      requiresResume: true,
    },
    { value: 'CUSTOM', mix: null, requiresResume: false },
  ],
};

export const SKILLS: CatalogSkill[] = [
  { id: 1, slug: 'java', name: 'Java', category: 'LANGUAGE', description: 'The JVM language' },
  { id: 2, slug: 'spring-boot', name: 'Spring Boot', category: 'FRAMEWORK', description: null },
  { id: 3, slug: 'react', name: 'React', category: 'FRAMEWORK', description: null },
];

export const CONFIGURATION: InterviewConfiguration = {
  id: 'cfg-1',
  role: 'BACKEND',
  experienceLevel: 'MID',
  difficulty: 'MEDIUM',
  durationMinutes: 30,
  interviewType: 'TECHNICAL',
  skills: [
    { slug: 'java', name: 'Java' },
    { slug: 'spring-boot', name: 'Spring Boot' },
  ],
  codingLanguage: null,
  useResume: false,
  strategy: 'FUNDAMENTALS_FIRST',
  stageMix: { fundamentals: 60, practical: 25, project: 15 },
  createdAt: '2026-10-08T12:00:00Z',
};

export const INTERVIEW_ID = '01a11b6f-ea6e-77cd-a217-eac609713be5';

export function question(overrides: Partial<Question> = {}): Question {
  return {
    id: 'q-1',
    sequenceNo: 1,
    kind: 'TECHNICAL',
    isFollowUp: false,
    section: 'QUESTIONING',
    topic: { skill: 'java', name: 'Java / Concurrency' },
    prompt: 'Why is `count++` not atomic even on a `volatile` field?',
    answerFormat: 'TEXT',
    suggestedMinutes: 5,
    details: null,
    answered: false,
    ...overrides,
  };
}

export function session(overrides: Partial<Session> = {}): Session {
  return {
    id: INTERVIEW_ID,
    state: 'QUESTIONING',
    serverTime: '2026-10-08T12:10:00Z',
    startedAt: '2026-10-08T12:00:00Z',
    deadlineAt: '2026-10-08T12:30:00Z',
    endReason: null,
    progress: {
      section: 'QUESTIONING',
      sectionIndex: 1,
      sectionCount: 2,
      questionsAnswered: 2,
      plannedQuestionsInSection: 6,
      remainingSeconds: 1200,
    },
    currentQuestion: question(),
    awaitingNextQuestion: false,
    interviewComplete: false,
    version: 4,
    configuration: CONFIGURATION,
    ...overrides,
  };
}

export function summary(overrides: Partial<InterviewSummary> = {}): InterviewSummary {
  return {
    id: INTERVIEW_ID,
    state: 'COMPLETED',
    configuration: CONFIGURATION,
    createdAt: '2026-10-08T11:55:00Z',
    startedAt: '2026-10-08T12:00:00Z',
    endedAt: '2026-10-08T12:28:00Z',
    endReason: 'PLAN_COMPLETE',
    ...overrides,
  };
}

export function report(overrides: Partial<Report> = {}): Report {
  return {
    interviewId: INTERVIEW_ID,
    status: 'READY',
    outcome: 'COMPLETE',
    overallScore: 72.5,
    recommendation: 'PROFICIENT',
    sections: [
      { section: 'INTRODUCTION', score: 80 },
      { section: 'QUESTIONING', score: 70 },
    ],
    skills: [
      {
        skill: 'Java',
        slug: 'java',
        score: 78,
        confidence: 'HIGH',
        evidenceCount: 4,
        avgDifficulty: 3,
      },
      {
        skill: 'Spring Boot',
        slug: 'spring-boot',
        score: 61,
        confidence: 'MEDIUM',
        evidenceCount: 2,
        avgDifficulty: 3,
      },
      {
        skill: 'React',
        slug: 'react',
        score: 40,
        confidence: 'LOW',
        evidenceCount: 1,
        avgDifficulty: 2,
      },
    ],
    summary: 'You reason well about **concurrency**, but proxies are a weak spot.',
    strengths: [
      {
        text: 'Clear explanation of the memory model',
        evidence: [{ questionId: 'q-1', excerpt: 'volatile gives visibility, not atomicity' }],
      },
    ],
    improvementAreas: [
      {
        text: 'Self-invocation and Spring proxies',
        evidence: [{ questionId: 'q-2', excerpt: 'I think @Transactional always applies' }],
      },
    ],
    studyPlan: ['Read about Spring AOP proxies', 'Practise atomic classes'],
    furtherReading: [
      { title: 'Java concurrency', section: 'Race conditions', source: 'java/concurrency.md' },
    ],
    integrityNotes: [],
    coverage: { questionsAnswered: 5, skipped: 1, followUps: 2, minutesUsed: 28 },
    assessment: {
      assessedShare: 1,
      assessedWeight: 20,
      intendedWeight: 20,
      unassessableAnswers: [],
    },
    knowledgeGaps: {
      missedConcepts: [
        {
          concept: 'count++ is a read-modify-write',
          status: 'MISSING',
          topic: { key: 'java/concurrency', name: 'Java / Concurrency' },
          questionId: 'q-1',
        },
      ],
      misconceptions: [
        {
          claim: '@Transactional works on self-invocation',
          correction: 'Self-invocation bypasses the proxy, so no transaction starts.',
          topic: { key: 'spring/aop', name: 'Spring / AOP' },
          questionId: 'q-2',
        },
      ],
      weakTopics: [
        { topic: { key: 'spring/aop', name: 'Spring / AOP' }, score: 30, evidenceCount: 2 },
      ],
      repeatedMistakes: [
        {
          description: 'Treats proxies as transparent',
          kind: 'MISCONCEPTION',
          basis: ['EVALUATOR_LINK'],
          questionIds: ['q-2', 'q-3'],
          topics: [{ key: 'spring/aop', name: 'Spring / AOP' }],
        },
      ],
    },
    assessmentAreas: {
      areas: [
        {
          area: 'FUNDAMENTALS',
          planned: true,
          score: 74,
          evidenceCount: 3,
          confidence: 'MEDIUM',
          shareOfOverall: 0.7,
          lowConfidenceAnswers: 0,
        },
        {
          area: 'PRACTICAL',
          planned: true,
          score: 68,
          evidenceCount: 1,
          confidence: 'LOW',
          shareOfOverall: 0.3,
          lowConfidenceAnswers: 1,
        },
        {
          area: 'PROBLEM_SOLVING',
          planned: false,
          score: null,
          evidenceCount: 0,
          confidence: null,
          shareOfOverall: null,
          lowConfidenceAnswers: 0,
        },
        {
          area: 'PROJECT',
          planned: true,
          score: null,
          evidenceCount: 0,
          confidence: null,
          shareOfOverall: null,
          lowConfidenceAnswers: 0,
        },
        {
          area: 'COMMUNICATION',
          planned: true,
          score: 75,
          evidenceCount: 5,
          confidence: 'HIGH',
          shareOfOverall: null,
          lowConfidenceAnswers: 0,
        },
      ],
      skillsNotAssessed: [{ key: 'react', name: 'React' }],
      topicsNotAssessed: [{ key: 'react/hooks', name: 'React / Hooks' }],
      recommendationCap: null,
    },
    generatedAt: '2026-10-08T12:29:00Z',
    attempts: 1,
    retryable: false,
    errorMessage: null,
    ...overrides,
  };
}

export function transcript(entries: TranscriptEntry[] = defaultEntries()): Transcript {
  return { interviewId: INTERVIEW_ID, state: 'COMPLETED', entries };
}

export function defaultEntries(): TranscriptEntry[] {
  return [
    {
      question: question(),
      answer: {
        content: 'volatile gives visibility, not atomicity',
        skipped: false,
        submittedAt: '2026-10-08T12:05:00Z',
      },
      feedback: {
        overallScore: 70,
        summary: 'Good on visibility, missed the read-modify-write.',
        criteria: [
          {
            criterion: 'CORRECTNESS',
            rating: 3,
            rationale: 'Mostly right.',
            basis: 'MODEL',
            evidence: [],
          },
          {
            criterion: 'DEPTH',
            rating: 2,
            rationale: 'Stayed on the surface.',
            basis: 'MODEL',
            evidence: [],
          },
        ],
        keyPoints: [
          { text: 'volatile gives visibility', status: 'COVERED' },
          { text: 'count++ is a read-modify-write', status: 'MISSING' },
        ],
      },
    },
    {
      question: question({ id: 'q-2', sequenceNo: 2, prompt: 'What happens on self-invocation?' }),
      answer: { content: '', skipped: true, submittedAt: '2026-10-08T12:07:00Z' },
      feedback: null,
    },
  ];
}

/** A tested coding solution: two examples passed, a hidden edge case failed, a performance test timed out. */
export function codingEntry(): TranscriptEntry {
  return {
    question: question({
      id: 'q-code',
      sequenceNo: 3,
      kind: 'CODING',
      section: 'CODING',
      topic: null,
      prompt: 'Return the indices of the two numbers that add up to `target`.',
      answerFormat: 'CODE',
      details: {
        language: 'java',
        constraints: ['2 <= nums.length <= 2 * 10^5'],
        examples: [{ input: 'nums = [2,7,11,15], target = 9', output: '[0,1]', explanation: null }],
        starterCode: 'class Solution {}',
        title: 'Two Sum',
        testable: true,
      },
    }),
    answer: {
      content: 'class Solution { public int[] twoSum(int[] nums, int target) { return null; } }',
      skipped: false,
      submittedAt: '2026-10-08T12:20:00Z',
    },
    feedback: {
      overallScore: 61.25,
      summary: 'Passes typical inputs, misses zeros and is quadratic.',
      keyPoints: [{ text: 'Uses a hash map from value to index', status: 'MISSING' }],
      criteria: [
        {
          criterion: 'CORRECTNESS',
          rating: 3,
          rationale: 'Fails when both values are zero.',
          basis: 'TESTS',
          modelRating: 4,
          note: "Set by the tests: 7 of 8 functional tests passed. The reviewer's own rating was 4.",
          evidence: [],
        },
        {
          criterion: 'TIME_COMPLEXITY',
          rating: 1,
          rationale: 'Nested loops.',
          basis: 'CAPPED',
          modelRating: 2,
          note: 'Lowered from 2 to 1: a performance test on a large input exceeded its time limit.',
          evidence: [],
        },
        {
          criterion: 'READABILITY',
          rating: 3,
          rationale: 'Clear names.',
          basis: 'MODEL',
          evidence: [],
        },
      ],
      codeTests: {
        status: 'COMPLETED',
        language: 'java',
        passed: 7,
        total: 9,
        durationMs: 2140,
        compileOutput: null,
        results: [
          {
            name: 'Example 1',
            category: 'EXAMPLE',
            status: 'PASSED',
            input: 'nums = [2,7,11,15], target = 9',
            expected: '[0,1]',
            actual: '[0,1]',
            message: null,
            timeMs: 2,
          },
          {
            name: 'zeros',
            category: 'EDGE',
            status: 'FAILED',
            message: 'Wrong answer.',
            timeMs: 1,
          },
          {
            name: 'pair at the end of a large array',
            category: 'PERFORMANCE',
            status: 'TIMEOUT',
            message: 'Exceeded the time limit of 2000 ms.',
            timeMs: 2000,
          },
        ],
      },
      codeAnalysis: {
        approach: 'Compares every pair of indices.',
        timeComplexity: 'O(n^2)',
        spaceComplexity: 'O(1)',
        referenceTimeComplexity: 'O(n)',
        referenceSpaceComplexity: 'O(n)',
      },
    },
  };
}

/** The architecture a candidate drew for a URL shortener. */
export const SHORTENER_DIAGRAM: Architecture = {
  components: [
    { id: 'api', kind: 'SERVICE', name: 'Redirect service', notes: 'stateless' },
    { id: 'db', kind: 'DATABASE', name: 'Links store' },
  ],
  connections: [{ from: 'api', to: 'db', label: 'lookup by code', async: false }],
};

/** A design problem from the bank, answered with a diagram, and a probe of the design's caching. */
export function designEntries(): TranscriptEntry[] {
  const dimensions: CriterionFeedback['criterion'][] = [
    'REQUIREMENTS',
    'HIGH_LEVEL_DESIGN',
    'API_DESIGN',
    'DATA_MODEL',
    'CACHING',
    'MESSAGING',
    'SCALABILITY',
    'AVAILABILITY',
    'RELIABILITY',
    'FAILURE_HANDLING',
    'SECURITY',
    'OBSERVABILITY',
    'TRADE_OFFS',
  ];
  const criteria = (caching: number): CriterionFeedback[] =>
    dimensions.map((criterion) => {
      const rating = criterion === 'CACHING' ? caching : criterion === 'OBSERVABILITY' ? 0 : 3;
      return {
        criterion,
        rating,
        rationale: `Rationale for ${criterion}.`,
        basis: criterion === 'SECURITY' ? 'CAPPED' : 'MODEL',
        ...(criterion === 'SECURITY'
          ? {
              modelRating: 3,
              note: 'Lowered from 3 to 1: the review could not point to anything you wrote about this.',
            }
          : {}),
        evidence: rating > 0 && criterion !== 'SECURITY' ? [`quote for ${criterion}`] : [],
      };
    });
  return [
    {
      question: question({
        id: 'q-design',
        sequenceNo: 2,
        kind: 'SYSTEM_DESIGN',
        section: 'SYSTEM_DESIGN',
        topic: null,
        prompt: 'Design a service like bit.ly.',
        design: { title: 'Design a URL shortener', facts: ['100 million new links per month'] },
      }),
      answer: {
        content: 'A stateless redirect service reads the links store.',
        skipped: false,
        submittedAt: '2026-10-08T12:20:00Z',
        architecture: SHORTENER_DIAGRAM,
      },
      feedback: {
        overallScore: 60,
        summary: 'A sound start.',
        keyPoints: [],
        criteria: criteria(1),
      },
    },
    {
      question: question({
        id: 'q-probe',
        sequenceNo: 3,
        kind: 'FOLLOW_UP',
        isFollowUp: true,
        section: 'SYSTEM_DESIGN',
        topic: null,
        prompt: 'Your redirect service reads the store on every click. Where would you cache?',
        design: { facts: [], probe: 'CACHING' },
      }),
      answer: {
        content: 'Redis in front of the store, keyed by short code.',
        skipped: false,
        submittedAt: '2026-10-08T12:30:00Z',
      },
      feedback: {
        overallScore: 68,
        summary: 'Caching added.',
        keyPoints: [],
        criteria: criteria(3),
      },
    },
  ];
}
