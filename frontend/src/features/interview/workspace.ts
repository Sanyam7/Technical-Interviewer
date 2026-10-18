import type { Question, Session } from '../../api/types';
import type { RoomCommands } from './useRoomCommands';

export interface WorkspaceProps {
  session: Session;
  question: Question;
  /** The question arrived during this visit (so it may be revealed progressively). */
  fresh: boolean;
  commands: RoomCommands;
}
