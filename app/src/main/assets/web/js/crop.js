import { h, clear } from './dom.js';
import { call } from './native.js';
import { app } from './app.js';
import { act, toast, iconButton } from './ui.js';

/** Square crop with drag and pinch; saves a 128 px PNG like the native crop view did. */
export function cropPage(name, dataUrl) {
  const canvas = h('canvas');
  const area = h('div.crop', canvas);
  const bar = h('div.bar.scrolled', iconButton('close', '取消', () => app.pop(), { plain: true }), h('div.t', h('b', '裁剪头像'), h('span', name)));
  const save = h('button.btn', { disabled: true, onClick: () => act(saveCrop) }, '保存头像');
  const footer = h('div.footer', h('div.note', { style: { margin: '0 0 12px' } }, '拖动和双指缩放，保留方框内的画面。'), save);
  const el = h('div.page', bar, area, footer);
  const img = new Image();
  let W = 0, H = 0, dpr = 1, side = 0, fx = 0, fy = 0;
  let scale = 1, min = 1, x = 0, y = 0;
  const pointers = new Map();
  let pinch = null;

  function layout() {
    const r = area.getBoundingClientRect();
    if (!r.width || !img.naturalWidth) return;
    dpr = window.devicePixelRatio || 1;
    W = r.width; H = r.height;
    canvas.width = Math.round(W * dpr); canvas.height = Math.round(H * dpr);
    side = Math.min(W, H) * .84;
    fx = (W - side) / 2; fy = (H - side) / 2;
    min = Math.max(side / img.naturalWidth, side / img.naturalHeight);
    scale = min;
    x = (W - img.naturalWidth * scale) / 2; y = (H - img.naturalHeight * scale) / 2;
    draw();
  }
  function clamp() {
    x = Math.min(fx, Math.max(fx + side - img.naturalWidth * scale, x));
    y = Math.min(fy, Math.max(fy + side - img.naturalHeight * scale, y));
  }
  function draw() {
    const c = canvas.getContext('2d');
    c.setTransform(dpr, 0, 0, dpr, 0, 0);
    c.clearRect(0, 0, W, H);
    c.drawImage(img, x, y, img.naturalWidth * scale, img.naturalHeight * scale);
    c.fillStyle = 'rgba(0,0,0,.6)';
    c.beginPath(); c.rect(0, 0, W, H); c.rect(fx + side, fy, -side, side); c.fill('evenodd');
    c.strokeStyle = getComputedStyle(document.documentElement).getPropertyValue('--accent').trim() || '#aeb8ff';
    c.lineWidth = 2; c.strokeRect(fx, fy, side, side);
    c.setLineDash([4, 6]); c.strokeStyle = 'rgba(255,255,255,.35)'; c.lineWidth = 1;
    c.beginPath(); c.arc(fx + side / 2, fy + side / 2, side / 2, 0, Math.PI * 2); c.stroke(); c.setLineDash([]);
  }
  function zoom(factor, cx, cy) {
    const next = Math.min(min * 8, Math.max(min, scale * factor));
    const f = next / scale;
    x = cx + (x - cx) * f; y = cy + (y - cy) * f; scale = next;
    clamp(); draw();
  }
  area.addEventListener('pointerdown', (e) => { area.setPointerCapture(e.pointerId); pointers.set(e.pointerId, { x: e.clientX, y: e.clientY }); pinch = null; });
  area.addEventListener('pointermove', (e) => {
    const p = pointers.get(e.pointerId);
    if (!p) return;
    const rect = area.getBoundingClientRect();
    if (pointers.size === 1) { x += e.clientX - p.x; y += e.clientY - p.y; clamp(); draw(); }
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (pointers.size === 2) {
      const [a, b] = [...pointers.values()];
      const d = Math.hypot(a.x - b.x, a.y - b.y);
      if (pinch) zoom(d / pinch, (a.x + b.x) / 2 - rect.left, (a.y + b.y) / 2 - rect.top);
      pinch = d;
    }
  });
  const up = (e) => { pointers.delete(e.pointerId); pinch = null; };
  area.addEventListener('pointerup', up);
  area.addEventListener('pointercancel', up);
  area.addEventListener('wheel', (e) => { e.preventDefault(); const r = area.getBoundingClientRect(); zoom(e.deltaY < 0 ? 1.1 : 1 / 1.1, e.clientX - r.left, e.clientY - r.top); }, { passive: false });
  new ResizeObserver(layout).observe(area);
  img.onload = () => { save.disabled = false; layout(); };
  img.src = dataUrl;

  async function saveCrop() {
    const out = h('canvas', { width: 128, height: 128 });
    out.width = 128; out.height = 128;
    const c = out.getContext('2d');
    const k = 128 / side;
    c.drawImage(img, (x - fx) * k, (y - fy) * k, img.naturalWidth * scale * k, img.naturalHeight * scale * k);
    await call('avatar.save', { name, data: out.toDataURL('image/png') });
    app.pop();
    toast('头像已更新');
  }
  return { el };
}
