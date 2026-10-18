import type { Question, Session } from '../../api/types';

/** What the interview room shows, decided only by what the server said (API_DESIGN.md section 6.3). */
export type RoomPhase =
  | { kind: 'briefing' }
  | { kind: 'abandoned' }
  | { kind: 'question'; question: Question }
  /** The next question is decided but not produced yet: the client asks for it. */
  | { kind: 'preparing' }
  /** The question phase is over: finish to evaluate what is left and build the report. */
  | { kind: 'wrapup' }
  | { kind: 'completed' }
  /** Running, with nothing open and nothing awaited: not expected; reload to resynchronise. */
  | { kind: 'idle' };

export function roomPhase(session: Session): RoomPhase {
  switch (session.state) {
    case 'INIT':
      return { kind: 'briefing' };
    case 'ABANDONED':
      return { kind: 'abandoned' };
    case 'COMPLETED':
      return { kind: 'completed' };
    case 'FINAL_EVALUATION':
      return { kind: 'wrapup' };
    default:
      if (session.interviewComplete) {
        return { kind: 'wrapup' };
      }
      if (session.currentQuestion) {
        return { kind: 'question', question: session.currentQuestion };
      }
      return session.awaitingNextQuestion ? { kind: 'preparing' } : { kind: 'idle' };
  }
}
