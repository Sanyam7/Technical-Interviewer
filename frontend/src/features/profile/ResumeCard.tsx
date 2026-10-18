import { useId, useState, type SyntheticEvent } from 'react';

import { MAX_RESUME_BYTES, useDeleteResume, useResume, useUploadResume } from '../../api/resume';
import type { Resume, ResumeExperience } from '../../api/types';
import { Alert } from '../../components/ui/Alert';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { Card } from '../../components/ui/Card';
import { Dialog } from '../../components/ui/Dialog';
import { ErrorState } from '../../components/ui/ErrorState';
import { Icon } from '../../components/ui/Icon';
import { Skeleton } from '../../components/ui/Skeleton';
import { Spinner } from '../../components/ui/Spinner';
import { describeError } from '../../lib/errors';
import { formatDate, plural } from '../../lib/format';

/** What the file picker offers; the backend decides the type from the content, whatever the name says. */
const ACCEPT =
  '.pdf,.docx,.txt,.md,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,text/plain,text/markdown';

const KIND_TITLE: Record<ResumeExperience['kind'], string> = {
  PROJECT: 'Projects',
  ACHIEVEMENT: 'Achievements',
  RESPONSIBILITY: 'Responsibilities',
};

const FILE_TYPE_LABEL: Record<Resume['fileType'], string> = {
  PDF: 'PDF',
  DOCX: 'Word',
  TEXT: 'Text',
};

function kilobytes(bytes: number): string {
  return `${Math.max(1, Math.round(bytes / 1024)).toLocaleString('en')} KB`;
}

/** A check in the browser, for a quick answer; the server checks again and decides. */
function clientProblem(file: File): string | null {
  if (file.size === 0) {
    return 'The file is empty.';
  }
  if (file.size > MAX_RESUME_BYTES) {
    return `The file is ${kilobytes(file.size)}; a résumé may be at most 2 MB.`;
  }
  return null;
}

function UploadForm({ replacing }: { replacing: boolean }) {
  const upload = useUploadResume();
  const inputId = useId();
  const hintId = useId();
  const [file, setFile] = useState<File | null>(null);
  const problem = file ? clientProblem(file) : null;

  function onSubmit(event: SyntheticEvent) {
    event.preventDefault();
    if (file && !problem && !upload.isPending) {
      upload.mutate(file, {
        onSuccess: () => {
          setFile(null);
        },
      });
    }
  }

  const failure = upload.isError ? describeError(upload.error) : null;

  return (
    <form
      onSubmit={onSubmit}
      className="space-y-3"
      aria-label={replacing ? 'Replace résumé' : 'Upload résumé'}
    >
      <label htmlFor={inputId} className="block text-sm font-semibold">
        {replacing ? 'Replace with a new file' : 'Résumé file'}
      </label>
      <input
        id={inputId}
        type="file"
        accept={ACCEPT}
        aria-describedby={hintId}
        disabled={upload.isPending}
        className="text-ink file:border-control-border file:bg-surface-muted file:text-ink block w-full text-sm file:mr-3 file:rounded-lg file:border file:px-3 file:py-1.5 file:text-sm file:font-semibold"
        onChange={(event) => {
          upload.reset();
          setFile(event.target.files?.[0] ?? null);
        }}
      />
      <p id={hintId} className="text-ink-muted text-sm">
        PDF, Word (.docx) or plain text, up to 2 MB and 10 pages. Scanned images cannot be read.
      </p>
      {problem ? (
        <p role="alert" className="text-danger-ink text-sm font-medium">
          {problem}
        </p>
      ) : null}
      {failure ? (
        <Alert tone="danger" title={failure.title}>
          {failure.message}
        </Alert>
      ) : null}
      {upload.isPending ? (
        <p role="status" className="text-ink-muted flex items-center gap-2 text-sm">
          <Spinner size="sm" /> Reading your résumé. This takes a few seconds, or a few minutes with
          a local model.
        </p>
      ) : null}
      <Button
        type="submit"
        variant={replacing ? 'secondary' : 'primary'}
        disabled={!file || problem !== null}
        loading={upload.isPending}
        icon={<Icon name="upload" className="size-4" />}
      >
        {replacing ? 'Upload new résumé' : 'Upload résumé'}
      </Button>
    </form>
  );
}

function Facts({ title, items }: { title: string; items: { name: string; evidence: string }[] }) {
  if (items.length === 0) {
    return null;
  }
  return (
    <div>
      <h3 className="text-sm font-semibold">{title}</h3>
      <ul className="mt-2 flex flex-wrap gap-2">
        {items.map((item) => (
          <li key={item.name} title={`From your résumé: “${item.evidence}”`}>
            <Badge tone="neutral">{item.name}</Badge>
          </li>
        ))}
      </ul>
    </div>
  );
}

function ResumeProfileView({ resume }: { resume: Resume }) {
  const { profile } = resume;
  const years = profile.yearsOfExperience;
  const kinds: ResumeExperience['kind'][] = ['PROJECT', 'ACHIEVEMENT', 'RESPONSIBILITY'];

  return (
    <div className="space-y-6">
      <p className="text-ink-muted flex flex-wrap items-center gap-x-2 gap-y-1 text-sm">
        <Icon name="file" className="size-4" />
        <span className="text-ink font-semibold">{resume.fileName}</span>
        <span>
          {FILE_TYPE_LABEL[resume.fileType]} · {kilobytes(resume.sizeBytes)}
          {resume.pageCount ? ` · ${plural(resume.pageCount, 'page')}` : ''} · uploaded{' '}
          {formatDate(resume.uploadedAt)}
        </span>
      </p>

      {resume.injectionSuspected ? (
        <Alert tone="warning" title="Some text was ignored">
          Part of your résumé addressed the AI directly (instructions about the interview or the
          assessment). It was removed before the AI read the résumé and is not part of your profile.
        </Alert>
      ) : null}
      {resume.droppedItems > 0 ? (
        <p className="text-ink-muted text-sm">
          {plural(resume.droppedItems, 'item')} the AI proposed{' '}
          {resume.droppedItems === 1 ? 'was' : 'were'} left out because your résumé does not say{' '}
          {resume.droppedItems === 1 ? 'it' : 'them'} in those words.
        </p>
      ) : null}

      <dl className="grid gap-4 sm:grid-cols-2">
        <div>
          <dt className="text-ink-muted text-sm">Experience</dt>
          <dd className="mt-1 font-semibold">
            {years
              ? `${plural(years.years, 'year')} ${years.basis === 'STATED' ? '(as your résumé states)' : '(from the dates of your roles)'}`
              : 'Not stated'}
          </dd>
        </div>
        {profile.positions.length > 0 ? (
          <div>
            <dt className="text-ink-muted text-sm">Roles</dt>
            <dd className="mt-1">
              <ul className="space-y-1 text-sm">
                {profile.positions.map((position) => (
                  <li key={`${position.title}-${position.start}`}>
                    <span className="font-semibold">{position.title}</span>
                    {position.organization ? ` · ${position.organization}` : ''}
                    <span className="text-ink-muted">
                      {' '}
                      · {position.start} – {position.end ?? 'present'}
                    </span>
                  </li>
                ))}
              </ul>
            </dd>
          </div>
        ) : null}
      </dl>

      <Facts title="Technologies" items={profile.technologies} />
      <Facts title="Skills" items={profile.skills} />
      <Facts title="Domains" items={profile.domains} />

      {kinds.map((kind) => {
        const experiences = profile.experiences.filter((experience) => experience.kind === kind);
        if (experiences.length === 0) {
          return null;
        }
        return (
          <div key={kind}>
            <h3 className="text-sm font-semibold">{KIND_TITLE[kind]}</h3>
            <ul className="mt-2 space-y-2">
              {experiences.map((experience) => (
                <li key={experience.id} className="border-line border-l-2 pl-3 text-sm">
                  <p>{experience.text}</p>
                  {experience.context || experience.technologies.length > 0 ? (
                    <p className="text-ink-muted mt-0.5">
                      {[experience.context, experience.technologies.join(', ')]
                        .filter(Boolean)
                        .join(' · ')}
                    </p>
                  ) : null}
                </li>
              ))}
            </ul>
          </div>
        );
      })}
    </div>
  );
}

function DeleteResume() {
  const remove = useDeleteResume();
  const [open, setOpen] = useState(false);

  return (
    <>
      <Button
        variant="ghost"
        icon={<Icon name="trash" className="size-4" />}
        onClick={() => {
          setOpen(true);
        }}
      >
        Delete résumé
      </Button>
      <Dialog
        open={open}
        onClose={() => {
          setOpen(false);
        }}
        title="Delete your résumé?"
        description="Its profile is deleted. Interviews already personalised from it continue without personalisation."
        footer={
          <>
            <Button
              variant="secondary"
              onClick={() => {
                setOpen(false);
              }}
            >
              Keep it
            </Button>
            <Button
              variant="danger"
              loading={remove.isPending}
              onClick={() => {
                remove.mutate(undefined, {
                  onSuccess: () => {
                    setOpen(false);
                  },
                });
              }}
            >
              Delete
            </Button>
          </>
        }
      >
        {remove.isError ? <ErrorState error={remove.error} /> : null}
      </Dialog>
    </>
  );
}

/**
 * The candidate's résumé: upload, what the interviewer will know from it, deletion. Personalised interviews anchor some
 * questions on the experiences listed here, and only on those.
 */
export function ResumeCard() {
  const resume = useResume();

  return (
    <Card
      title="Résumé"
      description="Personalise interviews with questions about your own work. Only what your résumé says is kept, never the file itself."
      action={resume.data ? <DeleteResume /> : undefined}
    >
      {resume.isError ? (
        <ErrorState
          error={resume.error}
          title="Your résumé could not be loaded"
          onRetry={() => {
            void resume.refetch();
          }}
        />
      ) : resume.isPending ? (
        <div role="status" aria-label="Loading your résumé">
          <Skeleton className="h-24 w-full" />
        </div>
      ) : resume.data ? (
        <div className="space-y-8">
          <ResumeProfileView resume={resume.data} />
          <UploadForm replacing />
        </div>
      ) : (
        <UploadForm replacing={false} />
      )}
    </Card>
  );
}
