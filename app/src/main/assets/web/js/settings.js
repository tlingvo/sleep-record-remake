import { h, icon, clear, clockHour } from './dom.js';
import { call } from './native.js';
import { app } from './app.js';
import { act, toast, confirm, prompt, alert, switchRow, linkRow, selectRow } from './ui.js';
import { devPage } from './dev.js';
import { detailsPage } from './details.js';

/** Applies setting changes, then reports invalid values the way the native app did. */
export async function applySettings(changes, { reset = false } = {}) {
  const result = await call('settings.apply', { changes, reset });
  if (result.invalid.length) toast(`部分参数已使用默认值：${result.invalid.join('、')}`);
  app.setTheme(result.dark);
  return result;
}
const unique = (list) => list.filter((v, i) => list.indexOf(v) === i);

export function settingsPage() {
  const content = h('div.content');
  const scroll = h('div.scroll', content);
  const compact = h('div.compact', '设置');
  const el = h('div.page', scroll, compact);
  scroll.addEventListener('scroll', () => compact.classList.toggle('on', scroll.scrollTop > 70), { passive: true });
  let s = null;
  const load = async () => { s = await call('settings.get'); render(); };
  const apply = (changes) => act(async () => { await applySettings(changes); await load(); });

  function hourChoice(label, key, base, iconName) {
    const current = Number(s.values[key]);
    const hours = unique([...base, current]);
    return selectRow(label, clockHour(current), hours.map((h0) => ({ label: clockHour(h0), value: String(h0) })), (v) => apply({ [key]: v }), { iconName });
  }
  function track(v) {
    const start = Number(v.sleepStartHour), end = Number(v.sleepEndHour), cut = Number(v.dayStartHour);
    const bar = h('div.track24');
    const span = (a, b) => bar.append(h('div.sl', { style: { left: `${a / 24 * 100}%`, width: `${(b - a) / 24 * 100}%` } }));
    if (start < end) span(start, end); else if (start === end) span(0, 24); else { span(start, 24); span(0, end); }
    bar.append(h('div.cut', { style: { left: `${cut / 24 * 100}%` } }));
    const now = new Date();
    bar.append(h('div.now', { style: { left: `${(now.getHours() + now.getMinutes() / 60) / 24 * 100}%` } }));
    return bar;
  }
  function render() {
    const v = s.values;
    const summary = v.summaryMode === 'true';
    const themeNames = ['跟随系统', '深色', '浅色'];
    const sleepHours = ((Number(v.sleepEndHour) - Number(v.sleepStartHour) + 23) % 24) + 1;
    clear(content,
      h('div.hero-head', h('div', h('div.eyebrow', `睡时记录 v${s.version}`), h('div.title', '设置'))),
      h('div.glass.clockcard',
        h('div.row', { style: { padding: 0, minHeight: 0 } }, h('div.grow',
          h('div.t', '计分时段'),
          h('div.s.num', `睡眠 ${clockHour(Number(v.sleepStartHour))} – ${clockHour(Number(v.sleepEndHour))}（${sleepHours} 小时）· 每日 ${clockHour(Number(v.dayStartHour))} 起算`))),
        track(v),
        h('div.ticks', ['00', '06', '12', '18', '24'].map((t) => h('span', t))),
        h('div.legend', h('span', h('i', { style: { background: 'var(--accent)' } }), '睡眠计分'), h('span', h('i', { style: { background: 'var(--ink)' } }), '每日起始'),
          h('span', h('i', { style: { background: 'var(--use)' } }), '此刻'))),
      h('div.glass.list',
        selectRow('周期长度', `${v.cycleLengthDays} 天`, unique(['1', '3', '4', '5', v.cycleLengthDays]).map((d) => ({ label: `${d} 天`, value: d })), (d) => apply({ cycleLengthDays: d }), { iconName: 'calendar' }),
        hourChoice('每日起始', 'dayStartHour', [0, 1, 3, 4, 5], 'layers'),
        hourChoice('睡眠开始', 'sleepStartHour', [22, 23, 0, 1], 'moon'),
        hourChoice('睡眠结束', 'sleepEndHour', [5, 6, 7], 'alarm')),
      h('div.sec', h('h3', '外观')),
      h('div.glass.list',
        selectRow('主题', themeNames[Number(v.darkMode)] || v.darkMode, themeNames.map((n, i) => ({ label: n, value: String(i) })), (m) => apply({ darkMode: m }), { iconName: 'palette' }),
        switchRow('显示聊天头像', s.avatars, (value) => act(() => call('settings.avatars', { value })), { iconName: 'person' })),
      h('div.sec', h('h3', '通知')),
      h('div.glass.list',
        switchRow('显示扣分率', v.showPenaltyNotification === 'true', (value) => apply({ showPenaltyNotification: String(value) }), { iconName: 'bell', sub: '睡眠时段内常驻通知' }),
        switchRow('提醒睡觉时间', v.showSleepTimeNotification === 'true', (value) => apply({ showSleepTimeNotification: String(value) }), { iconName: 'alarm', sub: '到达睡眠开始时提醒一次' })),
      h('div.sec', h('h3', 'AI 对话')),
      h('div.glass.list',
        selectRow('模型', v.chatModel, unique([s.model, v.chatModel]).map((m) => ({ label: m, value: m })), (m) => apply({ chatModel: m }), { iconName: 'spark' })),
      h('div.sec', h('h3', '本地数据')),
      h('div.glass.list',
        linkRow('导出备份', { iconName: 'download', sub: '包含使用历史、会话内容及 API 密钥', onClick: async () => {
          if (await confirm('导出备份', '备份包含使用历史、会话内容及 API 密钥。请选择妥善保管的位置。', '选择位置')) act(() => call('backup.export'));
        } }),
        linkRow('导入旧版备份', { iconName: 'upload', sub: '合并小时记录，覆盖备份中的会话与任务', onClick: () => act(() => call('backup.import')) })),
      h('div.sec', h('h3', '高级')),
      h('div.glass.list',
        linkRow('隐藏开发者选项', { iconName: 'lock', value: summary ? '已隐藏' : '已显示', onClick: async () => {
          if (!summary) { apply({ summaryMode: 'true' }); return; }
          const value = await prompt({ title: '显示开发者选项', hint: '输入 我确认 或 我确定' });
          if (value == null) return;
          if (!['我确认', '我确定', '1101'].includes(value.trim())) { await alert('操作未完成', '确认文字不匹配'); return; }
          apply({ summaryMode: 'false' });
        } }),
        summary ? null : linkRow('开发者选项', { iconName: 'code', onClick: () => app.push(devPage()) }),
        v.hideDetail === 'true' ? null : linkRow('详情', { iconName: 'timeline', sub: '按日期查看时间轴', onClick: () => app.push(detailsPage()) })),
      h('div.version', `睡时记录重制版 ${s.version}`));
  }
  return { el, scroll, show: () => act(load), reload: () => act(load) };
}
