// Flow block composer helpers — 流程积木编排辅助：从 YAML 解析/写回 list|detail|submit.blocks（无 yaml 依赖）。

import { isPageBlockId } from "@/lib/page-blocks-catalog";

export type FlowSectionKey = "list" | "detail" | "submit";

export type FlowPageBlocks = {
  list: string[];
  detail: string[];
  submit: string[];
};

export const FLOW_SECTION_KEYS: readonly FlowSectionKey[] = ["list", "detail", "submit"];

const SECTION_HEADER = /^(list|detail|submit):\s*(?:#.*)?$/;
const ROOT_KEY = /^[A-Za-z_][\w-]*:\s*/;
const BLOCKS_KEY = /^ {2}blocks:\s*(?:#.*)?$/;
const EMPTY_BLOCKS = /^ {2}blocks:\s*\[\s*\]\s*(?:#.*)?$/;
const BLOCK_ITEM = /^ {4}-\s+(\S+)\s*(?:#.*)?$/;

/** Empty composer state — 空编排状态。 */
export function emptyFlowPageBlocks(): FlowPageBlocks {
  return { list: [], detail: [], submit: [] };
}

/**
 * Parse optional blocks lists under list/detail/submit.
 * 解析 list/detail/submit 下可选的 blocks 列表；缺失则空数组。
 */
export function parseFlowBlocksFromYaml(yaml: string): FlowPageBlocks {
  const result = emptyFlowPageBlocks();
  const lines = yaml.replace(/\r\n/g, "\n").split("\n");
  let section: FlowSectionKey | null = null;
  let inBlocks = false;

  for (const line of lines) {
    const header = line.match(SECTION_HEADER);
    if (header) {
      section = header[1] as FlowSectionKey;
      inBlocks = false;
      continue;
    }
    if (section == null) continue;
    if (ROOT_KEY.test(line) && !line.startsWith(" ")) {
      section = null;
      inBlocks = false;
      continue;
    }
    if (EMPTY_BLOCKS.test(line)) {
      result[section] = [];
      inBlocks = false;
      continue;
    }
    if (BLOCKS_KEY.test(line)) {
      result[section] = [];
      inBlocks = true;
      continue;
    }
    if (inBlocks) {
      const item = line.match(BLOCK_ITEM);
      if (item) {
        const id = item[1];
        if (isPageBlockId(id) && !result[section].includes(id)) {
          result[section].push(id);
        }
        continue;
      }
      if (line.trim() === "") continue;
      inBlocks = false;
    }
  }
  return result;
}

function formatBlocksYaml(ids: readonly string[]): string[] {
  if (ids.length === 0) return [];
  return ["  blocks:", ...ids.map((id) => `    - ${id}`)];
}

/**
 * Remove blocks key and its sequence from one section body (lines under the header).
 * 从某一节正文中去掉 blocks 键及其序列。
 */
function stripBlocksInSection(sectionLines: string[]): string[] {
  const out: string[] = [];
  let i = 0;
  while (i < sectionLines.length) {
    const line = sectionLines[i];
    if (EMPTY_BLOCKS.test(line)) {
      i += 1;
      continue;
    }
    if (BLOCKS_KEY.test(line)) {
      i += 1;
      while (i < sectionLines.length) {
        const next = sectionLines[i];
        if (BLOCK_ITEM.test(next) || next.trim() === "") {
          i += 1;
          continue;
        }
        break;
      }
      continue;
    }
    out.push(line);
    i += 1;
  }
  return out;
}

/**
 * Merge composer blocks into flow YAML without wiping other keys.
 * 把编排器的 blocks 合并进流程 YAML，保留其它键；空列表则省略 blocks。
 */
export function applyFlowBlocksToYaml(yaml: string, blocks: FlowPageBlocks): string {
  const normalized = yaml.replace(/\r\n/g, "\n");
  const hadTrailingNewline = normalized.endsWith("\n");
  const lines = normalized.split("\n");
  const working =
    hadTrailingNewline && lines.length > 0 && lines[lines.length - 1] === ""
      ? lines.slice(0, -1)
      : lines;

  type Range = { header: number; start: number; end: number; key: FlowSectionKey };
  const ranges: Range[] = [];
  let i = 0;
  while (i < working.length) {
    const match = working[i].match(SECTION_HEADER);
    if (match) {
      const key = match[1] as FlowSectionKey;
      const header = i;
      i += 1;
      const start = i;
      while (i < working.length) {
        const line = working[i];
        if (ROOT_KEY.test(line) && !line.startsWith(" ")) break;
        i += 1;
      }
      ranges.push({ key, header, start, end: i });
      continue;
    }
    i += 1;
  }

  const pieces: string[] = [];
  let cursor = 0;
  for (const range of ranges) {
    pieces.push(...working.slice(cursor, range.header + 1));
    let body = stripBlocksInSection(working.slice(range.start, range.end));
    while (body.length > 0 && body[body.length - 1].trim() === "") {
      body = body.slice(0, -1);
    }
    const blockLines = formatBlocksYaml(blocks[range.key]);
    pieces.push(...body, ...blockLines);
    cursor = range.end;
  }
  pieces.push(...working.slice(cursor));

  let result = pieces.join("\n");
  if ((hadTrailingNewline || result.length > 0) && !result.endsWith("\n")) {
    result += "\n";
  }
  return result;
}

/** Toggle id in ordered list — 在有序列表中加入或移除 id。 */
export function toggleBlockId(current: readonly string[], id: string, include: boolean): string[] {
  if (!isPageBlockId(id)) return [...current];
  if (include) {
    return current.includes(id) ? [...current] : [...current, id];
  }
  return current.filter((x) => x !== id);
}

/** Move id up/down in list — 上移/下移。 */
export function moveBlockId(current: readonly string[], id: string, direction: -1 | 1): string[] {
  const index = current.indexOf(id);
  if (index < 0) return [...current];
  const next = index + direction;
  if (next < 0 || next >= current.length) return [...current];
  const copy = [...current];
  const tmp = copy[index];
  copy[index] = copy[next];
  copy[next] = tmp;
  return copy;
}
