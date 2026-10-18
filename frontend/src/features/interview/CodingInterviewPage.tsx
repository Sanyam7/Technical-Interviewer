import { useParams } from 'react-router';

import { InterviewRoom } from './InterviewRoom';

/** The coding section of an interview. The room sends the browser here when the open question is a coding question. */
export function CodingInterviewPage() {
  const { id = '' } = useParams();
  return <InterviewRoom interviewId={id} />;
}
