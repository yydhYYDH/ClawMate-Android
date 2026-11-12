import { applyPatches } from './source-patches.mjs';

const actions = [
  ['collection_open_app', '打开采集应用', '打开应用后手动定位页面'],
  ['collection_read_pages', '读取页面', '从当前页面读取可见文字'],
  ['collection_preview', '预览并保存采集', '确认后写入本机采集记录'],
  ['weibo_open_board', '打开微博榜单', '打开微博客户端的榜单页面'],
  ['weibo_select_hot', '切换热搜标签', '进入公开榜单'],
  ['weibo_read_hot', '读取热搜', '读取可见标题和热度'],
  ['weibo_preview', '预览并保存', '确认后写入本机采集记录'],
  ['xhs_public_notes', '小红书 AI 采集', '模型选择读取、翻页或结束']
];

const pageTemplates = [
  ['default_alipay_bill', 'com.eg.android.AlipayGphone', '请打开「我的 → 账单」。'],
  ['default_ele_order', 'me.ele', '请打开「我的 → 全部订单」。'],
  ['default_bilibili_history', 'tv.danmaku.bili', '请打开「我的 → 历史记录」。'],
  ['default_tencent_video_history', 'com.tencent.qqlive', '请在个人中心打开「观看历史」。'],
  ['default_weixin_chat', 'com.tencent.mm', '请打开指定联系人的聊天，定位到最新消息。', 3, 'up'],
  ['default_weixin_today_chat', 'com.tencent.mm', '请定位到指定联系人今天的消息，只读取一页。保存前请核对预览中的日期。', 1],
  ['default_dianping_order', 'com.dianping.v1', '请打开「我的 → 我的订单」。'],
  ['default_meituan_order', 'com.sankuai.meituan', '请打开「我的 → 全部订单」。'],
  ['default_xiaohongshu_history', 'com.xingin.xhs', '请在个人主页菜单中打开「浏览记录」。'],
  ['default_ctrip_order', 'ctrip.android.view', '请在「我的」中打开订单并切换到「全部」。']
];

export function androidPageTemplates(readLockedWorkflow) {
  return pageTemplates.map(([id, packageName, instructions, pages = 3, direction = 'down']) => {
    const legacy = JSON.parse(readLockedWorkflow(id));
    const workflow = {
      ...legacy,
      ...(packageName === 'com.tencent.mm' ? { context: { ...legacy.context, contact_name: '' } } : {}),
      steps: [
        { id: 1, type: 'android_accessibility', action: 'collection_open_app',
          package_name: packageName, task_description: instructions },
        { id: 2, type: 'android_accessibility', action: 'collection_read_pages', page_limit: pages, direction },
        { id: 3, type: 'android_accessibility', action: 'collection_preview' }
      ]
    };
    return { id, raw: JSON.stringify(workflow, null, 2),
      legacySteps: packageName === 'com.tencent.mm' ? legacy.steps : null };
  });
}

export function patchCollectionTaskPage(page) {
  const templates = actions.map(([key, label]) =>
    `  { key: '${key}', label: '${label}', shortLabel: '${label}', group: '无障碍采集', accentColor: '#148F73', backgroundColor: '#EAF8F3' },`).join('\n');
  const actionLabels = actions.map(([key, label]) => `      if (step.action === '${key}') { return '${label}'; }`).join('\n');
  const summaries = actions.filter(([key]) => !['weibo_read_hot', 'collection_open_app', 'collection_read_pages'].includes(key)).map(([key, , summary]) =>
    `      if (step.action === '${key}') { return '${summary}'; }`).join('\n');
  return applyPatches(page, [
    ['const NODE_TEMPLATES: WorkflowNodeTemplate[] = [', `const NODE_TEMPLATES: WorkflowNodeTemplate[] = [
${templates}
  { key: 'wait', label: '等待', shortLabel: '等待', group: '控制流', accentColor: '#7C4DFF', backgroundColor: '#F0EAFF' },`],
    ['interface WorkflowNodeTemplate {', `interface CollectionAppChoice {
  label: string;
  packageName: string;
}

interface WorkflowNodeTemplate {`],
    ['  @State private batchSyncing: boolean = false;', `  @State private batchSyncing: boolean = false;
  @State private collectionApps: CollectionAppChoice[] = [];`],
    ["    if (templateKey === 'open_app') {", `    if (${actions.map(([key]) => `templateKey === '${key}'`).join(' || ')}) {
      let step: WorkflowStep = { id: id, type: 'android_accessibility', action: templateKey };
      if (templateKey === 'collection_read_pages') { step.page_limit = 1; step.direction = 'down'; }
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
      if (step.action === 'collection_open_app') { return step.package_name || '选择目标应用'; }
      if (step.action === 'collection_read_pages') { return '最多 ' + String(step.page_limit ?? 1) + ' 页'; }
      return '检查无障碍节点';
    }`],
    ["          if (this.selectedStepType() === 'gui_action') {\n            this.GuiActionEditor()\n          }", `          if (this.selectedStepType() === 'gui_action') {
            this.GuiActionEditor()
          }
          if (this.selectedStepType() === 'android_accessibility') {
            this.CollectionActionEditor()
          }`],
    ["  @Builder GuiTaskEditor() {", `  private updateWeiboItemLimit(limit: number): void {
    let step = this.selectedStep();
    if (step && step.type === 'android_accessibility' && step.action === 'weibo_read_hot') {
      step.item_limit = limit;
      this.markWorkflowChanged();
    }
  }

  private async chooseCollectionApp(): Promise<void> {
    let node = this.selectedStep();
    try {
      let raw = await Bridge.createBridge('ClawMatePlatform').callMethod('guiCollectionApps') as string;
      let apps = ArkJSON.parse(raw) as CollectionAppChoice[];
      if (!this.editorOpen || this.selectedStep() !== node) { return; }
      if (apps.length === 0) { showToastSafely('找不到可选择的应用', 2000); return; }
      this.collectionApps = apps;
    } catch (error) {
      showToastSafely('无法读取已安装应用，请返回任务页重试', 2200);
    }
  }

  private updateCollectionPages(pages: number): void {
    let step = this.selectedStep();
    if (step && step.action === 'collection_read_pages') {
      step.page_limit = pages;
      this.markWorkflowChanged();
    }
  }

  @Builder CollectionActionEditor() {
    Column({ space: 8 }) {
      if (this.selectedStepAction() === 'collection_open_app') {
        this.FieldLabel('目标应用')
        if (this.collectionApps.length > 0) {
          Select(this.collectionApps.map((app: CollectionAppChoice): SelectOption =>
            ({ value: app.label + ' · ' + app.packageName })))
            .value(this.selectedStep()?.package_name || '选择已安装的应用')
            .onSelect((index: number) => {
              if (this.collectionApps[index] !== undefined && this.selectedStepAction() === 'collection_open_app') {
                this.updateSelectedStringField('package_name', this.collectionApps[index].packageName);
              }
            })
        } else {
          Button(this.selectedStep()?.package_name || '加载已安装的应用').fontSize(12)
            .onClick(() => { this.chooseCollectionApp(); })
        }
        this.FieldLabel('页面说明')
        TextInput({ placeholder: '例如 我的 → 订单', text: this.selectedStep()?.task_description || '' })
          .maxLength(180).backgroundColor(AppTheme.inputBackground())
          .onChange((value: string) => { this.updateSelectedStringField('task_description', value); })
      } else if (this.selectedStepAction() === 'collection_read_pages') {
        this.FieldLabel('最多读取页数')
        Row({ space: 6 }) {
          ForEach([1, 2, 3], (pages: number) => {
            this.SegmentButton(String(pages), String(pages), String(this.selectedStep()?.page_limit ?? 1),
              '#148F73', () => { this.updateCollectionPages(pages); })
          }, (pages: number) => String(pages))
        }
        if ((this.selectedStep()?.page_limit ?? 1) > 1) {
          this.FieldLabel('翻页方向')
          Row({ space: 6 }) {
            this.SegmentButton('向下', 'down', this.selectedStep()?.direction || 'down',
              '#148F73', () => { this.updateSelectedStringField('direction', 'down'); })
            this.SegmentButton('向上', 'up', this.selectedStep()?.direction || 'down',
              '#148F73', () => { this.updateSelectedStringField('direction', 'up'); })
          }
        }
      } else if (this.selectedStepAction() === 'collection_preview') {
        Text('确认预览后保存到本机采集记录。').fontSize(11).fontColor(AppTheme.text('#6F7584'))
      } else if (this.selectedStepAction() === 'xhs_public_notes') {
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
