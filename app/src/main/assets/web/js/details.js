import { h, clear } from './dom.js';
import { call } from './native.js';
import { app } from './app.js';
import { act, linkRow, pageShell } from './ui.js';
import { timelinePage } from './timeline.js';
import { questionPage } from './question.js';

export function detailsPage() {
  const shell = pageShell({ title: '详情' });
  const field = h('input.field.num', { type: 'text', placeholder: 'yyyy-MM-dd', 'aria-label': '自定义日日期' });
  const open = () => act(async () => {
    const date = field.value.trim();
    await call('timeline.get', { date });
    field.blur();
    app.push(timelinePage(date));
  });
  field.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); open(); } });
  let filled = false;
  return {
    el: shell.el,
    show: () => act(async () => {
      const [stats, settings] = await Promise.all([call('stats.overview'), call('settings.get')]);
      if (!filled) { field.value = stats.today; filled = true; }
      const debug = settings.values.showDebugTasks === 'true';
      clear(shell.content,
        h('div.glass.card',
          h('div', { style: { fontSize: '16px', fontWeight: '600' } }, '按自定义日查询时间轴'),
          h('label.label', '日期'), field,
          h('button.btn', { onClick: open }, '查看时间轴')),
        debug ? [h('div.sec', h('h3', '调试题目入口'), h('span', `${app.boot.questionIndex.length} 项`)),
          h('div.glass.list', app.boot.questionIndex.map((key) => linkRow(key, { onClick: () => app.push(questionPage(key)) })))] : null);
    }),
  };
}
