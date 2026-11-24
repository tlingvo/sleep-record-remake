import { h, icon, clear } from './dom.js';
import { call } from './native.js';
import { app } from './app.js';
import { act, dialog, confirm, prompt, menu, switchRow, linkRow, pageShell, toggle } from './ui.js';
import { avatarFor } from './chat.js';

export function chatSettingsPage(id) {
  const shell = pageShell({ title: '会话设置' });
  let conv = null;
  let archivedOpen = false;
  let historyOpen = false;
  const load = async () => { conv = await call('chat.get', { id }); render(); };
  const run = (fn) => act(async () => { await fn(); await load(); });

  async function avatarMenu(name, anchor) {
    const choice = await menu(anchor, [{ label: '设置头像', value: 'set' }, { label: '恢复默认头像', value: 'reset' }]);
    if (choice === 'set') act(() => call('avatar.pick', { name }));
    if (choice === 'reset') run(() => call('avatar.reset', { name }));
  }
  async function roleOptions(role, anchor) {
    const second = role.archived ? '启用角色' : role.type === 'assistant' ? '改为用户' : '设为助手';
    const choice = await menu(anchor, [{ label: '编辑名称', value: 'rename' },
      { label: '角色提示词', value: 'prompt', sub: role.prompt ? '已设置 · 聊天时自动随附' : '未设置' },
      { label: second, value: 'toggle' }, { label: '删除角色', value: 'delete', danger: true }]);
    if (choice === 'rename') {
      const name = await prompt({ title: '编辑角色名', value: role.name });
      if (name != null) run(() => call('role.save', { id, old: role.name, name }));
    }
    if (choice === 'prompt') {
      const text = await prompt({ title: `「${role.name}」的提示词`, value: role.prompt || '', multiline: true, positive: '保存',
        hint: role.type === 'assistant' ? '例如：你是一只温柔的猫，说话简短，会劝人早点睡。' : '例如：我是高三学生，明天早上有考试。',
        message: '本会话中有这个角色时，这段文字会作为系统提示词随每次 AI 请求发送；归档后不再发送。留空即清除。' });
      if (text != null) run(() => call('role.prompt', { id, name: role.name, prompt: text }));
    }
    if (choice === 'toggle') run(() => call('role.toggle', { id, name: role.name }));
    if (choice === 'delete') {
      const withMessages = toggle(false, () => true, { label: '同时删除该角色的消息' });
      const row = h('div.row.tap', { onClick: () => withMessages.click() }, h('div.grow', h('div.t', '同时删除该角色的消息')), withMessages);
      if (await dialog({ title: `删除 ${role.name}`, message: '保留消息时，该角色会进入归档。', custom: row, positive: '删除', negative: '取消', danger: true }))
        run(() => call('role.delete', { id, name: role.name, messages: withMessages.isOn() }));
    }
  }
  function roleRow(role, label) {
    const face = avatarFor(role.name, conv.avatarMap);
    const faceButton = h('button', { 'aria-label': `${role.name} 头像设置`, style: { borderRadius: '20px' }, onClick: (e) => { e.stopPropagation(); avatarMenu(role.name, faceButton); } }, face);
    face.style.marginTop = '0';
    const row = h('div.row.tap', { role: 'button', onClick: () => roleOptions(role, row.querySelector('.v')) },
      faceButton, h('div.grow', h('div.t', role.name), role.prompt ? h('div.s.clip', { title: role.prompt }, `提示词：${role.prompt.replace(/\s+/g, ' ')}`) : null),
      h('span.v', h('span', label), icon('next', 'chev')));
    return row;
  }
  function render() {
    if (conv.task) {
      clear(shell.content, h('div.glass.list',
        linkRow('重置到初始', { iconName: 'refresh', sub: '恢复初始开场消息并清空本会话', onClick: async () => {
          if (await confirm('重置任务会话', '恢复初始开场消息并清空本会话。挑战奖励仍按任务状态处理。', '重置'))
            act(async () => { await call('chat.reset', { id }); app.pop(); });
        } }),
        linkRow('放弃挑战', { iconName: 'flag', danger: true, sub: '结束挑战并删除这条任务会话', onClick: async () => {
          if (await confirm('放弃挑战', '将结束挑战并删除这条任务会话。', '放弃', true))
            act(async () => { await call('chat.abandon', { id }); app.pop(); app.tab(2); });
        } })));
      return;
    }
    const canRequest = conv.aiEnabled && !!conv.assistant && conv.streaming == null;
    const general = h('div.glass.list',
      linkRow('会话名称', { value: conv.title, onClick: async () => {
        const title = await prompt({ title: '会话名称', value: conv.title });
        if (title != null) run(() => call('chat.rename', { id, title }));
      } }),
      switchRow('显示消息时间', conv.showTime, (v) => run(() => call('chat.flag', { id, key: 'showMessageTime', value: v }))),
      switchRow('显示系统角色', conv.showSystem, (v) => run(() => call('chat.flag', { id, key: 'showSystemMessages', value: v }))),
      switchRow('启用 AI', conv.aiEnabled, (v) => run(() => call('chat.flag', { id, key: 'aiEnabled', value: v }))),
      h('div.row', h('div.grow', h('div.t', '立即请求回复'), h('div.s', conv.assistant ? `由「${conv.assistant}」回复当前对话` : '需要先设置助手角色')),
        h('button.pill', { disabled: !canRequest, onClick: () => act(async () => { await call('chat.manual', { id }); app.pop(); }) }, '请求')));
    const active = conv.roles.filter((r) => r.type !== 'system' && !r.archived);
    const archived = conv.roles.filter((r) => r.type !== 'system' && r.archived);
    const roles = h('div.glass.list',
      conv.roles.some((r) => r.type === 'system') ? h('div.row', h('span.sico', icon('shield')), h('div.grow', h('div.t', { style: { color: 'var(--ink-2)' } }, 'system · 系统'), h('div.s', '保留角色'))) : null,
      active.map((r) => roleRow(r, r.type === 'assistant' ? '助手' : '用户')),
      archived.length ? h('div.row.tap', { role: 'button', onClick: () => { archivedOpen = !archivedOpen; render(); } },
        h('div.grow', h('div.t', `已归档角色 (${archived.length})`)), h('span.v', h('span', archivedOpen ? '收起' : '展开'), icon(archivedOpen ? 'down' : 'next', 'chev'))) : null,
      archivedOpen ? archived.map((r) => roleRow(r, '已归档')) : null,
      h('div.row', h('button.pill', { onClick: async () => {
        const name = await prompt({ title: '添加角色' });
        if (name != null) run(() => call('role.save', { id, old: '', name }));
      } }, icon('plus'), '新建角色')));
    const history = conv.history.length ? h('div.glass.list',
      h('div.row.tap', { role: 'button', onClick: () => { historyOpen = !historyOpen; render(); } },
        h('div.grow', h('div.t', `使用过的角色 (${conv.history.length})`)), h('span.v', h('span', historyOpen ? '收起' : '展开'), icon(historyOpen ? 'down' : 'next', 'chev'))),
      historyOpen ? conv.history.map((name) => {
        const face = avatarFor(name, conv.avatarMap);
        face.style.marginTop = '0';
        const existing = conv.roles.find((r) => r.name === name);
        const present = existing && !existing.archived;
        const add = present
          ? h('span.pill.small.quiet', { 'aria-label': `${name} 已在本会话中` }, '已添加')
          : h('button.pill.small', { onClick: () => run(() => call('role.historyAdd', { id, name })) }, existing ? '恢复' : '添加');
        return h('div.row', face, h('div.grow', h('div.t', name), present ? h('div.s', '已在本会话中') : existing ? h('div.s', '已归档') : null),
          add,
          h('button.pill.small.danger', { onClick: () => run(() => call('role.historyRemove', { name })) }, '删除'));
      }) : null) : null;
    clear(shell.content, general,
      h('div.sec', h('h3', '角色列表'), h('span', '点头像换图，点整行管理')), roles,
      history ? [h('div.sec', h('h3', '历史角色')), history] : null,
      h('button.btn.danger', { style: { marginTop: '22px' }, onClick: async () => {
        if (await confirm('清空消息', '清空此会话的全部消息？', '清空', true)) run(() => call('chat.clear', { id }));
      } }, '清空消息'));
  }
  return { el: shell.el, show: () => act(load) };
}
