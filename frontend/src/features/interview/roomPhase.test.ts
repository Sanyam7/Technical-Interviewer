import { describe, expect, it } from 'vitest';

import { question, session } from '../../test/fixtures';
import { roomPhase } from './roomPhase';

describe('roomPhase', () => {
  it('shows the briefing before the interview starts', () => {
    expect(roomPhase(session({ state: 'INIT', currentQuestion: null }))).toEqual({
      kind: 'briefing',
    });
  });

  it('shows the open question while the interview runs', () => {
    const open = question({ id: 'q-9' });
    expect(roomPhase(session({ state: 'FOLLOW_UP', currentQuestion: open }))).toEqual({
      kind: 'question',
      question: open,
    });
  });

  it('asks for the next question when the server says it is awaited', () => {
    expect(roomPhase(session({ currentQuestion: null, awaitingNextQuestion: true }))).toEqual({
      kind: 'preparing',
    });
  });

  it('wraps up once the question phase is over, whatever else the session says', () => {
    expect(roomPhase(session({ state: 'FINAL_EVALUATION', currentQuestion: null }))).toEqual({
      kind: 'wrapup',
    });
    expect(roomPhase(session({ interviewComplete: true, currentQuestion: null }))).toEqual({
      kind: 'wrapup',
    });
  });

  it('knows completed and abandoned interviews', () => {
    expect(roomPhase(session({ state: 'COMPLETED', currentQuestion: null }))).toEqual({
      kind: 'completed',
    });
    expect(roomPhase(session({ state: 'ABANDONED', currentQuestion: null }))).toEqual({
      kind: 'abandoned',
    });
  });

  it('reports a running interview with nothing open and nothing awaited as idle', () => {
    expect(roomPhase(session({ currentQuestion: null, awaitingNextQuestion: false }))).toEqual({
      kind: 'idle',
    });
  });
});
