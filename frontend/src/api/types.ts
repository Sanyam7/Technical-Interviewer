import type { components } from './schema';

/** Names for the generated schema types, so features import `Report` and not `components['schemas'][…]`. */
type Schemas = components['schemas'];

export type User = Schemas['UserResponse'];
export type TokenResponse = Schemas['TokenResponse'];
export type LoginRequest = Schemas['LoginRequest'];
export type RegisterRequest = Schemas['RegisterRequest'];

export type Profile = Schemas['ProfileResponse'];
export type UpdateProfileRequest = Schemas['UpdateProfileRequest'];
export type Resume = Schemas['ResumeResponse'];
export type ResumeProfile = Schemas['ResumeProfileView'];
export type ResumeExperience = Schemas['ExperienceView'];
export type ResumeReference = Schemas['ResumeReferenceView'];

export type CatalogOptions = Schemas['OptionsResponse'];
export type CatalogSkill = Schemas['SkillResponse'];
export type InterviewTypeOption = Schemas['InterviewTypeOption'];

export type StrategyOption = Schemas['StrategyOption'];
export type InterviewStrategy = Schemas['ConfigurationResponse']['strategy'];
export type StageMix = Schemas['StageMixView'];

export type InterviewConfiguration = Schemas['ConfigurationResponse'];
export type ConfigurationRequest = Schemas['ConfigurationRequest'];
export type CreateInterviewRequest = Schemas['CreateInterviewRequest'];
export type CreatedInterview = Schemas['InterviewResponse'];
export type InterviewSummary = Schemas['InterviewSummaryResponse'];
export type InterviewPage = Schemas['PageResponseInterviewSummaryResponse'];
export type ConfigurationPage = Schemas['PageResponseConfigurationResponse'];

export type InterviewState = Schemas['SessionResponse']['state'];
export type Section = Schemas['QuestionResponse']['section'];
export type InterviewType = Schemas['ConfigurationRequest']['interviewType'];
export type Role = Schemas['ConfigurationRequest']['role'];
export type ExperienceLevel = Schemas['ConfigurationRequest']['experienceLevel'];
export type Difficulty = Schemas['ConfigurationRequest']['difficulty'];

export type Session = Schemas['SessionResponse'];
export type Progress = Schemas['ProgressResponse'];
export type Question = Schemas['QuestionResponse'];
export type QuestionType = NonNullable<Question['questionType']>;
export type AnswerRequest = Schemas['AnswerRequest'];
export type AnswerResult = Schemas['AnswerResponse'];
export type FinishReason = Schemas['FinishRequest']['reason'];
export type FinishResult = Schemas['FinishResponse'];

export type Transcript = Schemas['TranscriptResponse'];
export type TranscriptEntry = Schemas['TranscriptEntry'];
export type Feedback = Schemas['FeedbackResponse'];
export type CriterionFeedback = Schemas['CriterionFeedback'];
export type KeyPointFeedback = Schemas['KeyPointFeedback'];
export type CodeRunRequest = Schemas['CodeRunRequest'];
export type CodeRunResult = Schemas['CodeRunResponse'];
export type CodeTestResult = Schemas['CodeTestResultView'];
export type CodeTestsFeedback = Schemas['CodeTestsFeedback'];
export type CodeAnalysisFeedback = Schemas['CodeAnalysisFeedback'];
export type Architecture = Schemas['ArchitectureView'];
export type ArchitectureComponent = Schemas['ComponentView'];
export type ArchitectureConnection = Schemas['ConnectionView'];
export type ComponentKind = ArchitectureComponent['kind'];
export type DesignDetails = Schemas['DesignDetailsResponse'];
export type Criterion = CriterionFeedback['criterion'];

export type Report = Schemas['ReportResponse'];
export type ReportStatus = Report['status'];
export type Recommendation = NonNullable<Report['recommendation']>;
export type SkillScore = Schemas['SkillScoreResponse'];
export type SectionScore = Schemas['SectionScoreResponse'];
export type NarrativeItem = Schemas['NarrativeItemResponse'];
export type KnowledgeGaps = Schemas['KnowledgeGapsResponse'];
export type IntegrityNote = Schemas['IntegrityNoteResponse'];
export type AssessmentAreas = Schemas['AssessmentAreasResponse'];
export type AreaScore = Schemas['AreaScoreResponse'];
export type AssessmentArea = AreaScore['area'];

/** The states in which the interview still takes answers. */
export const RUNNING_STATES: readonly InterviewState[] = [
  'INTRODUCTION',
  'QUESTIONING',
  'FOLLOW_UP',
  'CODING',
  'SYSTEM_DESIGN',
];

export function isRunning(state: InterviewState): boolean {
  return RUNNING_STATES.includes(state);
}
