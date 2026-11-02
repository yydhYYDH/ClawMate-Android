export interface MeetingSegment { text: string; speaker: string; start?: number; end?: number; refinement_status?: string; }
export interface MeetingQuery { view?: string; q?: string; page_size?: string; meeting_id?: string; }
export interface MeetingRevision { base_revision: number; source_hash: string; }
export interface MeetingBody {
  source?: string; title?: string; tags?: string[]; name?: string; refinement_profile_id?: string;
  auto_refine?: boolean; summary_instructions?: string; template_id?: string; revision?: number;
  base_revision?: number; source_hash?: string; segments?: MeetingSegment[]; speaker_names?: Record<string, string>;
  audio_id?: string; compare?: boolean; offline_model?: string; selection?: string; groups?: string[];
  ids?: string[]; action?: string;
}
export interface MeetingArgs { id?: string; audio_id?: string; body?: MeetingBody; query?: MeetingQuery; }
export interface MeetingDocument { revision: number; source_hash: string; audio_id?: string; transcript: string; segments: MeetingSegment[]; speaker_names: Record<string, string>; }
export interface MeetingSummary { text: string; source_hash?: string; }
export interface MeetingAudio { recoverable?: boolean; available?: boolean; }
export interface MeetingRecord { id: string; title: string; tags: string[]; status: string; draft: boolean; error?: string; document: MeetingDocument; summary?: MeetingSummary; document_audio?: MeetingAudio; active_audio_id?: string; }
export interface MeetingHistoryItem { id: string; title: string; status: string; preview?: string; }
export interface MeetingHistory { items: MeetingHistoryItem[]; total: number; }
export interface MeetingCapture { mid?: string; streaming: boolean; recording: boolean; paused: boolean; audio_seconds: number; phase: string; message?: string; audio_id?: string; finals: MeetingSegment[]; }
export interface MeetingEngine { phase: string; error?: string; }
export interface MeetingEvent { type: string; text?: string; }
export interface MeetingPoll { v: number; engine: MeetingEngine; capture: MeetingCapture; events: string[]; sequence: number; overflowed: boolean; }
export interface MeetingJob { v: number; status: string; payload: string; error: string; http_status: number; }
export interface MeetingModelGroup { id: string; ready: boolean; downloadable: boolean; missing: string[]; }
export interface MeetingModels { groups: MeetingModelGroup[]; state: string; message: string; }
export interface MeetingTask { id: string; status: string; stage: string; progress: number; error?: string; }
export interface MeetingTasks { items: MeetingTask[]; }
export interface MeetingVersion { revision: number; origin: string; }
export interface MeetingVersions { versions: MeetingVersion[]; }
export interface MeetingHandoffSource { namespace: string; instance_id: string; meeting_id: string; audio_id?: string; revision: number; source_hash: string; }
export interface MeetingHandoffPayload { v: number; source: MeetingHandoffSource; title: string; created: string; tags: string[]; selection: string; text: string; summary_included: boolean; }
export interface MeetingHandoffEnvelope { v: number; export_id: string; payload: string; }
export interface MeetingImportReceipt { v: number; export_id: string; source: MeetingHandoffSource; imported_at: string; }

export function emptyMeeting(): MeetingRecord {
  return { id: '', title: '', tags: [], status: 'created', draft: true,
    document: { revision: 0, source_hash: '', transcript: '', segments: [], speaker_names: {} } };
}
