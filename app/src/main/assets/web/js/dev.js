import { h, icon, clear } from './dom.js';
import { call } from './native.js';
import { app } from './app.js';
import { act, toast, confirm, prompt, alert, switchRow, pageShell } from './ui.js';
import { applySettings } from './settings.js';

const switches = [
  ['showDebugInfo', '显示统计调试信息'], ['hideDetail', '隐藏详情'], ['usePercentageScoring', '使用百分比计分'], ['ignoreDesktopApps', '忽略桌面应用（兼容）'],
];
const groups = [
  ['时间参数', [['dayStartHour', '每日起始小时'], ['cycleLengthDays', '周期天数'], ['sleepStartHour', '睡眠开始小时'], ['sleepEndHour', '睡眠结束小时']]],
  ['评分与任务', [['sleepNoPhonePointPerMinute', '每空闲分钟基础分'], ['lateNightMaxPenaltyPerMinute', '每分钟扣分率上限'], ['noPhoneThresholdMs', '无手机时长阈值（兼容）'],
    ['chatCharsPerPoint', '聊天每分字数'], ['chatDailyMaxPoints', '聊天每日分数上限'], ['chatCharLimit', '聊天字符硬上限'],
    ['dailyTaskBonus', '早睡建议奖励'], ['checkinBonus', '每日打卡奖励'], ['restBonus', '长休息奖励'], ['restDurationMin', '长休息分钟'],
    ['restCooldownHour', '休息冷却小时（兼容）'], ['earlySleepPenalty', '早睡挑战接取扣分'], ['earlySleepReward', '早睡挑战奖励'],
    ['earlySleepThreshold', '早睡挑战扣分阈值'], ['notificationCheckIntervalSec', '通知检查间隔（秒）'], ['defaultTab', '默认页编号（兼容）']]],
  ['聊天提供商', [['chatProviderUrl', 'HTTPS API 基址'], ['chatModel', '模型'], ['chatApiKey', 'API 密钥']]],
];
const textKeys = new Set(['chatProviderUrl', 'chatModel', 'chatApiKey']);

export function devPage() {
  const save = h('button.btn', '保存并应用');
  const shell = pageShell({ title: '开发者选项', footer: h('div.footer', save) });
  const open = new Set();
  let values = null;
  let readers = {};

  function render() {
    readers = {};
    const v = values;
    const toggles = h('div.glass.list', switches.map(([key, label]) => {
      const row = switchRow(label, v[key] === 'true', () => true);
      readers[key] = () => String(row.toggle.isOn());
      return row;
    }));
    let confirmed = v.showDebugTasks === 'true';
    const debugRow = switchRow('显示调试任务', confirmed, async (on) => {
      if (!on || confirmed) return true;
      const code = await prompt({ title: '启用调试任务', hint: '输入调试口令', secret: true });
      if (code == null) return false;
      if (code.trim() !== '1101') { await alert('操作未完成', '调试口令不正确'); return false; }
      confirmed = true;
      return true;
    });
    readers.showDebugTasks = () => String(debugRow.toggle.isOn());
    toggles.append(debugRow);
    const params = groups.map(([name, fields]) => {
      const body = h(`div.param${open.has(name) ? '' : '.closed'}`, fields.map(([key, label]) => {
        const input = h('input.field', { type: key === 'chatApiKey' ? 'password' : 'text', placeholder: label });
        if (!textKeys.has(key)) input.setAttribute('inputmode', 'numeric');
        input.value = v[key] ?? '';
        readers[key] = () => input.value.trim();
        return [h('label.label', label), input];
      }));
      const state = h('span', open.has(name) ? '收起' : '展开');
      return h('div.glass.list', h('div.row.tap', { role: 'button', onClick: () => {
        if (!open.delete(name)) open.add(name);
        body.classList.toggle('closed', !open.has(name));
        state.textContent = open.has(name) ? '收起' : '展开';
      } }, h('div.grow', h('div.t', name), h('div.s', `${fields.length} 项`)), h('span.v', state, icon('down', 'chev'))), body);
    });
    clear(shell.content, toggles, params,
      h('button.btn.tonal', { style: { marginTop: '18px' }, onClick: async () => {
        if (!await confirm('恢复默认设置', '将所有设置恢复为初始值。', '恢复', true)) return;
        await act(async () => { await applySettings({}, { reset: true }); app.pop(); toast('已恢复默认设置'); });
      } }, '恢复默认设置'),
      v.showDebugTasks === 'true' ? h('button.btn.danger', { onClick: async () => {
        if (!await confirm('重置任务状态', '将清除挑战、打卡、休息完成标记和一次性任务完成状态。按旧版行为，已经写入小时记录的加扣分保留。', '重置', true)) return;
        await act(async () => { await call('tasks.reset'); toast('任务状态已重置'); });
      } }, '重置所有任务状态和额外加分') : null);
  }
  save.addEventListener('click', () => act(async () => {
    const changes = Object.fromEntries(Object.entries(readers).map(([k, read]) => [k, read()]));
    await applySettings(changes);
    toast('设置已保存');
    values = (await call('settings.get')).values;
    render();
  }));
  return { el: shell.el, show: () => act(async () => { values = (await call('settings.get')).values; render(); }) };
}
