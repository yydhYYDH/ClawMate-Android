import { DataManagerFacade } from '../datamanager/DataManagerFacade';
import { DataPipelineModelClient } from '../datamanager/DataPipelineModelClient';
import { IndexService } from '../datamanager/IndexService';
import { ConsolidatedState, ConsolidatedMetadata, AggregatedRecord, createStringMap } from '../datamanager/DataManagerTypes';
import { MeetingApi } from './MeetingApi';
import { MeetingHandoffEnvelope, MeetingHandoffPayload, MeetingImportReceipt } from './MeetingTypes';

/** ArkTS is the only writer of ClawMate's imported records and indexes. */
export class MeetingImport {
  static async save(filesDir: string, raw: string, api: MeetingApi): Promise<boolean> {
    if (AppStorage.get<boolean>('androidMemorySyncBusy') === true) { throw new Error('本机索引正在更新，请稍后保存'); }
    AppStorage.setOrCreate('androidMemorySyncBusy', true);
    try {
      let checked = await api.validateHandoff(raw);
      let envelope = JSON.parse(checked) as MeetingHandoffEnvelope;
      let value = JSON.parse(envelope.payload) as MeetingHandoffPayload;
      let facade = new DataManagerFacade(filesDir); facade.ensureReady();
      let day = value.created.slice(0, 10);
      let month = day.slice(0, 7);
      let name = 'meeting-' + envelope.export_id + '.md';
      let sourceRef = day + '/' + name + '#1';
      let output = facade.paths.outputsRoot + '/' + month + '/' + name;
      let receipts = facade.paths.rootDir + '/meeting_imports';
      let receiptPath = receipts + '/' + envelope.export_id + '.json';
      let previous = facade.storage.readJson<MeetingImportReceipt>(receiptPath,
        { v: 1, export_id: '', source: value.source, imported_at: '' });
      let duplicate = previous.export_id === envelope.export_id;
      let record: AggregatedRecord = { record_id: 1, first_seen_date: day, last_seen_date: day,
        source_refs: [sourceRef], source_dates: [day], workflow_scene_ids: [8], summary: value.text,
        normalized_fact: value.text, raw_excerpts: [value.text], tags: value.tags.concat(['会议记录']),
        dedup_status: 'explicit_meeting_import', model_notes: [], dedup_text: value.text };
      let range = createStringMap(); range.start_date = day; range.end_date = day;
      let metadata: ConsolidatedMetadata = { source_date_range: range,
        source_files: [sourceRef], processed_entry_hashes: createStringMap(), latest_record_index: 1, dedup_window_size: 1,
        updated_at: value.created, total_records: 1, month: month, recent_match_days: 0, recent_match_max_records: 0 };
      let state: ConsolidatedState = { metadata: metadata, records: [record] };
      let markdown = '# ' + value.title + '\n\n' + value.text + '\n';
      // Deterministic files make a retry after an interrupted save safe. Never replace edits.
      let existing = facade.storage.readText(output, '');
      if (existing.length > 0 && existing !== markdown) { throw new Error('已导入副本被修改，保留原内容；请另存新版本'); }
      if (!duplicate || !facade.storage.exists(output + '.state.json')) {
        facade.storage.writeTextAtomic(output, markdown);
        facade.storage.writeJsonAtomic(output + '.state.json', state as Object);
      }
      let index = new IndexService(facade.paths, facade.storage, new DataPipelineModelClient());
      await index.build({ baseUrl: '', apiKey: '', enabled: false });
      let receipt: MeetingImportReceipt = { v: 1, export_id: envelope.export_id, source: value.source,
        imported_at: duplicate ? previous.imported_at : new Date().toISOString() };
      facade.storage.writeJsonAtomic(receiptPath, receipt as Object);
      // Preserve the immutable packet beside the receipt so audio/revision provenance remains inspectable.
      facade.storage.writeTextAtomic(receipts + '/' + envelope.export_id + '.handoff.json', checked);
      return duplicate;
    } finally { AppStorage.setOrCreate('androidMemorySyncBusy', false); }
  }
}
