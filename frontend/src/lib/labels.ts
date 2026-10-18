import type {
  AssessmentArea,
  CriterionFeedback,
  Difficulty,
  ExperienceLevel,
  InterviewState,
  InterviewStrategy,
  InterviewType,
  QuestionType,
  Recommendation,
  Role,
  Section,
} from '../api/types';

export const ROLE_LABEL: Record<Role, string> = {
  BACKEND: 'Backend',
  FRONTEND: 'Frontend',
  FULL_STACK: 'Full stack',
};

export const ROLE_HINT: Record<Role, string> = {
  BACKEND: 'APIs, data, services',
  FRONTEND: 'UI, state, performance',
  FULL_STACK: 'Both sides of the stack',
};

export const EXPERIENCE_LABEL: Record<ExperienceLevel, string> = {
  INTERN: 'Intern',
  JUNIOR: 'Junior',
  MID: 'Mid-level',
  SENIOR: 'Senior',
  STAFF: 'Staff',
};

export const DIFFICULTY_LABEL: Record<Difficulty, string> = {
  EASY: 'Easy',
  MEDIUM: 'Medium',
  HARD: 'Hard',
};

export const TYPE_LABEL: Record<InterviewType, string> = {
  TECHNICAL: 'Technical Q&A',
  CODING: 'Coding',
  SYSTEM_DESIGN: 'System design',
  FULL_LOOP: 'Full loop',
};

export const TYPE_HINT: Record<InterviewType, string> = {
  TECHNICAL: 'Concept questions with follow-ups on what you answer.',
  CODING: 'Solve a problem in code, then explain your choices.',
  SYSTEM_DESIGN:
    'Design a real product, draw its architecture and defend it under probing on scale and failures.',
  FULL_LOOP: 'Questions, coding and design in one session.',
};

export const STRATEGY_LABEL: Record<InterviewStrategy, string> = {
  FUNDAMENTALS_FIRST: 'Fundamentals first',
  BALANCED: 'Balanced',
  RESUME_FOCUSED: 'Résumé-focused',
  CUSTOM: 'Custom',
};

export const STRATEGY_HINT: Record<InterviewStrategy, string> = {
  FUNDAMENTALS_FIRST:
    'The core concepts of each technology first, then practical scenarios, then your own projects. Recommended.',
  BALANCED: 'Fundamentals, practical scenarios and your own projects in similar parts.',
  RESUME_FOCUSED: 'A short fundamentals check, then mostly the projects on your résumé.',
  CUSTOM: 'Choose how much of each part you want.',
};

/** What a technical question is, as the candidate sees it on the question. */
export const QUESTION_TYPE_LABEL: Record<QuestionType, string> = {
  CONCEPT: 'Fundamentals',
  INTERNALS: 'Fundamentals',
  SCENARIO: 'Scenario',
  DEBUGGING: 'Debugging',
  EXPERIENCE: 'Your experience',
};

export const AREA_LABEL: Record<AssessmentArea, string> = {
  FUNDAMENTALS: 'Fundamentals',
  PRACTICAL: 'Practical application',
  PROBLEM_SOLVING: 'Problem solving',
  PROJECT: 'Project depth',
  COMMUNICATION: 'Communication',
};

export const AREA_DESCRIPTION: Record<AssessmentArea, string> = {
  FUNDAMENTALS: 'Core concepts and how they work inside',
  PRACTICAL: 'Applying them in realistic scenarios',
  PROBLEM_SOLVING: 'Debugging, coding and design',
  PROJECT: 'Your own projects and experience',
  COMMUNICATION: 'How clearly you explained, in every answer',
};

export const SECTION_LABEL: Record<Section, string> = {
  INTRODUCTION: 'Introduction',
  QUESTIONING: 'Technical questions',
  CODING: 'Coding',
  SYSTEM_DESIGN: 'System design',
};

export const STATE_LABEL: Record<InterviewState, string> = {
  INIT: 'Not started',
  INTRODUCTION: 'Introduction',
  QUESTIONING: 'Questions',
  FOLLOW_UP: 'Follow-up',
  CODING: 'Coding',
  SYSTEM_DESIGN: 'System design',
  FINAL_EVALUATION: 'Evaluating',
  COMPLETED: 'Completed',
  ABANDONED: 'Abandoned',
};

export const RECOMMENDATION_LABEL: Record<Recommendation, string> = {
  STRONG: 'Strong',
  PROFICIENT: 'Proficient',
  DEVELOPING: 'Developing',
  NOT_YET: 'Not yet',
};

export const RECOMMENDATION_MEANING: Record<Recommendation, string> = {
  STRONG: 'Answers were accurate, deep and well reasoned across the interview.',
  PROFICIENT: 'A solid command of the material, with some gaps to close.',
  DEVELOPING: 'The fundamentals are there, but important areas need work.',
  NOT_YET: 'Significant gaps showed. Use the study plan, then try again.',
};

export const CRITERION_LABEL: Record<CriterionFeedback['criterion'], string> = {
  CORRECTNESS: 'Correctness',
  DEPTH: 'Depth',
  COMPLETENESS: 'Completeness',
  COMMUNICATION: 'Communication',
  PRACTICAL_UNDERSTANDING: 'Practical understanding',
  REASONING: 'Reasoning',
  ALGORITHMIC_EFFICIENCY: 'Efficiency',
  EDGE_CASES: 'Edge cases',
  CODE_QUALITY: 'Code quality',
  ALGORITHM: 'Algorithm',
  TIME_COMPLEXITY: 'Time complexity',
  SPACE_COMPLEXITY: 'Space complexity',
  READABILITY: 'Readability',
  MAINTAINABILITY: 'Maintainability',
  REQUIREMENTS: 'Requirements',
  HIGH_LEVEL_DESIGN: 'Architecture',
  DATA_MODEL: 'Data model & database',
  SCALABILITY: 'Scalability',
  RELIABILITY_AND_TRADEOFFS: 'Reliability & trade-offs',
  API_DESIGN: 'API design',
  CACHING: 'Caching',
  MESSAGING: 'Queues & async work',
  AVAILABILITY: 'Availability',
  RELIABILITY: 'Reliability',
  FAILURE_HANDLING: 'Failure handling',
  SECURITY: 'Security',
  OBSERVABILITY: 'Observability',
  TRADE_OFFS: 'Trade-offs',
};

/** The route that shows the workspace for the section a question belongs to. */
export function workspacePath(interviewId: string, section: Section | null | undefined): string {
  switch (section) {
    case 'CODING':
      return `/interviews/${interviewId}/coding`;
    case 'SYSTEM_DESIGN':
      return `/interviews/${interviewId}/design`;
    default:
      return `/interviews/${interviewId}/live`;
  }
}
