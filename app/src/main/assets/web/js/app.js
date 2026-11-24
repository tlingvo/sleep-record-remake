// Shared page registry. main.js fills in the navigation functions; pages only import this object.
export const app = {
  boot: null,
  motion: true,
  questions: {},
  push: () => {},
  pop: () => {},
  tab: () => {},
  current: () => null,
  openChat: () => {},
  refreshAll: () => {},
  noticeDone: () => {},
};
