const NS = 'http://www.w3.org/2000/svg';

/** `h('div.row.tap', { onClick }, child, ...)`; children may be strings, nodes, arrays or null. */
export function h(spec, ...rest) {
  let attrs = {};
  if (rest.length && rest[0] && typeof rest[0] === 'object' && !(rest[0] instanceof Node) && !Array.isArray(rest[0])) attrs = rest.shift();
  const [tag, ...classes] = spec.split('.');
  const el = document.createElement(tag || 'div');
  if (classes.length) el.className = classes.join(' ');
  for (const [key, value] of Object.entries(attrs)) {
    if (value == null || value === false) continue;
    if (key === 'class') el.className = [el.className, value].filter(Boolean).join(' ');
    else if (key === 'style' && typeof value === 'object') Object.assign(el.style, value);
    else if (key.startsWith('on') && typeof value === 'function') el.addEventListener(key.slice(2).toLowerCase(), value);
    else if (key === 'html') el.innerHTML = value;
    else if (key === 'text') el.textContent = value;
    else if (['value', 'disabled', 'checked', 'placeholder', 'type', 'rows', 'maxLength'].includes(key)) el[key] = value;
    else el.setAttribute(key, value === true ? '' : value);
  }
  add(el, rest);
  return el;
}
export function add(el, children) {
  for (const child of [children].flat(Infinity)) {
    if (child == null || child === false || child === true) continue;
    el.append(child instanceof Node ? child : String(child));
  }
  return el;
}
export function clear(el, ...children) { el.replaceChildren(); return add(el, children); }
export function svg(tag, attrs = {}, parent) {
  const el = document.createElementNS(NS, tag);
  for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v);
  if (parent) parent.appendChild(el);
  return el;
}

const PATHS = {
  stats: '<path d="M4 20h16"/><path d="M7 16v-5M12 16V5M17 16V8"/>',
  chat: '<path d="M20 12.5a7.5 7.5 0 0 1-11 6.6L4.5 20l1-4A7.5 7.5 0 1 1 20 12.5Z"/>',
  tasks: '<rect x="5" y="3.5" width="14" height="17" rx="3.5"/><path d="M9 12l2.2 2.2L15.5 10"/>',
  settings: '<circle cx="12" cy="12" r="3"/><path d="M12 3.5v2M12 18.5v2M3.5 12h2M18.5 12h2M6 6l1.4 1.4M16.6 16.6 18 18M6 18l1.4-1.4M16.6 7.4 18 6"/>',
  refresh: '<path d="M19.5 12a7.5 7.5 0 1 1-2.2-5.3"/><path d="M19.5 4.5v4h-4"/>',
  info: '<circle cx="12" cy="12" r="8"/><path d="M12 11v5"/><path d="M12 7.6v.1"/>',
  back: '<path d="M14.5 5.5 8 12l6.5 6.5"/>',
  close: '<path d="M6.5 6.5l11 11M17.5 6.5l-11 11"/>',
  next: '<path d="M9.5 6l6 6-6 6"/>',
  down: '<path d="M7 10l5 5 5-5"/>',
  menu: '<path d="M5 7h14M5 12h14M5 17h9"/>',
  more: '<circle cx="6" cy="12" r=".9"/><circle cx="12" cy="12" r=".9"/><circle cx="18" cy="12" r=".9"/>',
  check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
  send: '<path d="M12 19V5M6 11l6-6 6 6"/>',
  timeline: '<path d="M4 7h10M4 12h16M4 17h7"/><circle cx="17" cy="7" r="2"/><circle cx="14" cy="17" r="2"/>',
  video: '<path d="M4 7.5h16v10a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z"/><path d="M10 11l4 2.5-4 2.5z"/>',
  moon: '<path d="M19 14.5A7.5 7.5 0 0 1 9.5 5a7.5 7.5 0 1 0 9.5 9.5Z"/>',
  timer: '<circle cx="12" cy="13" r="7"/><path d="M12 9.5V13l2.5 1.5M10 3.5h4"/>',
  music: '<path d="M9 18V6l10-2v12"/><circle cx="6.5" cy="18" r="2.5"/><circle cx="16.5" cy="16" r="2.5"/>',
  flag: '<path d="M6 21V4.5"/><path d="M6 5h11l-2 4 2 4H6"/>',
  spark: '<path d="M12 3.5l1.8 4.7 4.7 1.8-4.7 1.8L12 16.5l-1.8-4.7L5.5 10l4.7-1.8z"/><path d="M18 15.5l.8 2 2 .8-2 .8-.8 2-.8-2-2-.8 2-.8z"/>',
  person: '<circle cx="12" cy="8" r="3.5"/><path d="M5 20a7 7 0 0 1 14 0"/>',
  bell: '<path d="M12 4v1.5M6 10a6 6 0 0 1 12 0c0 5 2 6 2 6H4s2-1 2-6"/><path d="M10 19.5a2 2 0 0 0 4 0"/>',
  alarm: '<circle cx="12" cy="13" r="7"/><path d="M12 9.5V13l2.5 1.5M5 4.5 3 6.5M19 4.5l2 2"/>',
  download: '<path d="M12 4v11M7.5 10.5 12 15l4.5-4.5"/><path d="M5 19.5h14"/>',
  upload: '<path d="M12 15V4M7.5 8.5 12 4l4.5 4.5"/><path d="M5 19.5h14"/>',
  palette: '<path d="M12 3.5a8.5 8.5 0 1 0 0 17c1.2 0 1.8-.8 1.6-1.8-.3-1.3.6-2.2 1.9-2.2h1.8a3.2 3.2 0 0 0 3.2-3.2A9 9 0 0 0 12 3.5Z"/><circle cx="7.8" cy="11" r="1"/><circle cx="10.5" cy="7.6" r="1"/><circle cx="14.8" cy="7.8" r="1"/>',
  code: '<path d="M8.5 7 4 12l4.5 5M15.5 7 20 12l-4.5 5"/>',
  calendar: '<rect x="4" y="5.5" width="16" height="14.5" rx="3"/><path d="M4 10h16M8.5 3.5v4M15.5 3.5v4"/>',
  layers: '<path d="M12 4 3.5 8.5 12 13l8.5-4.5z"/><path d="M3.5 13 12 17.5l8.5-4.5"/>',
  play: '<path d="M8.5 5.8v12.4L18.5 12z"/>',
  pause: '<path d="M9 6v12M15 6v12"/>',
  plus: '<path d="M12 5.5v13M5.5 12h13"/>',
  trash: '<path d="M5 7h14M10 7V5h4v2M7 7l1 12.5h8L17 7"/>',
  edit: '<path d="M4.5 19.5 5.5 15 15.8 4.7a2 2 0 0 1 2.8 2.8L8.3 17.8z"/>',
  copy: '<rect x="8" y="8" width="11.5" height="11.5" rx="2.5"/><path d="M15.5 8V6.5A2 2 0 0 0 13.5 4.5h-7a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2H8"/>',
  image: '<rect x="4" y="5" width="16" height="14" rx="3"/><circle cx="9" cy="10" r="1.6"/><path d="M5 17l4.5-4 3.5 3 2.5-2 4 3.5"/>',
  trophy: '<path d="M8 4.5h8v5a4 4 0 0 1-8 0z"/><path d="M8 6.5H5a3 3 0 0 0 3 4M16 6.5h3a3 3 0 0 1-3 4M12 13.5v3M8.5 20h7M9.5 16.5h5"/>',
  book: '<path d="M5 5.5A2 2 0 0 1 7 4h12v14H7a2 2 0 0 0-2 2z"/><path d="M5 20V5.5M9 8h6"/>',
  shield: '<path d="M12 3.5 5 6v5.5c0 4 3 7.5 7 9 4-1.5 7-5 7-9V6z"/>',
  key: '<circle cx="8" cy="15" r="3.5"/><path d="M10.5 12.5 18 5M15 8l2.5 2.5M16.5 6.5 19 9"/>',
  lock: '<rect x="5" y="10.5" width="14" height="10" rx="3"/><path d="M8 10.5V8a4 4 0 0 1 8 0v2.5"/>',
};
export function icon(name, cls = '') {
  const el = document.createElementNS(NS, 'svg');
  el.setAttribute('viewBox', '0 0 24 24');
  el.setAttribute('class', `i ${cls}`.trim());
  el.setAttribute('aria-hidden', 'true');
  el.innerHTML = PATHS[name] || '';
  return el;
}

export const signed = (n) => (n > 0 ? `+${n}` : `${n}`);
export const minusSign = (n) => (n < 0 ? `−${-n}` : signed(n));
/** Same wording as the Kotlin durationText: "42秒" or "3分5秒". */
export function durationText(ms) {
  const s = Math.max(0, Math.floor(ms / 1000));
  return s < 60 ? `${s}秒` : `${Math.floor(s / 60)}分${s % 60}秒`;
}
export const mmss = (sec) => `${String(Math.floor(sec / 60)).padStart(2, '0')}:${String(Math.floor(sec % 60)).padStart(2, '0')}`;
export const pad2 = (n) => String(n).padStart(2, '0');
export const clockHour = (h) => `${pad2(h)}:00`;
export const shortDate = (d) => d ? d.slice(5).replace('-', '.') : '';
export const weekdayName = (calendarDay) => ['日', '一', '二', '三', '四', '五', '六'][(calendarDay - 1 + 7) % 7];
export function hm(ms) {
  const minutes = Math.round(ms / 60000);
  return { h: Math.floor(minutes / 60), m: minutes % 60 };
}
export function timeOf(ts) {
  const d = new Date(ts);
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
}
