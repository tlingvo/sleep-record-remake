// Desktop preview only: stands in for SleepNative with sample data. Never loaded inside the app.
const H = 3600000;
const params = new URLSearchParams(location.search);
const base = new Date(); base.setHours(2, 47, 0, 0);
const now = base.getTime();
const at = (h) => { const d = new Date(now); if (h >= 12) d.setDate(d.getDate() - 1); d.setHours(h, 0, 0, 0); return d.getTime(); };
const ymd = (t) => { const d = new Date(t); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; };
const today = ymd(at(23));
const back = (n) => ymd(at(23) - n * 24 * H);
const usage = [18, 42, 6, 9, null, null];
const slots = [23, 0, 1, 2, 3, 4].map((h, i) => ({ at: at(h), end: at(h) + H, hour: h, usage: usage[i] == null ? null : usage[i] * 60000, has: usage[i] != null }));
const day = (date, score, deduction, bonus, sleep) => ({ date, score, deduction, bonus, net: score - deduction + bonus, sleep: sleep.map((m) => (m == null ? null : m * 60000)) });
const cycles = [
  { index: 0, from: back(8), to: back(6), percent: -88, net: -740, min: -810, max: 1080, bonus: 70, hasBonus: true, notStarted: false,
    days: [day(back(6), 120, 300, 0, [30, 60, 60, 40, 10, 0]), day(back(7), 60, 280, 70, [60, 50, 40, 30, 0, 0]), day(back(8), 80, 270, 0, [40, 60, 30, 20, 20, 0])] },
  { index: 1, from: back(5), to: back(3), percent: 34, net: 420, min: -810, max: 1080, bonus: 50, hasBonus: true, notStarted: false,
    days: [day(back(3), 330, 40, 0, [0, 12, 0, 0, 0, 0]), day(back(4), 300, 60, 50, [20, 2, 0, 0, 0, 0]), day(back(5), 280, 90, 0, [26, 18, 0, 0, 0, 0])] },
  { index: 2, from: back(2), to: today, percent: -48, net: -150, min: -810, max: 900, bonus: 70, hasBonus: true, notStarted: false,
    days: [day(today, 190, 120, 20, usage), day(back(1), 300, 160, 50, [36, 20, 0, 0, 4, 0]), day(back(2), 260, 300, 0, [60, 60, 22, 0, 0, 0])] },
];
const questions = {
  '用户须知main': { name: '用户须知main', title: '欢迎', type: 'INFO', showCloseButton: false, linkedQuestionName: '功能介绍1',
    infoText: '欢迎使用睡时记录！\n\n数据隐私说明：\n• 本应用会获取您的手机使用情况数据\n• 所有数据仅存储在您的设备本地' },
  '功能介绍1': { name: '功能介绍1', title: '睡眠分数怎么算', type: 'QUIZ', showCloseButton: true, showProgressBar: true, progressRatio: .5,
    infoText: '睡眠时段内，每个空闲分钟得 1 分；用手机的分钟按当时的费率扣分，越晚扣得越多。', question: '凌晨 3 点刷 10 分钟手机，和 23 点刷 10 分钟相比？',
    options: [{ text: '扣分更多', type: 'CONDITION', linkedQuestionName: '功能介绍2' }, { text: '一样多', type: 'ERROR', errorMessage: '越接近清晨，每分钟扣分越高。' }] },
  '功能介绍2': { name: '功能介绍2', title: '答对了', type: 'ENDING', bonusPoints: 10, linkedQuestionName: 'return', infoText: '扣分率从睡眠开始的 1 倍，逐小时升到上限。' },
};
const chat = {
  id: 'c1', title: '深夜不想睡', task: false, completed: false, subType: '', status: '普通对话', showSystem: true, showTime: true, aiEnabled: true,
  assistant: '助手', selected: '我', usable: ['system', '我', '助手'],
  roles: [{ name: 'system', type: 'system' }, { name: '我', type: 'user' }, { name: '助手', type: 'assistant', active: true }],
  messages: [
    { role: 'system', content: '本会话的字数计入聊天任务', ts: now - 16 * 60000 },
    { role: '助手', content: '已经两点半啦。今晚你用手机一共七十多分钟，再撑下去明天会很难受的。', ts: now - 15 * 60000 },
    { role: '我', content: '我知道……但是现在一点都不困', ts: now - 7 * 60000 },
    { role: '助手', content: '那先放下手机，闭眼听一段助眠音频？任务页里的长休息还能拿 45 分。', ts: now - 6 * 60000 },
  ],
  history: ['小月'], avatars: true, avatarMap: {}, streaming: params.has('typing') ? '' : null,
};
const values = { sleepStartHour: '23', sleepEndHour: '5', dayStartHour: '5', cycleLengthDays: '3', darkMode: params.get('theme') === 'dawn' ? '2' : '1',
  showPenaltyNotification: 'true', showSleepTimeNotification: 'true', chatModel: 'deepseek-v4.1-flash', summaryMode: 'false', hideDetail: 'false', showDebugTasks: 'false' };
const week = Array.from({ length: 7 }, (_, i) => ({ date: back(6 - i), done: i >= 1 && i <= 5, today: i === 6, weekday: ((new Date(at(23) - (6 - i) * 24 * H).getDay()) + 1) }));
const responses = {
  'app.boot': { version: '3.0.0', permitted: !params.has('gate'), notice: params.has('notice'), dark: params.get('theme') !== 'dawn', motion: true, publicKey: true,
    insets: { top: 28, bottom: 16 }, rest: null, questions, questionIndex: Object.keys(questions) },
  'app.usage': !params.has('gate'),
  'stats.overview': { now, today, weekday: new Date(now).getDay() + 1, hour: 2, asleep: true, rate: 2.5, start: 23, end: 5, dayStart: 5, cycleLength: 3,
    percentage: true, started: true, detailed: true, debug: false, refreshing: false,
    night: { date: today, today: true, slots, usage: 75 * 60000, recorded: 4 }, cycles },
  'stats.refresh': { skipped: true },
  'timeline.get': { date: today, groups: [
    { at: at(23), range: '23:00~00:00', usage: 18 * 60000, usageText: '18分0秒', score: 42, deduction: 18, ratio: '1.0', bonus: 0, heading: '',
      rows: [{ time: '23:04:12', label: '哔哩哔哩', detail: '12分31秒', kind: 'app', parts: 6 }, { time: '23:17:40', label: '微信', detail: '5分29秒', kind: 'app', parts: 1 }, { time: '23:23:09', label: '屏幕锁定', detail: '41分2秒', kind: 'screen' }] },
    { at: at(0), range: '00:00~01:00', usage: 42 * 60000, usageText: '42分0秒', score: 18, deduction: 63, ratio: '1.5', bonus: 20, heading: '',
      rows: [{ time: '00:04:11', label: '屏幕解锁', detail: '', kind: 'screen' }, { time: '00:04:15', label: '哔哩哔哩', detail: '42分0秒', kind: 'app', parts: 14 }] },
  ] },
  'tasks.overview': { today, bonus: 70, simulation: 12,
    checkin: { done: false, streak: 5, points: 50, week },
    suggestion: { done: false, points: 20 }, chat: { chars: 1820, perPoint: 300, max: 100, points: 6 },
    rests: [{ key: 'last_rest_time', long: true, minutes: 3, points: 45, done: false, available: true, pending: false },
      { key: 'last_rest_short_time', long: false, minutes: 3, points: 20, done: true, available: true, pending: false }],
    debugTasks: false,
    challenges: [{ name: '早睡挑战', early: true, description: '让本自定义日的手机使用扣分低于 100 分', reward: 100, enabled: false, label: '下一自定义日 05:00 可再接取', question: '早睡挑战main' },
      { name: '说服AI晚睡', early: false, description: '与 AI 对话，说服它允许你晚睡', reward: 10, enabled: true, label: '接取挑战', question: '调试题目AI1' }],
    live: [{ id: 't1', name: '早睡挑战', status: 'IN_PROGRESS', early: true, points: 100, description: `判定日期 ${today}` }],
    archived: [{ name: '早睡挑战', detail: `完成时间 ${back(2)} 05:00` }],
    oneTime: { used: 2, limit: 5, done: [{ id: 'a', name: '海豚怎么睡觉', description: '半脑睡眠', points: 10, question: '' }],
      open: [{ id: 'b', name: '褪黑素原理', description: '了解褪黑素如何帮助入睡', points: 10, question: '功能介绍1' }, { id: 'c', name: '睡眠周期', description: '浅睡、深睡与快速眼动', points: 15, question: '功能介绍1' }] } },
  'rest.get': { key: 'last_rest_time', seconds: 180, points: 45, running: null, now },
  'chat.get': chat, 'chat.select': chat,
  'chat.list': { current: 'c1', items: [{ id: 'c1', title: '深夜不想睡', task: false, completed: false, status: '普通对话' },
    { id: 'c2', title: '角色扮演2', task: false, completed: false, status: '角色扮演' }, { id: 'c3', title: '说服我晚睡', task: true, completed: false, status: '进行中' }] },
  'settings.get': { values, avatars: true, version: '3.0.0', model: 'deepseek-v4.1-flash' },
  'audio.tracks': ['放松(女声)-夜深了，窗外的世界渐渐.....mp3', '放松2(少女)-今晚的月亮很圆，风也.mp3'],
  'player.open': { name: '放松2(少女)-今晚的月亮很圆，风也', prepared: true, playing: false, position: 64000, duration: 212000, error: '' },
  'player.state': { name: '放松2(少女)-今晚的月亮很圆，风也', prepared: true, playing: false, position: 64000, duration: 212000, error: '' },
};
export const mock = {
  call(id, method) {
    setTimeout(() => window.__native.resolve(id, true, method in responses ? JSON.parse(JSON.stringify(responses[method])) : null), 10);
  },
};
