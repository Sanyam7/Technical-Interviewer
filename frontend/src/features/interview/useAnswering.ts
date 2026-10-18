import { useState } from 'react';

import { type Architecture, type Question, type Session } from '../../api/types';
import { useDraft } from './useDraft';
import type { RoomCommands } from './useRoomCommands';

/** The most characters a text answer may have; a code answer may have more (API_DESIGN.md section 6.4). */
export const TEXT_LIMIT = 8_000;
export const CODE_LIMIT = 20_000;
const MAX_SECONDS = 86_400;

interface AnsweringOptions {
  session: Session;
  question: Question;
  commands: RoomCommands;
  /** What the editor starts with when there is no saved draft. */
  initial?: string;
  /** The architecture diagram sent with the answer (system design). */
  architecture?: Architecture | undefined;
}

/**
 * Everything a workspace needs to take an answer: the draft (kept in this browser), the limits, the time the question has been
 * open, and submit/skip bound to the server commands. A failed submit keeps the draft; sending it again is safe because the
 * server treats a repeated answer to the same question as one answer.
 */
export function useAnswering({
  session,
  question,
  commands,
  initial = '',
  architecture,
}: AnsweringOptions) {
  const draft = useDraft(question.id, initial);
  const [shownAt] = useState(() => Date.now());
  const isCode = question.answerFormat === 'CODE';
  const limit = isCode ? CODE_LIMIT : TEXT_LIMIT;
  const language = isCode
    ? (question.details?.language ?? session.configuration.codingLanguage ?? null)
    : null;

  const elapsedSeconds = () => Math.min(MAX_SECONDS, Math.round((Date.now() - shownAt) / 1000));
  const submitting = commands.answer.isPending;
  const skipping = commands.skip.isPending;
  const trimmed = draft.value.trim();
  const canSubmit =
    trimmed.length > 0 &&
    draft.value.length <= limit &&
    !submitting &&
    !skipping &&
    (!isCode || language !== null);

  function submit() {
    if (!canSubmit) {
      return;
    }
    commands.answer.mutate({
      question,
      content: draft.value,
      codeLanguage: language,
      timeTakenSeconds: elapsedSeconds(),
      architecture,
    });
  }

  function skip() {
    commands.skip.mutate({ question, timeTakenSeconds: elapsedSeconds() });
  }

  // The room's commands outlive a question: only a failure of this question's own answer or skip is shown here, and a retry
  // repeats whichever of the two failed last.
  const { answer: answerCommand, skip: skipCommand } = commands;
  const answerFailed = answerCommand.isError && answerCommand.variables.question.id === question.id;
  const skipFailed = skipCommand.isError && skipCommand.variables.question.id === question.id;
  const failed =
    answerFailed && (!skipFailed || answerCommand.submittedAt >= skipCommand.submittedAt)
      ? 'answer'
      : skipFailed
        ? 'skip'
        : null;

  return {
    value: draft.value,
    setValue: draft.setValue,
    limit,
    language,
    canSubmit,
    submitting,
    skipping,
    error: failed === 'answer' ? answerCommand.error : failed === 'skip' ? skipCommand.error : null,
    submit,
    skip,
    /** Repeats the command that failed. */
    retry: failed === 'skip' ? skip : submit,
  };
}

export type Answering = ReturnType<typeof useAnswering>;
