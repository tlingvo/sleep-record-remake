import { h, icon, clear, signed, minusSign, shortDate, weekdayName, hm, clockHour } from './dom.js';
import { call, on } from './native.js';
import { app } from './app.js';
import { act, dialog, iconButton } from './ui.js';
import { nightArc, heatGrid } from './art.js';
import { timelinePage } from './timeline.js';
import { questionPage } from './question.js';

export function statsPage() {
  const content = h('div.content');
  const scroll = h('div.scroll', content);
  const compact = h('div.compact', '睡眠分数');
  const el = h('div.page', scroll, compact);
  scroll.addEventListener('scroll', () => compact.classList.toggle('on', scroll.scrollTop > 70), { passive: true });
  let data = null;
  let refreshing = false;
  const expanded = new Set();
  let firstLoad = true;
  const refreshButton = iconButton('refresh', '刷新', () => refresh(true));
  const aboutButton = iconButton('info', '关于', about);
  on('refreshing', (value) => { refreshing = value; refreshButton.classList.toggle('spin', value); refreshButton.setAttribute('aria-label', value ? '刷新中' : '刷新'); });
  on('refreshed', () => load());

  async function load() {
    data = await call('stats.overview');
    if (firstLoad && data.cycles.length) expanded.add(data.cycles[data.cycles.length - 1].index);
    firstLoad = false;
    render();
    if (app.scheduleCapture) app.scheduleCapture();
  }
  async function refresh(manual) {
    try {
      const result = await call('stats.refresh', { manual });
      if (result && result.done) await load();
    } catch (error) {
      if (manual) await dialog({ title: '操作未完成', message: error.message });
    }
  }
  async function about() {
    const c = data || { cycleLength: 3, start: 23, end: 5 };
    const more = await dialog({ title: '关于睡眠分数', positive: '功能介绍', negative: '关闭',
      message: `连续记录按每 ${c.cycleLength} 天分为一个周期。睡眠区 ${clockHour(c.start)}–${clockHour(c.end)} 内，空闲分钟得分，手机使用按小时费率扣分。百分比以周期理论分值为基准，额外加分可使结果超出原范围。` });
    if (more) app.push(questionPage('功能介绍1'));
  }

  function scoreText(cycle) {
    if (!cycle) return { word: '暂无数据' };
    if (cycle.notStarted) return { word: '未开始' };
    if (data.percentage) return { value: minusSign(cycle.percent), unit: '%' };
    return { value: minusSign(cycle.net) };
  }
  /** Same scale as the native scoreColor: red below 25 %, through amber at 50 %, to green from 75 %. */
  function tone(pct) {
    const css = getComputedStyle(document.documentElement);
    const v = (name) => css.getPropertyValue(name).trim();
    const rgb = (hex) => [0, 2, 4].map((i) => parseInt(hex.replace('#', '').slice(i, i + 2), 16));
    const mix = (a, b, t) => { const x = rgb(a), y = rgb(b); return `rgb(${x.map((c, i) => Math.round(c + (y[i] - c) * t)).join(',')})`; };
    if (pct < 25) return v('--minus');
    if (pct < 50) return mix(v('--minus'), v('--use'), (pct - 25) / 25);
    if (pct < 75) return mix(v('--use'), v('--plus'), (pct - 50) / 25);
    return v('--plus');
  }
  function render() {
    const d = data;
    const current = d.cycles[d.cycles.length - 1];
    const score = scoreText(current);
    refreshButton.classList.toggle('spin', refreshing || d.refreshing);
    const arc = h('div.arcwrap', nightArc(d.night, d.now),
      h('div.score',
        h('div.k', '当前周期'),
        score.word ? h('div.v.word', score.word) : h('div.v', { style: { color: tone(current.percent) } }, score.value,
          score.unit ? h('small', { style: { color: 'inherit', opacity: '.75' } }, score.unit) : null),
        h('div.d', current ? [h('span.num', `${shortDate(current.from)} – ${shortDate(current.to)}`), ` · 第 ${current.index + 1} 周期 · ${d.percentage ? '百分比制' : '原始分制'}`] : d.today)));

    const usage = hm(d.night.usage);
    const bonus = current ? (d.detailed ? h('b', { class: current.bonus > 0 ? 'plus' : current.bonus < 0 ? 'minus' : '' }, minusSign(current.bonus))
      : h('b.word', current.hasBonus ? '有' : '无')) : h('b.word', '—');
    const metrics = h('div.metrics',
      h('div', d.asleep ? h('b.use', d.rate.toFixed(2)) : h('b.word', '不在时段'), h('span', '每分钟扣分')),
      h('div', bonus, h('span', '额外加扣分')),
      h('div', d.night.recorded ? h('b', usage.h ? [usage.h, h('small', 'h'), ' ', usage.m, h('small', 'm')] : [usage.m, h('small', 'm')]) : h('b.word', '暂无记录'),
        h('span', d.night.today ? '今晚使用' : '昨晚使用')));
    const timelineLink = h('div.glass.list', h('div.row.tap', { role: 'button', onClick: () => app.push(timelinePage(d.today)) },
      h('span.tile', icon('timeline')), h('div.grow', h('div.t', '查看今日时间轴'), h('div.s', '按小时列出应用使用与锁屏记录')), icon('next', 'chev')));

    const list = h('div.glass.list');
    if (!d.cycles.length) list.append(h('div.empty', '暂无数据'));
    for (const cycle of d.cycles.slice().reverse()) {
      const open = expanded.has(cycle.index);
      const s = scoreText(cycle);
      const body = h(`div.cyc-body${open ? '.open' : ''}`,
        h('div.facts.num', d.detailed ? `原始 ${cycle.net} 分 · 理论 ${cycle.min} ～ ${cycle.max} 分 · 额外 ${signed(cycle.bonus)}` : (cycle.hasBonus ? '有额外加扣分' : '无额外加扣分')),
        cycle.days.map((day) => d.detailed
          ? h('div.day.tap', { role: 'button', onClick: () => app.push(timelinePage(day.date)) },
            h('span.d.num', day.date),
            h('span.f.num', `${day.score} − ${day.deduction}${day.bonus ? ` ${signed(day.bonus)}` : ''} =`, h('b', day.net)), icon('next', 'chev'))
          : h('div.day', h('span.d.num', day.date), h('span.f', '有数据'))));
      const head = h('div.row.tap.cyc', { role: 'button', 'aria-expanded': String(open), onClick: () => {
        if (expanded.has(cycle.index)) expanded.delete(cycle.index); else expanded.add(cycle.index);
        render();
      } },
        h('div', h('div.n.num', `周期 ${String(cycle.index + 1).padStart(2, '0')}`), h('div.s.num', `${shortDate(cycle.from)} – ${shortDate(cycle.to)} · ${d.percentage ? '百分比制' : '原始分制'}`)),
        heatGrid(cycle.days.slice().reverse()),
        s.word ? h('div.pct.word', s.word) : h('div.pct', { style: { color: tone(cycle.percent) } }, s.value, s.unit || ' 分'));
      list.append(h('div.sub', head, body));
    }
    clear(content,
      h('div.hero-head', h('div', h('div.title', '睡眠分数')), h('div.head-actions', refreshButton, aboutButton)),
      arc, metrics, timelineLink,
      h('div.sec', h('h3', '历史周期'), h('span', `共 ${d.cycles.length} 个`)), list,
      d.debug ? h('div.debug.num', `当前日期键 ${d.today} · ${d.cycles.reduce((n, c) => n + c.days.length, 0)} 个日期`) : null);
  }
  return {
    el, scroll,
    async show() {
      await act(load);
      refresh(false);
    },
    reload: () => act(load),
  };
}
