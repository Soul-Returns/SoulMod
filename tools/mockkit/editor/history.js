// Undo / redo over whole-state snapshots, like HudMoveScreen: one entry per edit; repeated edits with the
// same key (scroll, nudge on one HUD) within COALESCE_MS merge into one step. Any new edit clears redo.
//
// Exports: COALESCE_MS, createHistory({ snapshot, restore, coalesceMs? }) → { edit, push, undo, redo, canUndo, canRedo, clear }.
// Usage: const h = createHistory({ snapshot: () => JSON.stringify(S), restore: (j) => { S = JSON.parse(j); } });
//        h.edit(() => { S.x += 1; }, 'nudge:sacks');

/** HudMoveScreen.COALESCE_MS: same-key steps within this window are one undo step. */
export const COALESCE_MS = 800;

export function createHistory({ snapshot, restore, coalesceMs = COALESCE_MS }) {
  const undoStack = [], redoStack = [];

  /** Record a finished change (e.g. a drag: snapshot at the press, push at the drop). No-op if nothing changed. */
  function push(before, after, key = null, focus = null) {
    if (before === after) return false;
    const now = Date.now(), last = undoStack[undoStack.length - 1];
    if (key && last && last.key === key && now - last.at < coalesceMs) {
      last.after = after;
      last.at = now;
    } else {
      undoStack.push({ before, after, key, at: now, focus });
    }
    redoStack.length = 0;
    return true;
  }

  return {
    push,
    /** Run [fn] as one undoable step; [focus] (an item id) is what undo/redo re-select. */
    edit(fn, key = null, focus = null) {
      const before = snapshot();
      fn();
      return push(before, snapshot(), key, focus);
    },
    /** Restores the state before the last step; returns its entry (or null). */
    undo() {
      const e = undoStack.pop();
      if (!e) return null;
      restore(e.before);
      redoStack.push(e);
      return e;
    },
    redo() {
      const e = redoStack.pop();
      if (!e) return null;
      restore(e.after);
      undoStack.push(e);
      return e;
    },
    canUndo: () => undoStack.length > 0,
    canRedo: () => redoStack.length > 0,
    clear() {
      undoStack.length = 0;
      redoStack.length = 0;
    },
  };
}
