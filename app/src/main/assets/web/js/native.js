// Calls into Kotlin. Each call resolves when MainActivity runs `__native.resolve(id, ok, payload)`;
// native-side events arrive through `__native.emit(type, data)`.
const pending = new Map();
const listeners = new Map();
let seq = 0;
let backend = window.SleepNative || null;

export class NativeError extends Error {}
export const native = { get real() { return !!window.SleepNative; } };

window.__native = {
  resolve(id, ok, payload) {
    const entry = pending.get(id);
    if (!entry) return;
    pending.delete(id);
    if (ok) entry.resolve(payload);
    else entry.reject(new NativeError((payload && payload.message) || '请重试'));
  },
  emit(type, data) {
    for (const fn of (listeners.get(type) || []).slice()) {
      try { fn(data); } catch (error) { console.error(error); }
    }
  },
};

export function useBackend(mock) { backend = mock; }

export function call(method, args = {}) {
  return new Promise((resolve, reject) => {
    const id = ++seq;
    pending.set(id, { resolve, reject });
    try { backend.call(id, method, JSON.stringify(args)); }
    catch (error) { pending.delete(id); reject(new NativeError(String(error && error.message || error))); }
  });
}

export function on(type, fn) {
  if (!listeners.has(type)) listeners.set(type, []);
  listeners.get(type).push(fn);
  return () => { const list = listeners.get(type); const i = list.indexOf(fn); if (i >= 0) list.splice(i, 1); };
}
