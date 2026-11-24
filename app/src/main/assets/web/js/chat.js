import { h, icon, clear, timeOf } from './dom.js';
import { call, on } from './native.js';
import { app } from './app.js';
import { act, dialog, confirm, prompt, menu, toast, toggle, iconButton, longPress, openLayer, watchScroll, copyText } from './ui.js';
import { defaultAvatar } from './art.js';
import { chatSettingsPage } from './chatSettings.js';

export function avatarFor(name, map) {
  const box = h('span.avatar', { 'aria-label': `${name} 头像` });
  const letter = () => clear(box, (name || '?').slice(0, 1));
  const url = map ? map[name] : undefined;
  if (url === undefined) box.append(defaultAvatar());
  else if (!url) letter();
  else box.append(h('img', { src: url, alt: '', onError: letter }));
  return box;
}

export function chatPage() {
  let conv = null;
  let auto = null;
  let streaming = null;
  let streamBubble = null;
  const drafts = new Map();

  const titleEl = h('div.t');
  const subText = h('span');
  const dot = h('span.dot');
  const head = h('div.chathead', iconButton('menu', '会话', () => openDrawer()), h('div.grow', titleEl, h('div.s', dot, subText)),
    iconButton('more', '选项', () => conv && app.push(chatSettingsPage(conv.id))));
  const msgs = h('div.msgs');
  const extra = h('div');
  const spacer = h('div.chat-spacer');
  const scroll = h('div.scroll', msgs, extra, spacer);
  watchScroll(scroll, head);

  const roleLabel = h('span');
  const roleButton = h('button.role', { onClick: pickRole }, roleLabel, icon('down'));
  const aiToggle = toggle(false, (value) => { auto = value; renderHead(); }, { small: true, label: '请求AI' });
  const aiWrap = h('label.ai', '请求AI', aiToggle);
  const meta = h('div.meta', roleButton, aiWrap);
  const field = h('textarea', { rows: 1, placeholder: '输入消息', 'aria-label': '输入消息' });
  const sendButton = h('button.send', { 'aria-label': '发送', onClick: send }, icon('send'));
  const composer = h('div.composer', meta, h('div.send-row', field, sendButton));
  const el = h('div.page', head, scroll, composer);

  const nearBottom = () => scroll.scrollHeight - scroll.clientHeight - scroll.scrollTop < 90;
  const toEnd = () => { scroll.scrollTop = scroll.scrollHeight; };
  function fit() {
    field.style.height = 'auto';
    field.style.height = `${Math.min(field.scrollHeight + 2, 150)}px`;
    sendButton.disabled = !!streaming || !field.value.trim();
    const rect = composer.getBoundingClientRect();
    const hidden = composer.classList.contains('hidden');
    spacer.style.height = `${hidden ? 110 : Math.max(120, window.innerHeight - rect.top + 16)}px`;
  }
  field.addEventListener('input', () => { if (conv) drafts.set(conv.id, field.value); fit(); });
  new ResizeObserver(() => requestAnimationFrame(() => { const follow = nearBottom(); fit(); if (follow) toEnd(); })).observe(composer);

  async function load(id = conv ? conv.id : '', { toBottom = false } = {}) {
    const next = await call('chat.get', { id });
    if (!conv || next.id !== conv.id) { toBottom = true; auto = null; field.value = drafts.get(next.id) || ''; }
    conv = next;
    if (auto == null) auto = conv.aiEnabled && !!conv.assistant;
    if (conv.streaming != null) streaming = { id: conv.id, text: conv.streaming };
    else if (streaming && streaming.id === conv.id) streaming = null;
    render(toBottom);
  }
  function renderHead() {
    titleEl.textContent = conv.title;
    const available = conv.aiEnabled && !!conv.assistant;
    const replies = conv.task || (available && auto);
    dot.classList.toggle('on', !!replies && !(conv.task && conv.completed));
    subText.textContent = conv.task ? (conv.completed ? '任务会话 · 已完成' : '任务会话')
      : conv.subType === 'SELF_CHAT' ? '离线 · 自我对话'
        : available ? `${conv.assistant} · ${auto ? '自动请求 AI 回复' : '不自动请求回复'}` : '不想睡觉 · 未启用 AI';
  }
  function bubbleOptions(index, message, anchor) {
    if (streaming) return;
    menu(anchor, [{ label: '复制', value: 'copy' }, { label: '编辑', value: 'edit' }, { label: '删除', value: 'delete', danger: true }]).then(async (choice) => {
      if (choice === 'copy') { copyText(message.content); toast('已复制'); }
      if (choice === 'edit') {
        const value = await prompt({ title: '编辑消息', value: message.content, multiline: true });
        if (value != null && value.trim()) act(async () => { await call('chat.editMessage', { id: conv.id, index, content: value }); await load(); });
      }
      if (choice === 'delete' && await confirm('删除消息', '删除所选消息？', '删除', true))
        act(async () => { await call('chat.deleteMessage', { id: conv.id, index }); await load(); });
    });
  }
  function messageView(m, index, roles) {
    const system = (roles[m.role] && roles[m.role].type === 'system') || m.role === 'system';
    if (system && !conv.showSystem) return null;
    const time = conv.showTime && m.ts > 0 ? timeOf(m.ts) : '';
    if (system) {
      const box = h('div.sys', h('div.sys-k', '系统', time ? ` · ${time}` : ''), m.content);
      longPress(box, () => bubbleOptions(index, m, box));
      return box;
    }
    const own = m.role === conv.selected;
    const bubble = h('div.b', m.content);
    longPress(bubble, () => bubbleOptions(index, m, bubble));
    return h(`div.m${own ? '.me' : ''}`, conv.avatars ? avatarFor(m.role, conv.avatarMap) : null,
      h('div.col', h('div.who', m.role), bubble, time ? h('div.tm', time) : null));
  }
  function streamView() {
    const name = conv.assistant || '助手';
    streamBubble = h('div.b', streaming.text ? streaming.text : h('span.typing', { 'aria-label': '正在等待回复…' }, h('i'), h('i'), h('i')));
    return h('div.m', conv.avatars ? avatarFor(name, conv.avatarMap) : null, h('div.col', h('div.who', name), streamBubble));
  }
  function render(toBottom = false) {
    const follow = toBottom || nearBottom();
    renderHead();
    const roles = Object.fromEntries(conv.roles.map((r) => [r.name, r]));
    streamBubble = null;
    clear(msgs, conv.messages.map((m, i) => messageView(m, i, roles)), streaming && streaming.id === conv.id ? streamView() : null);
    const starter = !conv.messages.length && !conv.task && !conv.subType
      ? h('div.glass.starter', h('b', '从一句话开始'), h('div', ['很晚了但是我现在不想睡觉', '今天过得怎么样', '给我讲个故事吧'].map((s) =>
        h('button.pill', { onClick: () => { field.value = s; drafts.set(conv.id, s); fit(); field.focus(); } }, s)))) : null;
    clear(extra, starter, conv.task && conv.completed ? h('div.done-banner', '挑战已完成，请在任务页领取奖励。') : null);
    const closed = conv.task && conv.completed;
    composer.classList.toggle('hidden', closed);
    meta.classList.toggle('hidden', conv.task);
    roleLabel.textContent = `以「${conv.selected}」发送`;
    roleButton.disabled = !!streaming || !conv.usable.length;
    const available = conv.aiEnabled && !!conv.assistant;
    aiToggle.set(available && auto === true);
    aiWrap.classList.toggle('off', !available || !!streaming);
    field.placeholder = closed ? '该任务会话已完成' : '输入消息';
    fit();
    if (follow) requestAnimationFrame(toEnd);
  }
  async function pickRole() {
    const name = await menu(roleButton, conv.usable.map((n) => ({ label: n, sel: n === conv.selected })));
    if (name != null) act(async () => { await call('chat.role', { id: conv.id, name }); await load(); });
  }
  async function send() {
    const text = field.value.trim();
    if (!text || streaming || !conv) return;
    const id = conv.id;
    const result = await act(() => call('chat.send', { id, text, auto: auto === true }));
    if (result === undefined) return;
    field.value = '';
    drafts.delete(id);
    if (result.streaming) streaming = { id, text: '' };
    await act(() => load(id, { toBottom: true }));
  }

  on('chat.start', ({ id }) => { if (conv && conv.id === id) { streaming = { id, text: '' }; render(true); } });
  on('chat.chunk', ({ id, text }) => {
    if (!conv || conv.id !== id) return;
    const follow = nearBottom();
    streaming = { id, text };
    if (streamBubble) streamBubble.textContent = text; else render();
    if (follow) toEnd();
  });
  on('chat.done', async (event) => {
    if (streaming && streaming.id === event.id) streaming = null;
    if (conv) await act(() => load(conv.id));
    if (event.completed) dialog({ title: '挑战完成', message: '请到任务页领取奖励。' });
    else if (event.error) dialog({ title: event.title || '未收到 AI 回复', message: event.error });
  });

  async function openDrawer() {
    let data = await act(() => call('chat.list', { id: conv ? conv.id : '' }));
    if (!data) return;
    let doneOpen = false;
    const list = h('div.scroll');
    const layer = openLayer((root) => {
      const panel = h('div.drawer', h('div.head', h('b', '会话'), h('span', '切换或新建对话')), list);
      let x0 = 0, y0 = 0;
      panel.addEventListener('touchstart', (e) => { x0 = e.touches[0].clientX; y0 = e.touches[0].clientY; }, { passive: true });
      panel.addEventListener('touchmove', (e) => {
        const dx = e.touches[0].clientX - x0, dy = e.touches[0].clientY - y0;
        if (dx < -48 && Math.abs(dx) > Math.abs(dy) * 1.4) layer.close();
      }, { passive: true });
      root.append(panel);
    });
    const pick = (id) => act(async () => {
      layer.close();
      if (conv && conv.id === id) return;
      await call('chat.select', { id });
      await load(id, { toBottom: true });
    });
    const rowFor = (item, deletable) => {
      const active = conv && item.id === conv.id;
      const more = deletable ? iconButton('more', `管理 ${item.title}`, async (e) => {
        e.stopPropagation();
        const choice = await menu(more, [{ label: '删除会话', value: 'delete', danger: true }]);
        if (choice !== 'delete' || !await confirm('删除会话', `删除「${item.title}」及其消息？`, '删除', true)) return;
        await act(async () => {
          await call('chat.delete', { id: item.id });
          if (active) { conv = null; await load(''); }
          data = await call('chat.list', { id: conv ? conv.id : '' });
          paint();
        });
      }, { plain: true }) : (active ? icon('check', 'accent') : null);
      return h(`div.conv.tap${active ? '.on' : ''}`, { role: 'button', onClick: () => pick(item.id) },
        h('span.mk', item.task ? '任' : item.title.slice(0, 1)), h('div.grow', h('div.t', item.title), h('div.s', item.status)), more);
    };
    function paint() {
      const users = data.items.filter((c) => !c.task);
      const todo = data.items.filter((c) => c.task && !c.completed);
      const finished = data.items.filter((c) => c.task && c.completed);
      const create = (kind) => act(async () => {
        const id = await call('chat.create', { kind });
        layer.close();
        await load(id, { toBottom: true });
      });
      const templates = [
        { label: '普通对话', value: 'USER', sub: '与 AI 助手聊天' },
        { label: '角色扮演', value: 'ROLEPLAY', sub: '先写好角色设定，再开始对话' },
        { label: '自我对话', value: 'SELF_CHAT', sub: '离线，不请求 AI' },
      ];
      const newButton = h('button.pill', { onClick: async () => {
        const kind = await menu(newButton, templates);
        if (kind) create(kind === 'USER' ? '' : kind);
      } }, icon('plus'), '新建会话', icon('down'));
      clear(list,
        h('div.new-kinds', newButton),
        h('div.sec', h('h3', '用户会话')), users.map((c) => rowFor(c, true)),
        h('div.sec', h('h3', '任务会话')), todo.length ? todo.map((c) => rowFor(c, false)) : h('div.empty', { style: { padding: '14px 10px', textAlign: 'left' } }, '暂无进行中的任务会话'),
        finished.length ? [h('div.conv.tap', { role: 'button', onClick: () => { doneOpen = !doneOpen; paint(); } },
          h('div.grow', h('div.t', { style: { color: 'var(--ink-2)' } }, `${doneOpen ? '收起' : '展开'}已完成会话（${finished.length}）`)), icon(doneOpen ? 'down' : 'next', 'chev')),
        doneOpen ? finished.map((c) => rowFor(c, false)) : null] : null);
    }
    paint();
  }

  return {
    el,
    show: () => act(() => load(conv ? conv.id : '')),
    openDrawer,
    open: (id) => act(async () => { await call('chat.select', { id }); await load(id, { toBottom: true }); }),
    reset() { conv = null; streaming = null; drafts.clear(); },
    resize: fit,
  };
}
