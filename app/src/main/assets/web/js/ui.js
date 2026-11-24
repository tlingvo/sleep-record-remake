import { h, icon, add } from './dom.js';
import { app } from './app.js';

const overlays = [];
const host = () => document.getElementById('app');
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

/** Mounts a dismissable layer (scrim + content). Closing runs the leave transition, then removes it. */
export function openLayer(build, { onClose, soft = false } = {}) {
  const layer = h(`div.layer${soft ? '.soft' : ''}`);
  const scrim = h('div.scrim');
  layer.append(scrim);
  let closed = false;
  const api = {
    layer,
    close(result) {
      if (closed) return;
      closed = true;
      layer.classList.remove('open');
      const i = overlays.indexOf(api);
      if (i >= 0) overlays.splice(i, 1);
      setTimeout(() => layer.remove(), app.motion ? 320 : 0);
      if (onClose) onClose(result);
    },
  };
  scrim.addEventListener('click', () => api.close(null));
  build(layer, api);
  host().append(layer);
  overlays.push(api);
  requestAnimationFrame(() => requestAnimationFrame(() => layer.classList.add('open')));
  return api;
}
export function closeTopOverlay() {
  const top = overlays[overlays.length - 1];
  if (!top) return false;
  top.close(null);
  return true;
}
export const hasOverlay = () => overlays.length > 0;
export function closeAllOverlays() { while (overlays.length) overlays[overlays.length - 1].close(null); }

export function dialog({ title, message = '', custom = null, positive = '确定', negative = null, danger = false }) {
  return new Promise((resolve) => {
    openLayer((layer, api) => {
      layer.append(h('div.dialog', { role: 'dialog', 'aria-label': title },
        h('h2', title),
        message ? h('p', message) : null,
        custom ? h('div.custom', custom) : null,
        h('div.actions',
          negative ? h('button.pill.quiet', { onClick: () => api.close(false) }, negative) : null,
          h(`button.pill.${danger ? 'danger' : 'solid'}.ok`, { onClick: () => api.close(true) }, positive))));
    }, { onClose: (r) => resolve(r === true) });
  });
}
export const alert = (title, message) => dialog({ title, message });
export const confirm = (title, message, positive = '确认', danger = false) => dialog({ title, message, positive, negative: '取消', danger });
export async function prompt({ title, value = '', hint = '', secret = false, multiline = false, positive = '确定', message = '' }) {
  const field = multiline ? h('textarea.field', { rows: 5, placeholder: hint }) : h('input.field', { type: secret ? 'password' : 'text', placeholder: hint });
  field.value = value;
  if (!multiline) field.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); field.closest('.dialog')?.querySelector('.ok')?.click(); }
  });
  setTimeout(() => { field.focus(); field.setSelectionRange?.(field.value.length, field.value.length); }, app.motion ? 260 : 0);
  const ok = await dialog({ title, message, custom: field, positive, negative: '取消' });
  return ok ? field.value : null;
}

/** Popover anchored to [anchor]. Items: { label, value?, sel?, danger?, sub? }. Resolves the picked value or null. */
export function menu(anchor, items) {
  return new Promise((resolve) => {
    openLayer((layer, api) => {
      const box = h('div.menu', { role: 'menu' }, items.map((item) => h(`button${item.sel ? '.sel' : ''}${item.danger ? '.danger' : ''}`,
        { role: 'menuitem', onClick: () => api.close(item.value ?? item.label) },
        h('span.grow', item.label, item.sub ? h('div.sub', item.sub) : null), item.sel ? icon('check') : null)));
      box.style.visibility = 'hidden';
      layer.append(box);
      requestAnimationFrame(() => {
        const r = anchor.getBoundingClientRect();
        const bw = box.offsetWidth, bh = box.offsetHeight, W = innerWidth, H = innerHeight;
        const left = Math.min(Math.max(16, r.right - bw), W - bw - 16);
        let top = r.bottom + 6, origin = 'top';
        if (top + bh > H - 16) { top = Math.max(16, r.top - bh - 6); origin = 'bottom'; }
        Object.assign(box.style, { left: `${left}px`, top: `${top}px`, transformOrigin: `${origin} right`, visibility: '' });
      });
    }, { soft: true, onClose: (v) => resolve(v ?? null) });
  });
}

let toastEl = null;
let toastTimer = 0;
export function toast(text) {
  if (!toastEl) { toastEl = h('div.toast', { role: 'status' }); host().append(toastEl); }
  toastEl.textContent = text;
  host().append(toastEl);
  requestAnimationFrame(() => toastEl.classList.add('on'));
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toastEl.classList.remove('on'), 2300);
}

/** Runs an action and reports failures the way the native app did: a dialog titled 操作未完成. */
export async function act(fn) {
  try { return await fn(); }
  catch (error) {
    await alert('操作未完成', (error && error.message) || '请重试');
    return undefined;
  }
}

export function toggle(on, onChange, { small = false, label = '', disabled = false } = {}) {
  const el = h(`button.tog${small ? '.small' : ''}${on ? '.on' : ''}${disabled ? '.disabled' : ''}`, { role: 'switch', 'aria-checked': String(on), 'aria-label': label });
  const set = (value) => { el.classList.toggle('on', value); el.setAttribute('aria-checked', String(value)); };
  el.addEventListener('click', async (e) => {
    e.stopPropagation();
    const next = !el.classList.contains('on');
    set(next);
    const accepted = await onChange(next);
    if (accepted === false) set(!next);
  });
  el.set = set;
  // Not `value`: <button> already owns a string `value` property, which would swallow a function.
  el.isOn = () => el.classList.contains('on');
  return el;
}
export function switchRow(label, on, onChange, { iconName, sub } = {}) {
  const tog = toggle(on, onChange, { label });
  const row = h('div.row.tap', { onClick: () => tog.click() },
    iconName ? h('span.sico', icon(iconName)) : null,
    h('div.grow', h('div.t', label), sub ? h('div.s', sub) : null), tog);
  row.toggle = tog;
  return row;
}
export function linkRow(label, { value = '', sub, iconName, onClick, danger = false } = {}) {
  const v = h('span.v', value ? h('span.num', value) : null, icon('next', 'chev'));
  const row = h('div.row.tap', { role: 'button', onClick: () => onClick && onClick(row, v) },
    iconName ? h('span.sico', icon(iconName)) : null,
    h('div.grow', h('div.t', { class: danger ? 'minus' : '' }, label), sub ? h('div.s', sub) : null), v);
  return row;
}
/** A setting row whose value opens a picker anchored to the value. */
export function selectRow(label, current, options, onPick, { iconName, sub } = {}) {
  const v = h('span.v', h('span', current), icon('down', 'chev'));
  const row = h('div.row.tap', { role: 'button', 'aria-label': `${label}，${current}`, onClick: async () => {
    const picked = await menu(v, options.map((o) => ({ label: o.label, value: o.value, sel: o.label === current })));
    if (picked != null) onPick(picked);
  } }, iconName ? h('span.sico', icon(iconName)) : null, h('div.grow', h('div.t', label), sub ? h('div.s', sub) : null), v);
  return row;
}
export function segments(labels, index, onPick, from = index) {
  let current = index;
  const knob = h('span.knob', { style: { width: `calc((100% - 8px) / ${labels.length})` } });
  const buttons = labels.map((label, i) => h(`button${i === index ? '.on' : ''}`, { role: 'tab', onClick: () => { if (i !== current) onPick(i); } }, label));
  const el = h('div.seg', { role: 'tablist' }, knob, buttons);
  knob.style.transform = `translateX(${from * 100}%)`;
  if (from !== index) requestAnimationFrame(() => requestAnimationFrame(() => { knob.style.transform = `translateX(${index * 100}%)`; }));
  el.select = (i) => { current = i; knob.style.transform = `translateX(${i * 100}%)`; buttons.forEach((b, j) => b.classList.toggle('on', j === i)); };
  return el;
}
export function iconButton(name, label, onClick, { plain = false } = {}) {
  return h(`button.iconbtn${plain ? '.plain' : ''}`, { 'aria-label': label, onClick }, icon(name));
}
export function longPress(el, fn) {
  let timer = 0, x = 0, y = 0;
  const cancel = () => clearTimeout(timer);
  el.addEventListener('pointerdown', (e) => { x = e.clientX; y = e.clientY; cancel(); timer = setTimeout(() => fn(e), 480); });
  el.addEventListener('pointermove', (e) => { if (Math.abs(e.clientX - x) > 8 || Math.abs(e.clientY - y) > 8) cancel(); });
  ['pointerup', 'pointercancel', 'pointerleave'].forEach((t) => el.addEventListener(t, cancel));
  el.addEventListener('contextmenu', (e) => { e.preventDefault(); cancel(); fn(e); });
}
/** Gives a top bar its frosted background once content scrolls under it. */
export function watchScroll(scroll, bar, threshold = 4) {
  const update = () => bar.classList.toggle('scrolled', scroll.scrollTop > threshold);
  scroll.addEventListener('scroll', update, { passive: true });
  update();
}
/** Standard pushed page: frosted top bar with back, title and optional actions over a scrolling body. */
export function pageShell({ title, sub = '', actions = [], closeIcon = false, footer = null }) {
  const titleEl = h('b', title);
  const subEl = h('span', sub);
  const back = iconButton(closeIcon ? 'close' : 'back', '返回', () => app.pop(), { plain: true });
  const bar = h('div.bar', back, h('div.t', titleEl, subEl), actions);
  const content = h('div.content');
  const scroll = h('div.scroll', content);
  const el = h('div.page', bar, scroll, footer);
  watchScroll(scroll, bar);
  return { el, bar, scroll, content, back,
    setTitle(t, s) { titleEl.textContent = t; if (s != null) subEl.textContent = s; subEl.classList.toggle('hidden', !subEl.textContent); } };
}
export function copyText(text) {
  const area = h('textarea', { style: { position: 'fixed', opacity: '0', top: '0' } });
  area.value = text;
  document.body.append(area);
  area.select();
  let ok = false;
  try { ok = document.execCommand('copy'); } catch (_) { ok = false; }
  area.remove();
  if (!ok && navigator.clipboard) navigator.clipboard.writeText(text).catch(() => {});
}
export { wait, add };
