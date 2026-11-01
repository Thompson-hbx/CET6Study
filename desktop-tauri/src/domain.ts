export interface Draft {
  id: string; title: string; prompt: string; text: string; reviewText: string; reviewSource: string;
  accepted: number[]; dismissed: number[]; parentId: string | null; updatedAt: number;
}
export interface Workspace { version: number; revision: number; activeId: string | null; drafts: Draft[] }
export interface Issue { id: number; quote: string; fix: string; reason: string; category: string; must: boolean }
export interface Review { issues: Issue[]; band: number | null; summary: string }
export interface Placement { issue: Issue; start: number | null; end: number | null }
type Row = Record<string, unknown>;
const record = (value: unknown): value is Row => value !== null && typeof value === 'object' && !Array.isArray(value);
const text = (row: Row, key: string) => typeof row[key] === 'string' ? row[key].trim() : '';
const strings = (row: Row, ...keys: string[]) => keys.every(key => !(key in row) || typeof row[key] === 'string');
const positive = (value: unknown): value is number => typeof value === 'number' && Number.isInteger(value) && value > 0 && value <= 2147483647;
const integerFields = new WeakMap<Row, Set<string>>();

// JSON.parse collapses 11.0 and 11 into the same JS number; preserve integer token types used by the phone protocol.
function numericTokens(raw: string): Set<string> {
  const integers = new Set<string>(); const keys = new Set<string>(); let depth = 0;
  for (let i = 0; i < raw.length; i++) {
    if (raw[i] === '{' || raw[i] === '[') depth++;
    else if (raw[i] === '}' || raw[i] === ']') depth--;
    else if (raw[i] === '"') {
      const start = i; let escaped = false;
      for (i++; i < raw.length; i++) { if (escaped) escaped = false; else if (raw[i] === '\\') escaped = true; else if (raw[i] === '"') break; }
      let at = i + 1; while (/\s/.test(raw[at] ?? '') && at < raw.length) at++;
      if (depth !== 1 || raw[at] !== ':') continue;
      const key = JSON.parse(raw.slice(start, i + 1)) as string;
      if (keys.has(key)) throw new Error('批改字段重复，原文仍保留'); keys.add(key);
      at++; while (/\s/.test(raw[at] ?? '') && at < raw.length) at++;
      const token = raw.slice(at).match(/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/);
      if (token && /^-?\d+$/.test(token[0])) integers.add(key);
    }
  }
  return integers;
}
const optionalChoice = (row: Row, key: string, choices: string[]) => !(key in row) || choices.includes(row[key] as string);

/** Mirrors the existing JVM brace scanner: fences and pretty JSON are accepted, incomplete objects are rejected. */
function objects(raw: string): Row[] {
  const found: Row[] = []; let depth = 0; let start = -1; let quoted = false; let escaped = false;
  for (let i = 0; i < raw.length; i++) {
    const c = raw[i];
    if (quoted) { if (escaped) escaped = false; else if (c === '\\') escaped = true; else if (c === '"') quoted = false; continue; }
    if (c === '"' && depth > 0) quoted = true;
    else if (c === '{') { if (depth === 0) start = i; depth++; }
    else if (c === '}' && depth > 0) {
      depth--;
      if (depth === 0) {
        let parsed: unknown;
        try { parsed = JSON.parse(raw.slice(start, i + 1)); } catch { throw new Error('批改 JSON 损坏，原文仍保留'); }
        if (!record(parsed) || typeof parsed.type !== 'string') throw new Error('批改类型无效，原文仍保留');
        integerFields.set(parsed, numericTokens(raw.slice(start, i + 1)));
        found.push(parsed);
      }
    }
  }
  if (depth !== 0 || quoted) throw new Error('批改尚未完整结束，请粘贴完整回复');
  return found;
}

export function parseReview(raw: string): Review {
  if (raw.length > 200_000) throw new Error('批改超过单条内容上限');
  const rows = objects(raw); const seen = new Set<string>(); const indexes = new Set<number>();
  const result: Review = { issues: [], band: null, summary: '' }; let structured = false;
  for (const row of rows) {
    const type = row.type as string;
    let valid = true;
    if (type === 'score' || type === 'rewrite') { if (seen.has(type)) valid = false; seen.add(type); }
    switch (type) {
      case 'score': {
        const score = row.band;
        valid &&= integerFields.get(row)?.has('band') === true && typeof score === 'number' && Number.isInteger(score) && score >= 0 && score <= 15 && strings(row, 'summary');
        if (valid) { result.band = score === 0 ? 0 : [2, 5, 8, 11, 14].reduce((a, b) => Math.abs(b - (score as number)) < Math.abs(a - (score as number)) ? b : a); result.summary = text(row, 'summary'); structured = true; }
        break;
      }
      case 'aspect': valid &&= text(row, 'name') !== '' && strings(row, 'name', 'level', 'note') && optionalChoice(row, 'level', ['好', '一般', '待改进']); structured = true; break;
      case 'issue':
        valid &&= strings(row, 'quote', 'fix', 'reason', 'category', 'level') && (text(row, 'quote') !== '' || text(row, 'fix') !== '') && (!('index' in row) || (positive(row.index) && integerFields.get(row)?.has('index') === true)) && optionalChoice(row, 'level', ['must', 'better']);
        if (valid) result.issues.push({ id: result.issues.length, quote: text(row, 'quote'), fix: text(row, 'fix'), reason: text(row, 'reason'), category: text(row, 'category') || '表达', must: row.level !== 'better' });
        structured = true; break;
      case 'segment':
        valid &&= integerFields.get(row)?.has('index') === true && positive(row.index) && !indexes.has(row.index as number) && strings(row, 'mine', 'verdict', 'note', 'suggestion') && optionalChoice(row, 'verdict', ['good', 'minor', 'error', 'missing']);
        indexes.add(row.index as number); structured = true; break;
      case 'term': valid &&= text(row, 'zh') !== '' && text(row, 'en') !== '' && strings(row, 'zh', 'en', 'note'); break;
      case 'tip': case 'rewrite': valid &&= typeof row.text === 'string'; break;
    }
    if (!valid) throw new Error('批改字段不符合现有协议，已停止生成可采纳批注；原文仍保留');
  }
  if (!structured) throw new Error('未找到结构化写作批改，原文仍保留');
  return result;
}

function normalized(source: string): { text: string; offsets: number[] } {
  let out = ''; const offsets: number[] = []; let space = true;
  for (let i = 0; i < source.length; i++) {
    let c = source[i];
    if (/\s/u.test(c)) { if (!space) { out += ' '; offsets.push(i); space = true; } }
    else { c = /[’‘′`]/u.test(c) ? "'" : /[“”″]/u.test(c) ? '"' : /[–—]/u.test(c) ? '-' : c.toLowerCase()[0]; out += c; offsets.push(i); space = false; }
  }
  return { text: out, offsets };
}
export function placeIssues(source: string, issues: Issue[]): Placement[] {
  const used: {start: number; end: number}[] = [];
  return issues.map(issue => {
    const quote = issue.quote.trim(); const ranges: {start: number; end: number}[] = [];
    if (quote !== '' && quote.length <= 2000) {
      for (let at = source.indexOf(quote); at >= 0; at = source.indexOf(quote, at + 1)) ranges.push({ start: at, end: at + quote.length });
      if (ranges.length === 0) {
        const folded = normalized(source); const needle = normalized(quote).text.trim();
        if (needle !== '') for (let at = folded.text.indexOf(needle); at >= 0; at = folded.text.indexOf(needle, at + 1)) ranges.push({ start: folded.offsets[at], end: folded.offsets[at + needle.length - 1] + 1 });
      }
    }
    const range = ranges.find(r => used.every(taken => r.start >= taken.end || r.end <= taken.start));
    if (range) used.push(range);
    return { issue, start: range?.start ?? null, end: range?.end ?? null };
  });
}
export function applyAccepted(source: string, placements: Placement[], accepted: number[]): string {
  const edits = placements.filter(p => p.start !== null && p.end !== null && accepted.includes(p.issue.id)).sort((a, b) => b.start! - a.start!);
  let result = source; let boundary = source.length;
  for (const p of edits) { if (p.end! > boundary) continue; result = result.slice(0, p.start!) + p.issue.fix + result.slice(p.end!); boundary = p.start!; }
  return result;
}
export function newDraft(parent: Draft | null = null): Draft {
  return { id: crypto.randomUUID(), title: parent ? `${parent.title || '未命名作文'} · 修改稿` : '', prompt: parent?.prompt ?? '', text: parent?.text ?? '', reviewText: '', reviewSource: '', accepted: [], dismissed: [], parentId: parent?.id ?? null, updatedAt: Date.now() };
}
export const wordCount = (value: string) => (value.match(/[A-Za-z0-9]+(?:['’-][A-Za-z0-9]+)*/g) ?? []).length;

/** Serializes all saves; a failed write is visible and a later edit retries the latest complete snapshot. */
export class SaveQueue {
  private tail: Promise<void> = Promise.resolve(); private revision: number; private pending = 0;
  constructor(revision: number, private readonly persist: (workspace: Workspace) => Promise<Workspace>, private readonly status: (saving: boolean, error: string | null) => void) { this.revision = revision; }
  enqueue(workspace: Workspace): Promise<void> {
    const snapshot = structuredClone(workspace); this.pending++; this.status(true, null);
    const next = this.tail.catch(() => undefined).then(async () => {
      this.status(true, null); let failed = false;
      try { const saved = await this.persist({ ...snapshot, revision: this.revision }); this.revision = saved.revision; }
      catch (error) { failed = true; this.status(false, error instanceof Error ? error.message : String(error)); throw error; }
      finally { this.pending--; if (!failed && this.pending === 0) this.status(false, null); }
    });
    this.tail = next; void next.catch(() => undefined); return next;
  }
  async flush(): Promise<void> { await this.tail; this.status(false, null); }
}
