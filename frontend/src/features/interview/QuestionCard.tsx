import type { Question, ResumeReference } from '../../api/types';
import { Badge } from '../../components/ui/Badge';
import { Icon } from '../../components/ui/Icon';
import { CRITERION_LABEL, QUESTION_TYPE_LABEL, SECTION_LABEL } from '../../lib/labels';
import { StreamedText } from './StreamedText';

interface QuestionCardProps {
  question: Question;
  /** True when this question has just arrived in this visit. */
  fresh: boolean;
}

/** "Question 3" counts every question of the interview, as the results timeline does; the badge says it is a follow-up. */
function headingLabel(question: Question): string {
  return question.kind === 'INTRO' ? 'Introduction' : `Question ${question.sequenceNo}`;
}

/** The résumé statement a personalised question is based on, so the candidate sees exactly what the interviewer read. */
export function ResumeQuote({ reference }: { reference: ResumeReference }) {
  return (
    <figure className="border-line bg-surface-muted mt-4 rounded-xl border px-4 py-3 text-sm">
      <figcaption className="text-ink-muted text-xs font-semibold tracking-wide uppercase">
        Based on your résumé{reference.context ? ` · ${reference.context}` : ''}
      </figcaption>
      <blockquote className="mt-1">“{reference.text}”</blockquote>
    </figure>
  );
}

/** The interviewer's question: who is asking, what it is about, and the text. */
export function QuestionCard({ question, fresh }: QuestionCardProps) {
  const title = question.details?.title ?? question.design?.title;
  return (
    <article
      aria-labelledby={`q-${question.id}-label`}
      className="border-line bg-surface shadow-raised animate-rise rounded-2xl border p-5 sm:p-6"
    >
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <span
            aria-hidden="true"
            className="grid size-9 place-items-center rounded-full bg-gradient-to-br from-indigo-500 to-violet-600 text-white"
          >
            <Icon name="sparkle" className="size-4.5" />
          </span>
          <div>
            <p id={`q-${question.id}-label`} className="text-sm font-semibold">
              Interviewer · {headingLabel(question)}
            </p>
            <p className="text-ink-muted text-xs">
              {question.topic ? question.topic.name : SECTION_LABEL[question.section]}
              {question.suggestedMinutes > 0 ? ` · about ${question.suggestedMinutes} min` : ''}
            </p>
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          {question.topic ? <Badge tone="brand">{question.topic.skill}</Badge> : null}
          {question.questionType && !question.resume ? (
            <Badge tone="neutral">{QUESTION_TYPE_LABEL[question.questionType]}</Badge>
          ) : null}
          {question.isFollowUp ? <Badge tone="warning">Follow-up</Badge> : null}
          {question.design?.probe ? (
            <Badge tone="brand">Probing: {CRITERION_LABEL[question.design.probe]}</Badge>
          ) : null}
          {question.resume ? <Badge tone="success">From your résumé</Badge> : null}
        </div>
      </header>
      {title ? <h2 className="mt-5 text-xl font-semibold tracking-tight">{title}</h2> : null}
      <div className={title ? 'mt-2 text-lg leading-relaxed' : 'mt-5 text-lg leading-relaxed'}>
        <StreamedText key={question.id} text={question.prompt} animate={fresh} />
      </div>
      {question.resume ? <ResumeQuote reference={question.resume} /> : null}
    </article>
  );
}
