import { h, icon, clear, signed, minusSign, weekdayName } from './dom.js';
import { call } from './native.js';
import { app } from './app.js';
import { act, toast, segments } from './ui.js';
import { questionPage } from './question.js';
import { restPage } from './rest.js';
import { audioPage } from './audio.js';

const chipFor = (points) => h(`span.chip.${points < 0 ? 'minus' : 'plus'}`, minusSign(points));
function taskRow({ iconName, title, sub, extra, points, action, off = false, onClick }) {
  return h(`div.row${onClick ? '.tap' : ''}`, { onClick },
    h(`span.tile${off ? '.off' : ''}`, icon(iconName)),
    h('div.grow', h('div.t', { style: off ? { color: 'var(--ink-2)' } : null }, title), sub ? h('div.s', sub) : null, extra),
    points != null ? chipFor(points) : null, action);
}
const done = (label = '今日已完成') => h('span.pill.quiet', { 'aria-label': label }, icon('check', 'plus'));

export function tasksPage() {
  const content = h('div.content');
  const scroll = h('div.scroll', content);
  const compact = h('div.compact', '任务');
  const el = h('div.page', scroll, compact);
  scroll.addEventListener('scroll', () => compact.classList.toggle('on', scroll.scrollTop > 70), { passive: true });
  let section = 0;
  let from = 0;
  let slide = 0;
  let data = null;
  let archivedOpen = false;
  let oneTimeOpen = false;

  async function load() {
    const wanted = section;
    const next = await call('tasks.overview', { section: wanted });
    if (wanted !== section) return;
    data = next;
    render();
  }
  function pick(index) {
    from = section;
    slide = index > section ? 1 : -1;
    section = index;
    act(load);
  }
  const run = (fn) => act(async () => { await fn(); await load(); });

  function regular() {
    const d = data;
    const c = d.checkin;
    const feature = h('div.feature',
      h('div.row', { style: { padding: 0, minHeight: 0, alignItems: 'flex-start' } },
        h('div.grow', h('div.t', '每日打卡'), h('div.s', c.done ? `已连续打卡 ${c.streak} 天，今天已打卡` : `已连续打卡 ${c.streak} 天`)),
        c.done ? h('span.chip.plus', signed(c.points)) : h('button.pill.solid', { onClick: () => run(async () => toast(`已打卡 ${signed(await call('tasks.checkin'))} 分`)) },
          '打卡 ', h('span.num', signed(c.points)))),
      h('div.week', c.week.map((day) => h(`div${day.done ? '.done' : ''}${day.today ? '.today' : ''}`, h('i'), day.today ? '今天' : weekdayName(day.weekday)))));
    const s = d.suggestion;
    const chat = d.chat;
    const rests = d.rests.map((r) => {
      const label = r.long ? '长休息' : '短休息';
      const sub = r.long ? `${r.minutes} 分钟 · 22:00～04:00 可开始` : '30 秒 · 全天可开始';
      let action;
      if (r.pending) action = h('button.pill.solid', { onClick: () => app.push(restPage(r.key)) }, '继续');
      else if (r.done) action = done();
      else if (!r.available) action = h('span.pill.quiet', '未到时段');
      else action = h('button.pill', { onClick: () => app.push(restPage(r.key)) }, '开始');
      return taskRow({ iconName: r.long ? 'moon' : 'timer', title: label, sub: r.pending ? '计时中，继续计时或结算' : sub, points: r.points, action, off: r.done && !r.pending });
    });
    const list = h('div.glass.list',
      taskRow({ iconName: 'video', title: '查看早睡建议', sub: s.done ? '今天已完成' : '打开睡眠主题视频搜索', points: s.points, off: s.done,
        action: s.done ? done() : h('button.pill', { onClick: () => run(() => call('tasks.suggestion')) }, '查看') }),
      taskRow({ iconName: 'chat', title: '聊天', sub: `今日已计 ${chat.chars} 字，每 ${chat.perPoint} 字 1 分`,
        extra: h('div', { style: { display: 'flex', alignItems: 'center', gap: '10px' } },
          h('div.bar-track', { style: { flex: '1', maxWidth: '140px' } }, h('i', { style: { width: `${chat.max ? Math.min(100, chat.points / chat.max * 100) : 0}%` } })),
          h('span.num', { style: { fontSize: '12px', color: 'var(--ink-3)', marginTop: '10px' } }, `${chat.points} / ${chat.max} 分`)),
        action: h('button.pill', { onClick: () => app.tab(1) }, '去聊天') }),
      rests,
      taskRow({ iconName: 'music', title: '放松睡觉音频', sub: '离线播放 · 睡前放松', action: icon('next', 'chev'), onClick: () => app.push(audioPage()) }));
    const debug = d.debugTasks ? h('div.glass.list', taskRow({ iconName: 'code', title: '调试加扣分', sub: '记入当前小时',
      action: h('div', { style: { display: 'flex', gap: '6px' } },
        h('button.pill.small.danger', { onClick: () => run(() => call('tasks.debugBonus', { delta: -100 })) }, '−100'),
        h('button.pill.small', { onClick: () => run(() => call('tasks.debugBonus', { delta: 100 })) }, '+100')) })) : null;
    return [feature, list, debug];
  }
  function challenges() {
    const d = data;
    const cards = h('div.glass.list', d.challenges.map((c) => taskRow({ iconName: c.early ? 'moon' : 'spark', title: c.name,
      sub: c.description, extra: c.enabled ? null : h('div.s', { style: { color: 'var(--accent)' } }, c.label), points: c.reward,
      action: c.enabled ? h('button.pill', { onClick: () => app.push(questionPage(c.question)) }, '接取') : null })));
    const live = d.live.length ? [h('div.sec', h('h3', '已接取')), h('div.glass.list', d.live.map((t) => {
      let action, sub;
      if (t.status === 'IN_PROGRESS') {
        sub = `进行中 · ${t.description}`;
        action = t.early ? h('button.pill', { onClick: () => app.tab(0) }, '查看统计')
          : h('button.pill', { onClick: () => act(async () => app.openChat(await call('chat.forTask', { task: t.id }))) }, '继续对话');
      } else if (t.status === 'COMPLETED') {
        sub = `已完成 · ${t.description}`;
        action = h('button.pill.solid', { onClick: () => run(async () => toast(`已领取 ${await call('tasks.archive', { id: t.id })} 分`)) }, '领取奖励');
      } else {
        sub = `挑战失败 · ${t.description}`;
        action = h('button.pill', { onClick: () => run(() => call('tasks.archive', { id: t.id })) }, '确认');
      }
      return taskRow({ iconName: 'flag', title: t.name, sub, points: t.points, action });
    }))] : [];
    const archived = d.archived.length ? [h('div.glass.list',
      h('div.row.tap', { role: 'button', onClick: () => { archivedOpen = !archivedOpen; render(); } },
        h('div.grow', h('div.t', `${archivedOpen ? '收起' : '展开'}已归档挑战（${d.archived.length}）`)), icon(archivedOpen ? 'down' : 'next', 'chev')),
      archivedOpen ? d.archived.map((t) => h('div.row', h('div.grow', h('div.t', t.name), h('div.s.num', t.detail)))) : null)] : [];
    return [cards, ...live, ...archived];
  }
  function oneTime() {
    const o = data.oneTime;
    const full = o.used >= o.limit;
    return [
      h('div.glass.card', h('div.prog', h('div', h('div', { style: { fontSize: '15px', fontWeight: '600' } }, '今日完成'), h('div.s', { style: { fontSize: '12.5px', color: 'var(--ink-3)', marginTop: '3px' } }, `每天最多 ${o.limit} 项`)),
        h('b', o.used, h('small', ` / ${o.limit}`))), h('div.bar-track', h('i', { style: { width: `${o.used / o.limit * 100}%` } }))),
      o.done.length ? h('div.glass.list',
        h('div.row.tap', { role: 'button', onClick: () => { oneTimeOpen = !oneTimeOpen; render(); } },
          h('div.grow', h('div.t', `${oneTimeOpen ? '收起' : '展开'}已完成（${o.done.length}）`)), icon(oneTimeOpen ? 'down' : 'next', 'chev')),
        oneTimeOpen ? o.done.map((t) => taskRow({ iconName: 'check', title: t.name, sub: t.description, off: true, action: h('span.chip', '已完成') })) : null) : null,
      h('div.sec', h('h3', '可完成任务'), h('span', `${o.open.length} 项`)),
      h('div.glass.list', o.open.length ? o.open.map((t) => taskRow({ iconName: 'book', title: t.name, sub: t.description, points: t.points,
        action: full ? h('span.pill.quiet', '配额已满') : h('button.pill', { onClick: () => app.push(questionPage(t.question)) }, '开始') }))
        : h('div.empty', '全部完成了')),
    ];
  }
  function render() {
    const d = data;
    const body = h('div.section-body', section === 0 ? regular() : section === 1 ? challenges() : oneTime(),
      h('div.sim', h('span', '额外 +100 分对当前周期的影响'), h('b', d.simulation == null ? 'N/A' : `${signed(d.simulation)} 个百分点`)));
    if (slide && app.motion) {
      body.style.transform = `translateX(${slide * 48}px)`;
      body.style.opacity = '0';
      requestAnimationFrame(() => requestAnimationFrame(() => { body.style.transform = 'none'; body.style.opacity = '1'; }));
    }
    slide = 0;
    clear(content,
      h('div.hero-head', h('div', h('div.eyebrow', '今日加分'), h('div.title', '任务')),
        h('div.bonus', { class: d.bonus > 0 ? 'plus' : d.bonus < 0 ? 'minus' : '' }, minusSign(d.bonus))),
      segments(['常规', '挑战', '一次性'], section, pick, from),
      body);
    from = section;
  }
  return { el, scroll, show: () => act(load), reload: () => act(load) };
}
