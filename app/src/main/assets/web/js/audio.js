import { h, icon, clear, mmss } from './dom.js';
import { call, on } from './native.js';
import { app } from './app.js';
import { act, pageShell } from './ui.js';
import { moonArt } from './art.js';

export function audioPage() {
  const shell = pageShell({ title: '放松睡觉音频', sub: '离线播放' });
  return {
    el: shell.el,
    show: () => act(async () => {
      const tracks = await call('audio.tracks');
      clear(shell.content,
        h('div.glass.list', tracks.length ? tracks.map((file) => h('div.row.tap', { role: 'button', onClick: () => app.push(playerPage(file)) },
          h('span.tile', icon('music')), h('div.grow', h('div.t', file.replace(/\.[^.]+$/, ''))), h('span.pill.small', icon('play'), '播放')))
          : h('div.empty', '暂无音频文件')),
        h('div.note', '音频由 Qwen3-TTS 生成'));
    }),
  };
}

export function playerPage(file) {
  const shell = pageShell({ title: '播放器' });
  let state = null;
  let poll = 0;
  let dragging = false;
  const fill = h('i', { style: { width: '0%' } });
  const dot = h('span.dot', { style: { left: '0%' } });
  const seek = h('div.seek', h('div.bar-track', fill), dot);
  const now = h('span');
  const total = h('span');
  const play = h('button.play', { 'aria-label': '播放', onClick: () => act(async () => { state = await call('player.toggle'); paint(); }) }, icon('play'));
  const message = h('div.note');
  function paint() {
    if (!state) return;
    const ratio = state.duration ? state.position / state.duration : 0;
    if (!dragging) { fill.style.width = `${ratio * 100}%`; dot.style.left = `${ratio * 100}%`; }
    now.textContent = mmss(state.position / 1000);
    total.textContent = mmss(state.duration / 1000);
    play.disabled = !state.prepared;
    clear(play, icon(state.playing ? 'pause' : 'play'));
    play.setAttribute('aria-label', state.playing ? '暂停' : '播放');
    message.textContent = state.error || '';
  }
  const at = (e) => { const r = seek.getBoundingClientRect(); return Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)); };
  seek.addEventListener('pointerdown', (e) => { dragging = true; seek.setPointerCapture(e.pointerId); const f = at(e); fill.style.width = `${f * 100}%`; dot.style.left = `${f * 100}%`; });
  seek.addEventListener('pointermove', (e) => { if (!dragging) return; const f = at(e); fill.style.width = `${f * 100}%`; dot.style.left = `${f * 100}%`; });
  seek.addEventListener('pointerup', (e) => { if (!dragging) return; dragging = false; act(async () => { state = await call('player.seek', { fraction: at(e) }); paint(); }); });
  // The app releases playback when it goes to the background; showing the page again reopens the track.
  const off = on('player.released', () => { clearInterval(poll); state = null; });
  return {
    el: shell.el,
    show: () => act(async () => {
      state = await call('player.open', { name: file });
      clear(shell.content, h('div.player',
        h('div', { style: { height: '210px', position: 'relative' } }, moonArt()),
        h('h2', file.replace(/\.[^.]+$/, '')), seek, h('div.time', now, total), play, message));
      paint();
      clearInterval(poll);
      poll = setInterval(async () => { try { state = await call('player.state'); paint(); } catch (_) { /* page closing */ } }, 250);
    }),
    destroy() { clearInterval(poll); off(); call('player.release').catch(() => {}); },
  };
}
