import { applyPatches } from './source-patches.mjs';

const actions = [
  ['weibo_open_board', '打开微博榜单', '打开微博客户端的榜单页面'],
  ['weibo_select_hot', '切换热搜标签', '进入公开榜单'],
  ['weibo_read_hot', '读取热搜', '读取可见标题和热度'],
  ['weibo_preview', '预览并保存', '确认后写入本机采集记录'],
  ['xhs_public_notes', '小红书 AI 采集', '模型选择读取、翻页或结束']
];

export function patchWeiboTaskPage(page) {
  const templates = actions.map(([key, label]) =>
    `  { key: '${key}', label: '${label}', shortLabel: '${label}', group: '无障碍采集', accentColor: '#148F73', backgroundColor: '#EAF8F3' },`).join('\n');
  const actionLabels = actions.map(([key, label]) => `      if (step.action === '${key}') { return '${label}'; }`).join('\n');
  const summaries = actions.filter(([key]) => key !== 'weibo_read_hot').map(([key, , summary]) =>
    `      if (step.action === '${key}') { return '${summary}'; }`).join('\n');
  return applyPatches(page, [
    ['const NODE_TEMPLATES: WorkflowNodeTemplate[] = [', `const NODE_TEMPLATES: WorkflowNodeTemplate[] = [
${templates}
  { key: 'wait', label: '等待', shortLabel: '等待', group: '控制流', accentColor: '#7C4DFF', backgroundColor: '#F0EAFF' },`],
    ["    if (templateKey === 'open_app') {", `    if (${actions.map(([key]) => `templateKey === '${key}'`).join(' || ')}) {
      let step: WorkflowStep = { id: id, type: 'android_accessibility', action: templateKey };
      if (templateKey === 'weibo_read_hot') { step.item_limit = 0; }
      if (templateKey === 'xhs_public_notes') {
        step.text = '上海城市漫步';
        step.task_description = '采集两页公开笔记摘录，然后打开预览。';
      }
      return step;
    }
    if (templateKey === 'open_app') {`],
    ["  private nodeLabel(step: WorkflowStep, path: string): string {", `  private nodeLabel(step: WorkflowStep, path: string): string {
    if (step.type === 'android_accessibility') {
${actionLabels}
      return '无障碍采集';
    }`],
    ["  private nodeSummary(step: WorkflowStep, path: string): string {", `  private nodeSummary(step: WorkflowStep, path: string): string {
    if (step.type === 'android_accessibility') {
${summaries}
      if (step.action === 'weibo_read_hot') { return step.item_limit ? '最多 ' + String(step.item_limit) + ' 条' : '逐页读取公开热搜主榜'; }
      return '检查无障碍节点';
    }`],
    ["          if (this.selectedStepType() === 'gui_action') {\n            this.GuiActionEditor()\n          }", `          if (this.selectedStepType() === 'gui_action') {
            this.GuiActionEditor()
          }
          if (this.selectedStepType() === 'android_accessibility') {
            this.WeiboActionEditor()
          }`],
    ["  @Builder GuiTaskEditor() {", `  private updateWeiboItemLimit(limit: number): void {
    let step = this.selectedStep();
    if (step && step.type === 'android_accessibility' && step.action === 'weibo_read_hot') {
      step.item_limit = limit;
      this.markWorkflowChanged();
    }
  }

  @Builder WeiboActionEditor() {
    Column({ space: 8 }) {
      if (this.selectedStepAction() === 'xhs_public_notes') {
        this.FieldLabel('公开搜索关键词')
        TextInput({ placeholder: '例如 上海城市漫步', text: this.selectedStep()?.text || '' })
          .maxLength(40).backgroundColor(AppTheme.inputBackground())
          .onChange((value: string) => { this.updateSelectedStringField('text', value); })
        this.FieldLabel('采集提示词')
        TextArea({ placeholder: '例如 采集两页公开笔记摘录，然后预览', text: this.selectedStep()?.task_description || '' })
          .maxLength(300).height(96).backgroundColor(AppTheme.inputBackground())
          .onChange((value: string) => { this.updateSelectedStringField('task_description', value); })
        Text('Decider 选择读取、翻页或结束。最多翻两次，笔记摘录保存在本机。').fontSize(11).fontColor(AppTheme.text('#6F7584'))
      } else if (this.selectedStepAction() === 'weibo_read_hot') {
        this.FieldLabel('读取范围')
        this.SegmentButton('整榜', '0', String(this.selectedStep()?.item_limit ?? 0), '#148F73',
          () => { this.updateWeiboItemLimit(0); })
        Row({ space: 6 }) {
          ForEach([1, 2, 3, 4, 5], (limit: number) => {
            this.SegmentButton(String(limit), String(limit), String(this.selectedStep()?.item_limit ?? 0),
              '#148F73', () => { this.updateWeiboItemLimit(limit); })
          }, (limit: number) => String(limit))
        }
        Text('整榜会自动翻页。保存标题、采集日期、可核对的名次和热度。').fontSize(11).fontColor(AppTheme.text('#6F7584'))
      } else {
        Text('此节点使用微博的固定操作。可在节点之间插入等待。').fontSize(11).fontColor(AppTheme.text('#6F7584'))
      }
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
  }

  @Builder GuiTaskEditor() {`],
    ["          this.SegmentButton('继续', 'continue', this.nodeOnErrorDraft, '#148F73', () => {\n            this.nodeOnErrorDraft = 'continue';\n          })", `          if (this.selectedStepType() !== 'android_accessibility') {
            this.SegmentButton('继续', 'continue', this.nodeOnErrorDraft, '#148F73', () => {
              this.nodeOnErrorDraft = 'continue';
            })
          }`]
  ]);
}
