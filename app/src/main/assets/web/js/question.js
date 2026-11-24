import { h, icon, clear } from './dom.js';
import { call } from './native.js';
import { app } from './app.js';
import { act, alert, toast, iconButton, watchScroll } from './ui.js';
import { moonArt } from './art.js';

/** Question flow (reading, quiz, ending). Consecutive questions replace the content in place, like the old quiz motion. */
export function questionPage(startName, { notice = false } = {}) {
  let name = startName;
  let selection = -1;
  const titleEl = h('b', notice ? '睡时记录' : '任务与说明');
  const closeButton = iconButton('close', '关闭', () => close(), { plain: true });
  const bar = h('div.bar', closeButton, h('div.t', titleEl));
  const content = h('div.content');
  const scroll = h('div.scroll', content);
  const footer = h('div.footer');
  const el = h('div.page', bar, scroll, footer);
  watchScroll(scroll, bar);

  function close() {
    app.pop();
    if (notice) app.noticeDone();
  }
  function node() { return app.questions[name]; }
  function advance(next) {
    if (!app.questions[next]) return;
    name = next;
    selection = -1;
    if (app.motion) {
      content.style.transition = 'none';
      content.style.opacity = '0';
      content.style.transform = 'translateY(14px)';
      render();
      scroll.scrollTop = 0;
      requestAnimationFrame(() => requestAnimationFrame(() => {
        content.style.transition = 'opacity .26s var(--ease), transform .3s var(--ease)';
        content.style.opacity = '1';
        content.style.transform = 'none';
      }));
    } else { render(); scroll.scrollTop = 0; }
  }
  async function finish() {
    const result = await act(() => call('question.finish', { name }));
    if (!result) return;
    if (result.toast) toast(result.toast);
    if (result.action === 'next') advance(result.next);
    else if (result.action === 'close') {
      close();
      if (result.chat) app.openChat(result.chat);
    }
  }
  function render() {
    const q = node();
    closeButton.classList.toggle('hidden', q ? q.showCloseButton === false : false);
    bar.style.paddingLeft = q && q.showCloseButton === false ? '20px' : '';
    if (!q) {
      clear(content, h('div.glass.card', h('div.q-title', '找不到题目'), h('div.q-info', name)));
      clear(footer, h('button.btn.tonal', { onClick: close }, '退出'));
      return;
    }
    const kind = q.type === 'QUIZ' ? '选择题' : q.type === 'ENDING' ? '结局' : notice ? '初次使用' : '阅读';
    let info = q.infoText || '';
    if (name === '用户须知main' && !app.boot.publicKey) info = info.replace('默认使用内置的公开低额度 AI 服务（Catbee）', 'AI 对话需在设置中填写 API 密钥');
    const progress = q.showProgressBar ? Math.max(0, Math.min(100, Math.round((q.progressRatio || 0) * 100))) : null;
    const card = h('div.glass.card',
      h('span.chip.accent.q-kicker', kind),
      h('div.q-title', q.title || name),
      progress != null ? h('div.q-progress', h('span', `进度 ${progress}%`), h('div.bar-track', h('i', { style: { width: `${progress}%` } }))) : null,
      info ? h('div.q-info', info) : null,
      q.type === 'QUIZ' ? h('div.q-ask', q.question || '') : null,
      q.type === 'ENDING' && q.bonusPoints > 0 ? h('div.reward', `可获得 +${q.bonusPoints} 分`) : null);
    const options = q.type === 'QUIZ' ? h('div.glass.options', (q.options || []).map((option, i) =>
      h(`div.opt-row.tap${selection === i ? '.sel' : ''}`, { role: 'button', onClick: async () => {
        if (option.type === 'ERROR') { await alert('再试一次', option.errorMessage || '请重新选择'); return; }
        selection = i;
        render();
      } }, h('span.badge', String.fromCharCode(65 + i)), h('span.grow', option.text || ''), selection === i ? icon('check', 'accent') : null))) : null;
    clear(content, notice ? h('div', { style: { height: '120px', position: 'relative' } }, moonArt()) : null, card, options);
    if (q.type === 'QUIZ') clear(footer, h('button.btn', { disabled: selection < 0, onClick: () => {
      const option = (q.options || [])[selection];
      if (option) advance(option.linkedQuestionName);
    } }, '确认'));
    else if (q.type === 'ENDING') clear(footer, h('button.btn', { onClick: finish }, '完成'));
    else clear(footer, h('button.btn', { onClick: () => advance(q.linkedQuestionName) }, '继续'));
  }
  render();
  return {
    el,
    /** Back acts like the close button where one is shown; otherwise it is ignored, as before. */
    back() {
      const q = node();
      if (!q || q.showCloseButton !== false) close();
      return true;
    },
  };
}
