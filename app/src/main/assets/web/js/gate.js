import { h } from './dom.js';
import { call } from './native.js';
import { act, toast } from './ui.js';
import { moonArt } from './art.js';

/** Usage-access gate. [onGranted] runs once the permission check passes. */
export function gatePage(onGranted) {
  const content = h('div.content.gate',
    h('div.art', moonArt()),
    h('h1', '授权后开始记录'),
    h('p', '睡时记录通过 Android 的使用事件计算夜间用量。应用使用历史保存在此设备。'),
    h('button.btn', { style: { marginTop: '28px' }, onClick: () => act(() => call('app.openUsageSettings')) }, '打开使用情况访问设置'),
    h('button.btn.tonal', { onClick: () => act(async () => {
      if (await call('app.usage')) onGranted();
      else toast('请在系统设置中允许睡时记录访问使用情况');
    }) }, '已授权，重新检查'));
  const el = h('div.page', h('div.scroll', content));
  return { el, exitOnBack: true };
}
