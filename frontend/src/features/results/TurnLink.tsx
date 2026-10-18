import type { ReactNode } from 'react';

import { jumpToTurn } from './jumpToTurn';

interface TurnLinkProps {
  questionId: string;
  className?: string;
  children?: ReactNode;
}

export function TurnLink({ questionId, className, children = 'See answer' }: TurnLinkProps) {
  return (
    <a
      href={`#turn-${questionId}`}
      onClick={(event) => {
        event.preventDefault();
        jumpToTurn(questionId);
      }}
      className={className ?? 'text-brand text-sm font-semibold underline underline-offset-4'}
    >
      {children}
    </a>
  );
}
