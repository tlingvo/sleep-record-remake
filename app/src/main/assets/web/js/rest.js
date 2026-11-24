import { h, clear, mmss, signed, svg } from './dom.js';
import { call, on } from './native.js';
import { app } from './app.js';
import { act, toast, pageShell } from './ui.js';
import { moonArt } from './art.js';

/** Rest countdown. While it runs the page cannot be left (the native side also pins the task). */
export function restPage(key) {
  const shell = pageShell({ title: key === 'last_rest_time' ? '长休息' : '短休息' });
  let info = null;
  let offset = 0;
  let result = null;
  let timer = 0;
  let settling = false;
  const ring = svg('svg', { viewBox: '0 0 236 236' });
  const track = svg('circle', { cx: 118, cy: 118, r: 108, fill: 'none', 'stroke-width': 6 }, ring);
  track.style.stroke = 'var(--track)';
  const arc = svg('circle', { cx: 118, cy: 118, r: 108, fill: 'none', 'stroke-width': 6, 'stroke-linecap': 'round' }, ring);
  arc.style.stroke = 'var(--accent)';
  arc.style.transition = 'stroke-dashoffset .3s linear';
  const length = 2 * Math.PI * 108;
  arc.setAttribute('stroke-dasharray', length.toFixed(1));
  const clock = h('div.clock');
  const status = h('div.s');
  const button = h('div');
  const running = () => !result && info && info.running && info.running.end > 0;

  function tick() {
    if (!running()) return;
    const remaining = info.running.end - (Date.now() + offset);
    const total = (info.running.seconds || info.seconds) * 1000;
    if (remaining <= 0) {
      clock.textContent = '00:00';
      arc.style.strokeDashoffset = length.toFixed(1);
      settle();
      return;
    }
    clock.textContent = mmss(Math.ceil(remaining / 1000));
    arc.style.strokeDashoffset = (length * (1 - remaining / total)).toFixed(1);
  }
  async function settle() {
    if (settling) return;
    settling = true;
    try {
      const done = await call('rest.settle');
      if (done) { result = done; render(); }
      else setTimeout(() => { settling = false; tick(); }, 1000);
    } catch (error) { settling = false; }
  }
  async function load() {
    info = await call('rest.get', { key });
    offset = info.now - Date.now();
    if (info.running && info.running.end <= info.now) { await settle(); return; }
    render();
  }
  function render() {
    clearInterval(timer);
    const finished = !!result;
    const live = running();
    const shown = finished ? result : (info.running || info);
    shell.setTitle(shown.key === 'last_rest_time' ? '长休息' : '短休息');
    shell.back.classList.toggle('hidden', live);
    clock.className = finished ? 'clock done' : 'clock';
    clock.textContent = finished ? `${signed(result.points)} 分` : mmss(info.seconds);
    status.textContent = finished ? '奖励已记入当前小时' : live ? '计时中 · 可关闭屏幕' : `准备好后开始计时 · 完成可得 ${signed(info.points)} 分`;
    arc.style.strokeDashoffset = finished ? '0' : live ? arc.style.strokeDashoffset : '0';
    arc.style.stroke = finished ? 'var(--plus)' : 'var(--accent)';
    clear(button,
      finished ? h('button.btn', { onClick: () => app.pop() }, '返回')
        : live ? null
          : h('button.btn', { onClick: () => act(async () => {
            const started = await call('rest.start', { key });
            offset = started.now - Date.now();
            info.running = { ...started, seconds: info.seconds };
            render();
          }) }, '开始计时'));
    clear(shell.content, h('div.rest',
      h('div.art', moonArt()),
      h('div.ring', ring, h('div.in', clock, h('div.unit', finished ? '已入账' : live ? '剩余' : info.seconds >= 60 ? `共 ${Math.round(info.seconds / 60)} 分钟` : `共 ${info.seconds} 秒`))),
      h('h2', finished ? '休息完成' : '放下手机，休息一下'), status, button));
    if (live) { tick(); timer = setInterval(tick, 250); }
  }
  const off = on('resume', () => { if (running()) act(load); });
  return {
    el: shell.el,
    show: () => act(load),
    back() {
      if (running()) { toast('休息计时结束前请留在此页面，可以关闭屏幕'); return true; }
      return false;
    },
    destroy() { clearInterval(timer); off(); },
  };
}
