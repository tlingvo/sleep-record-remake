import { h, icon } from './dom.js';
import { call, on, native, useBackend } from './native.js';
import { app } from './app.js';
import { closeTopOverlay, hasOverlay, closeAllOverlays, toast, dialog } from './ui.js';
import { drawStars } from './art.js';
import { statsPage } from './stats.js';
import { chatPage } from './chat.js';
import { tasksPage } from './tasks.js';
import { settingsPage } from './settings.js';
import { questionPage } from './question.js';
import { gatePage } from './gate.js';
import { cropPage } from './crop.js';
import { restPage } from './rest.js';

const root = document.getElementById('app');
const stack = [];
const names = ['统计', '不想睡觉', '任务', '设置'];
const glyphs = ['stats', 'chat', 'tasks', 'settings'];
let tabs = [];
let tabIndex = 0;
let mode = 'boot';
let pager, stackEl, dock, fade, gate = null;
let dockButtons = [];
let pendingRest = null;

app.setTheme = (dark) => { document.documentElement.dataset.theme = dark ? 'night' : 'dawn'; };
function setInsets(i = {}) {
  const s = document.documentElement.style;
  s.setProperty('--sat', `${i.top || 0}px`);
  s.setProperty('--sab', `${i.bottom || 0}px`);
  s.setProperty('--sal', `${i.left || 0}px`);
  s.setProperty('--sar', `${i.right || 0}px`);
  document.body.classList.toggle('typing-ime', !!i.ime);
  updateDock();
  if (tabs[1]) tabs[1].resize();
}
const activePage = () => (stack.length ? stack[stack.length - 1] : tabs[tabIndex]);
app.current = activePage;

function updateDock() {
  if (!dock) return;
  const away = stack.length > 0 || mode !== 'main' || document.body.classList.contains('typing-ime');
  dock.classList.toggle('away', away);
  fade.classList.toggle('hidden', away);
}
function selectTab(i, { show = true } = {}) {
  if (i < 0 || i >= tabs.length) return;
  tabIndex = i;
  pager.classList.toggle('settle', app.motion);
  pager.style.transform = `translateX(${-i * 100}%)`;
  dockButtons.forEach((b, j) => { b.classList.toggle('on', j === i); b.setAttribute('aria-selected', String(j === i)); });
  if (show && tabs[i].show) return tabs[i].show();
  return Promise.resolve();
}

app.push = (page) => {
  const below = activePage();
  page.el.classList.add('page');
  stack.push(page);
  stackEl.append(page.el);
  if (app.motion) {
    page.el.classList.add('enter');
    page.el.getBoundingClientRect();
    page.el.classList.add('anim');
    if (below) below.el.classList.add('anim');
    requestAnimationFrame(() => { page.el.classList.remove('enter'); if (below) below.el.classList.add('leave'); });
  } else if (below) below.el.classList.add('leave');
  updateDock();
  if (page.show) page.show();
};
app.pop = () => {
  const page = stack.pop();
  if (!page) return;
  if (page.destroy) page.destroy();
  const below = activePage();
  if (app.motion) {
    page.el.classList.add('anim', 'back-out');
    setTimeout(() => page.el.remove(), 330);
  } else page.el.remove();
  if (below) below.el.classList.remove('leave');
  updateDock();
  if (below && below.show) below.show();
};
function popAll() {
  while (stack.length) {
    const page = stack.pop();
    if (page.destroy) page.destroy();
    page.el.remove();
  }
  tabs.forEach((t) => t.el.classList.remove('leave'));
  updateDock();
}
app.tab = (i) => { popAll(); selectTab(i); };
app.openChat = (id) => { popAll(); selectTab(1, { show: false }); tabs[1].open(id); };

function back() {
  if (closeTopOverlay()) return true;
  const top = stack[stack.length - 1];
  if (top) {
    if (top.exitOnBack) return false;
    if (top.back && top.back()) return true;
    app.pop();
    return true;
  }
  if (tabIndex !== 0) { selectTab(0); return true; }
  return false;
}
window.app = { back };

/** Tells native the first real frame is on screen, so the cached launch image can fade out. */
function painted() {
  requestAnimationFrame(() => requestAnimationFrame(() => call('app.painted').catch(() => {})));
}
let captureTimer = 0;
const capturable = () => mode === 'main' && !stack.length && !hasOverlay() && tabIndex === 0 &&
  tabs[0].scroll.scrollTop < 4 && !document.body.classList.contains('typing-ime') && !document.querySelector('.toast.on, .iconbtn.spin');
window.app.capturable = capturable;
/** Refreshes the launch image once the stats tab has been still for a moment. */
app.scheduleCapture = () => {
  clearTimeout(captureTimer);
  captureTimer = setTimeout(() => { if (capturable()) call('app.capture').catch(() => {}); }, 1200);
};
async function enterMain() {
  if (mode === 'main') return;
  const first = mode === 'boot';
  mode = 'main';
  if (first) dock.style.transition = 'none';
  updateDock();
  if (first) requestAnimationFrame(() => requestAnimationFrame(() => { dock.style.transition = ''; }));
  call('app.ready').catch(() => {});
  if (pendingRest) { const key = pendingRest; pendingRest = null; app.push(restPage(key)); painted(); return; }
  await selectTab(tabIndex);
  painted();
}
function showGate() {
  if (gate) return;
  mode = 'gate';
  popAll();
  gate = gatePage(() => { gate = null; popAll(); mode = 'gate-done'; enterMain(); });
  app.push(gate);
}
async function route() {
  const permitted = await call('app.usage').catch(() => false);
  if (permitted) enterMain(); else showGate();
}
app.noticeDone = async () => {
  await call('app.noticeDone').catch(() => {});
  mode = 'boot';
  route();
};

function buildShell() {
  tabs = [statsPage(), chatPage(), tasksPage(), settingsPage()];
  pager = h('div.pager', tabs.map((t) => h('div.tab', t.el)));
  stackEl = h('div.stack');
  fade = h('div.fade-bottom');
  dockButtons = names.map((name, i) => h('button', { role: 'tab', 'aria-label': name, onClick: () => { if (i === tabIndex) return; selectTab(i); } }, icon(glyphs[i]), h('span', name)));
  dock = h('nav.dock.away', { role: 'tablist' }, dockButtons);
  root.append(pager, fade, dock, stackEl);
  pager.style.transform = 'translateX(0)';
  dockButtons[0].classList.add('on');
  enableSwipe();
}

function enableSwipe() {
  let sx = 0, sy = 0, t0 = 0, W = 1, tracking = false, dragging = false, edge = false;
  pager.addEventListener('touchstart', (e) => {
    if (mode !== 'main' || stack.length || hasOverlay() || e.touches.length > 1) { tracking = false; return; }
    const t = e.touches[0];
    sx = t.clientX; sy = t.clientY; t0 = e.timeStamp; W = pager.clientWidth || 1;
    tracking = !e.target.closest('input, textarea, .composer, [data-noswipe]');
    dragging = false;
    // On the chat tab, swiping toward 统计 opens the conversation list instead of leaving the tab.
    edge = tabIndex === 1;
  }, { passive: true });
  pager.addEventListener('touchmove', (e) => {
    if (!tracking) return;
    const t = e.touches[0];
    const dx = t.clientX - sx, dy = t.clientY - sy;
    if (!dragging) {
      if (Math.abs(dy) > 10 && Math.abs(dy) >= Math.abs(dx)) { tracking = false; return; }
      if (!(Math.abs(dx) > 14 && Math.abs(dx) > Math.abs(dy) * 1.4)) return;
      if (edge && dx > 0) { tracking = false; tabs[1].openDrawer(); return; }
      dragging = true;
      pager.classList.remove('settle');
    }
    e.preventDefault();
    const resist = (tabIndex === 0 && dx > 0) || (tabIndex === tabs.length - 1 && dx < 0);
    pager.style.transform = `translateX(${-tabIndex * W + (resist ? dx / 3 : dx)}px)`;
  }, { passive: false });
  const end = (e) => {
    if (!dragging) { tracking = false; return; }
    dragging = false; tracking = false;
    const dx = e.changedTouches[0].clientX - sx;
    const velocity = dx / Math.max(1, e.timeStamp - t0);
    let next = tabIndex;
    if ((dx < -W * .22 || velocity < -.55) && tabIndex < tabs.length - 1) next += 1;
    else if ((dx > W * .22 || velocity > .55) && tabIndex > 0) next -= 1;
    selectTab(next, { show: next !== tabIndex });
  };
  pager.addEventListener('touchend', end);
  pager.addEventListener('touchcancel', end);
}

on('insets', setInsets);
on('theme', (dark) => app.setTheme(dark));
on('toast', (text) => toast(text));
on('error', (e) => dialog({ title: (e && e.title) || '操作未完成', message: (e && e.message) || '请重试' }));
on('photo', (p) => { if (p && p.data) app.push(cropPage(p.name, p.data)); });
on('imported', (r) => {
  closeAllOverlays();
  popAll();
  tabs[1].reset();
  selectTab(0);
  toast(r && r.invalid ? '导入完成，部分评分参数已使用默认值' : '旧版备份已导入');
});
on('resume', (r) => {
  if (!r) return;
  if (mode === 'gate' && r.permitted && gate) { gate = null; popAll(); mode = 'gate-done'; enterMain(); return; }
  if (mode === 'main' && !r.permitted) { showGate(); return; }
  const page = activePage();
  if (mode === 'main' && page && page.show) page.show();
});
document.addEventListener('contextmenu', (e) => { if (!e.target.closest('input, textarea')) e.preventDefault(); });

async function start() {
  if (!native.real) { const { mock } = await import('./mock.js'); useBackend(mock); }
  const boot = await call('app.boot');
  app.boot = boot;
  app.motion = boot.motion !== false;
  app.questions = boot.questions || {};
  if (!app.motion) document.body.classList.add('no-motion');
  app.setTheme(boot.dark);
  setInsets(boot.insets || {});
  drawStars(document.getElementById('sky'));
  buildShell();
  if (boot.rest && boot.rest.end > boot.rest.now) pendingRest = boot.rest.key;
  if (boot.notice) { mode = 'notice'; app.push(questionPage('用户须知main', { notice: true })); painted(); }
  else if (boot.permitted) enterMain();
  else { showGate(); painted(); }
  if (!native.real) preview();
}
/** Desktop preview only: ?tab=2&page=rest jumps straight to a screen for visual checks. */
async function preview() {
  const q = new URLSearchParams(location.search);
  if (q.has('tab')) selectTab(Number(q.get('tab')));
  const page = q.get('page');
  if (!page) return;
  const pages = {
    timeline: async () => (await import('./timeline.js')).timelinePage('2026-11-24'),
    rest: async () => restPage('last_rest_time'),
    audio: async () => (await import('./audio.js')).audioPage(),
    player: async () => (await import('./audio.js')).playerPage('放松2(少女)-今晚的月亮很圆，风也.mp3'),
    question: async () => questionPage('功能介绍1'),
    chatSettings: async () => (await import('./chatSettings.js')).chatSettingsPage('c1'),
    dev: async () => (await import('./dev.js')).devPage(),
  };
  setTimeout(async () => {
    if (page === 'drawer') tabs[1].openDrawer();
    else if (page === 'dialog') dialog({ title: '导出备份', message: '备份包含使用历史、会话内容及 API 密钥。请选择妥善保管的位置。', positive: '选择位置', negative: '取消' });
    else if (pages[page]) app.push(await pages[page]());
  }, 300);
}
start().catch((error) => {
  root.append(h('div.content', { style: { paddingTop: '80px' } }, h('div.title', '启动失败'), h('p', { style: { marginTop: '12px', color: 'var(--ink-2)' } }, String(error && error.message || error))));
});
