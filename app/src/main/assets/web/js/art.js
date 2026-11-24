import { svg, clockHour } from './dom.js';

let uid = 0;
const nextId = (p) => `${p}${++uid}`;
function rng(seed) { let s = seed * 9301 + 49297; return () => (s = (s * 9301 + 49297) % 233280) / 233280; }

/** Sparse, seeded star field for the shared sky layer. */
export function drawStars(target, seed = 7) {
  const el = svg('svg', { viewBox: '0 0 390 520', preserveAspectRatio: 'xMidYMin slice' }, target);
  const r = rng(seed);
  for (let i = 0; i < 78; i++) {
    const big = r() > .93;
    svg('circle', { cx: (r() * 390).toFixed(1), cy: (r() * 520).toFixed(1), r: big ? 1.3 : (.35 + r() * .6).toFixed(2),
      fill: r() > .8 ? '#cfd5ff' : '#ffffff', opacity: (big ? .85 : .18 + r() * .45).toFixed(2) }, el);
  }
  return el;
}

/** Crescent over a calm sea: used on the first-run, permission, rest and player pages. */
export function moonArt() {
  const glow = nextId('mg'), fill = nextId('mf'), mask = nextId('mm');
  const wrap = document.createElement('div');
  wrap.className = 'moon';
  wrap.innerHTML = `<svg viewBox="0 0 320 200" width="100%" height="100%" preserveAspectRatio="xMidYMid meet" aria-hidden="true">
  <defs>
    <radialGradient id="${glow}"><stop offset="0" stop-color="#fff2d2" stop-opacity=".42"/><stop offset="1" stop-color="#fff2d2" stop-opacity="0"/></radialGradient>
    <linearGradient id="${fill}" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#fbf7ff"/><stop offset=".6" stop-color="#d9defd"/><stop offset="1" stop-color="#9eb0e4"/></linearGradient>
    <mask id="${mask}"><rect width="320" height="200" fill="#fff"/><circle cx="183" cy="62" r="35" fill="#000"/></mask>
  </defs>
  <circle cx="164" cy="82" r="78" fill="url(#${glow})"/>
  <circle cx="164" cy="82" r="38" fill="url(#${fill})" mask="url(#${mask})"/>
  <g fill="#c9d4ff">
    <path d="M70 46l2 5 5 2-5 2-2 5-2-5-5-2 5-2z" opacity=".85"/>
    <path d="M246 34l1.4 3.4 3.4 1.4-3.4 1.4-1.4 3.4-1.4-3.4-3.4-1.4 3.4-1.4z" opacity=".7"/>
    <path d="M262 110l1 2.5 2.5 1-2.5 1-1 2.5-1-2.5-2.5-1 2.5-1z" opacity=".55"/>
    <circle cx="104" cy="104" r="1.4" opacity=".6"/><circle cx="220" cy="78" r="1.1" opacity=".5"/><circle cx="128" cy="30" r="1" opacity=".55"/>
  </g>
  <g fill="none" stroke-linecap="round">
    <path d="M28 154c46-26 86 10 132-6s82-28 132-4" stroke="#8fa3dd" stroke-opacity=".55" stroke-width="1.6"/>
    <path d="M46 170c40-18 80 8 120-4s76-22 112-2" stroke="#8fa3dd" stroke-opacity=".34" stroke-width="1.4"/>
    <path d="M74 184c32-10 62 4 92-2s56-12 82 0" stroke="#8fa3dd" stroke-opacity=".2" stroke-width="1.2"/>
  </g></svg>`;
  return wrap;
}

/** Default role avatar; same artwork as the app's vector drawable. */
export function defaultAvatar() {
  const g = nextId('ag'), c = nextId('ac');
  const wrap = document.createElement('span');
  wrap.innerHTML = `<svg viewBox="0 0 96 96" aria-hidden="true"><defs><linearGradient id="${g}" x1=".15" y1=".05" x2=".85" y2=".95">
  <stop offset="0" stop-color="#9aa8ff"/><stop offset=".55" stop-color="#6e7be6"/><stop offset="1" stop-color="#4a55b8"/></linearGradient>
  <clipPath id="${c}"><circle cx="48" cy="48" r="48"/></clipPath></defs>
  <circle cx="48" cy="48" r="48" fill="url(#${g})"/><g clip-path="url(#${c})"><path d="M16 98C16 76.5 30.5 63 48 63S80 76.5 80 98Z" fill="#f2f4ff" opacity=".92"/></g>
  <circle cx="48" cy="40.5" r="15.5" fill="#f2f4ff" opacity=".95"/><path d="M72.6 15.2a9 9 0 1 0 7.4 13.4 7 7 0 1 1-7.4-13.4z" fill="#fff3c8"/></svg>`;
  return wrap.firstElementChild;
}

/**
 * Night arc: one segment per sleep hour. Moonlight = recorded idle time, amber = phone use (share of the hour),
 * empty track = no record yet. The moon marks the current moment while tonight is in progress.
 */
export function nightArc(night, now) {
  const W = 358, H = 236, cx = W / 2, cy = 222, R = 158;
  const el = svg('svg', { viewBox: `0 0 ${W} ${H}`, role: 'img', 'aria-label': '夜间使用弧线' });
  const slots = night.slots || [];
  const n = Math.max(slots.length, 1);
  const pt = (f, r = R) => { const a = Math.PI * (1 - f); return [cx + r * Math.cos(a), cy - r * Math.sin(a)]; };
  const arc = (f0, f1, r = R) => {
    const [x0, y0] = pt(f0, r), [x1, y1] = pt(f1, r);
    return `M${x0.toFixed(2)} ${y0.toFixed(2)} A${r} ${r} 0 0 1 ${x1.toFixed(2)} ${y1.toFixed(2)}`;
  };
  const stroke = (d, color, opacity = 1, width = 14) => {
    const p = svg('path', { d, fill: 'none', 'stroke-width': width, 'stroke-linecap': 'round' }, el);
    p.style.stroke = color; p.style.strokeOpacity = opacity;
    return p;
  };
  const outer = svg('path', { d: arc(0, 1, R + 16), fill: 'none', 'stroke-width': 1, 'stroke-dasharray': '1 5' }, el);
  outer.style.stroke = 'var(--ink-4)';
  for (let i = 1; i < n; i++) {
    const [a0, b0] = pt(i / n, R + 11), [a1, b1] = pt(i / n, R + 19);
    const t = svg('line', { x1: a0, y1: b0, x2: a1, y2: b1, 'stroke-width': 1.2, 'stroke-linecap': 'round' }, el);
    t.style.stroke = 'var(--ink-4)';
  }
  const gap = .006;
  let moonAt = null;
  slots.forEach((slot, i) => {
    const f0 = i / n + gap, f1 = (i + 1) / n - gap;
    stroke(arc(f0, f1), 'var(--track)');
    if (slot.at > now) return;
    const running = now < slot.end;
    const end = running ? f0 + (f1 - f0) * Math.min(1, (now - slot.at) / (slot.end - slot.at)) : f1;
    if (running && night.today) moonAt = end;
    if (slot.usage == null) return;
    if (end - f0 > .001) stroke(arc(f0, end), 'var(--accent)', .55);
    const share = Math.min(1, slot.usage / 3600000);
    if (slot.usage >= 60000) {
      const span = Math.max((f1 - f0) * share, .012);
      stroke(arc(f0, Math.min(f0 + span, f1)), 'var(--use)', .55 + .45 * share);
    }
  });
  if (slots.length) {
    const label = (text, f, anchor) => {
      const [x, y] = pt(f, R);
      const t = svg('text', { x: x.toFixed(1), y: (y + 26).toFixed(1), 'text-anchor': anchor, 'font-family': 'Outfit', 'font-size': 11 }, el);
      t.style.fill = 'var(--ink-3)';
      t.textContent = text;
    };
    label(clockHour(slots[0].hour), 0, 'start');
    label(clockHour(new Date(slots[slots.length - 1].end).getHours()), 1, 'end');
  }
  if (moonAt != null) {
    const [mx, my] = pt(moonAt, R);
    const halo = nextId('halo');
    const defs = svg('defs', {}, el);
    const g = svg('radialGradient', { id: halo }, defs);
    svg('stop', { offset: '0', 'stop-color': '#fff4d8', 'stop-opacity': '.55' }, g);
    svg('stop', { offset: '1', 'stop-color': '#fff4d8', 'stop-opacity': '0' }, g);
    svg('circle', { cx: mx, cy: my, r: 30, fill: `url(#${halo})` }, el);
    svg('circle', { cx: mx, cy: my, r: 11, fill: '#fff1cf' }, el);
    const bite = svg('circle', { cx: mx + 5, cy: my - 4, r: 9 }, el);
    bite.style.fill = 'var(--sky-top)';
    const [lx, ly] = pt(moonAt, R + 30);
    const d = new Date(now);
    const t = svg('text', { x: lx, y: ly, 'text-anchor': 'middle', 'font-family': 'Outfit', 'font-size': 12.5, 'font-weight': 500 }, el);
    t.style.fill = 'var(--use)';
    t.textContent = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  }
  return el;
}

/** Days × sleep hours grid; each cell is the stored usage of that hour. */
export function heatGrid(days) {
  const wrap = document.createElement('div');
  wrap.className = 'heat';
  const rows = Math.max(1, ...days.map((d) => d.sleep.length));
  wrap.style.gridTemplateRows = `repeat(${rows}, 5px)`;
  for (const day of days) {
    for (let i = 0; i < rows; i++) {
      const usage = day.sleep[i];
      const cell = document.createElement('i');
      if (usage == null) cell.style.background = 'var(--track)';
      else if (usage < 60000) { cell.style.background = 'var(--accent)'; cell.style.opacity = '.32'; }
      else { cell.style.background = 'var(--use)'; cell.style.opacity = usage < 600000 ? '.45' : usage < 1800000 ? '.72' : '1'; }
      wrap.append(cell);
    }
  }
  return wrap;
}
