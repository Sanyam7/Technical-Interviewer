import type { Report } from '../../api/types';
import { Alert } from '../../components/ui/Alert';
import { Card } from '../../components/ui/Card';
import { Icon } from '../../components/ui/Icon';
import { TurnLink } from './TurnLink';

const FLAG_TITLE: Record<Report['integrityNotes'][number]['flag'], string> = {
  INJECTION_SUSPECTED: 'An answer addressed the grader',
  EVAL_EVIDENCE_MISMATCH: 'An evaluation could not be fully verified',
  OFF_TOPIC: 'An answer was off topic',
  LANGUAGE_MISMATCH: 'An answer was in another language than the interview',
  LOW_EVALUATOR_CONFIDENCE: 'The evaluator was not confident',
};

export function StudyPlan({ report }: { report: Report }) {
  if (report.studyPlan.length === 0 && report.furtherReading.length === 0) {
    return null;
  }
  return (
    <Card title="Study plan" description="What to do next, ordered by what would help most.">
      <div className="grid gap-8 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
        {report.studyPlan.length > 0 ? (
          <ol className="space-y-4">
            {report.studyPlan.map((step, index) => (
              <li key={step} className="flex gap-3">
                <span className="bg-brand-soft text-brand-soft-ink grid size-7 shrink-0 place-items-center rounded-full text-sm font-bold tabular-nums">
                  {index + 1}
                </span>
                <p className="pt-0.5 text-pretty">{step}</p>
              </li>
            ))}
          </ol>
        ) : null}
        {report.furtherReading.length > 0 ? (
          <div>
            <h3 className="eyebrow">Further reading</h3>
            <ul className="mt-3 space-y-3">
              {report.furtherReading.map((item) => (
                <li
                  key={`${item.source ?? ''}${item.title}${item.section ?? ''}`}
                  className="flex gap-2.5 text-sm"
                >
                  <Icon name="book" className="text-ink-muted mt-0.5 size-4 shrink-0" />
                  <span>
                    <span className="font-semibold">{item.title}</span>
                    {item.section ? (
                      <span className="text-ink-muted"> · {item.section}</span>
                    ) : null}
                  </span>
                </li>
              ))}
            </ul>
          </div>
        ) : null}
      </div>
    </Card>
  );
}

/** Things a reader of the scores should know about how an answer was judged. */
export function IntegrityNotes({ report }: { report: Report }) {
  if (report.integrityNotes.length === 0) {
    return null;
  }
  return (
    <section aria-label="Integrity notes" className="space-y-3">
      {report.integrityNotes.map((note) => (
        <Alert
          key={`${note.flag}-${note.questionId}`}
          tone="warning"
          title={FLAG_TITLE[note.flag]}
          action={
            <TurnLink
              questionId={note.questionId}
              className="text-sm font-semibold underline underline-offset-4"
            />
          }
        >
          {note.note}
        </Alert>
      ))}
    </section>
  );
}
