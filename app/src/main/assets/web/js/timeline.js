import { h, icon, clear } from './dom.js';
import { call } from './native.js';
import { act, pageShell } from './ui.js';

export function timelinePage(date) {
  const exportButton = h('button.pill.small', { onClick: () => act(() => call('timeline.export', { date })) }, '导出');
  const shell = pageShell({ title: '时间轴', sub: date, actions: [exportButton] });
  const collapsed = new Set();
  let data = null;

  function render() {
    const hours = data.groups.map((g) => {
      const chips = [
        g.score > 0 ? h('span.chip.plus', `+${g.score}`) : null,
        g.deduction > 0 ? h('span.chip.minus', `−${g.deduction} (${g.ratio}×)`) : null,
        g.bonus > 0 ? h('span.chip.use', `奖励 +${g.bonus}`) : null,
      ];
      const card = h(`div.glass.hour${collapsed.has(g.at) ? '.closed' : ''}`,
        h('div.hour-head.tap', { role: 'button', 'aria-label': g.heading, onClick: () => {
          if (!collapsed.delete(g.at)) collapsed.add(g.at);
          card.classList.toggle('closed', collapsed.has(g.at));
        } },
          h('div.grow', h('div.t', g.range), h('div.s', `使用 ${g.usageText}`)), chips, icon('next', 'chev')),
        h('div.hour-body', g.rows.length ? g.rows.map((r) => h(`div.ev${r.kind === 'screen' ? '.screen' : ''}`,
          h('span.tm', r.time),
          h('span.nm', r.label, r.parts > 1 ? h('span.x', `${r.parts} 段`) : null),
          h('span.du', r.kind === 'screen' ? (r.detail ? `(${r.detail})` : '') : r.detail)))
          : h('div.ev.screen', h('span.nm', '无应用或屏幕事件'))));
      return card;
    });
    const all = (open) => {
      if (open) collapsed.clear(); else data.groups.forEach((g) => collapsed.add(g.at));
      render();
    };
    clear(shell.content,
      h('div.tl-actions', h('button.pill', { onClick: () => all(true) }, '全部展开'), h('button.pill', { onClick: () => all(false) }, '全部收起')),
      hours.length ? hours : h('div.glass.card', h('div.empty', '暂无数据')));
  }
  return {
    el: shell.el,
    async show() {
      await act(async () => { data = await call('timeline.get', { date }); render(); });
    },
  };
}
