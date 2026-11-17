// Agent-to-Agent @AgentName Handoffs & CEO/Supervisor Orchestration

export const DEFAULT_MAX_HOPS = 3;

export function clampMaxHops(val, fallback = DEFAULT_MAX_HOPS) {
  const num = Number(val);
  if (!Number.isFinite(num)) return fallback;
  return Math.min(Math.max(Math.round(num), 1), 5);
}

export function isErrorOrSetupReply(text = '') {
  const s = String(text || '').trim();
  if (!s) return true;
  return (
    /needs login in the In-App Browser/i.test(s) ||
    /not signed in yet/i.test(s) ||
    /Desktop Browser Bridge is not connected/i.test(s) ||
    /^\*\*[^*]+ error:\*\*/i.test(s) ||
    /is not configured yet\.\*\*/i.test(s)
  );
}

function escapeRegex(str = '') {
  return String(str).replace(/[-/\\^$*+?.()|[\]{}]/g, '\\$&');
}

function buildNamePattern(name = '') {
  const tokens = String(name || '')
    .trim()
    .split(/\s+/)
    .filter(Boolean)
    .map(escapeRegex);
  if (tokens.length === 0) return null;
  // Allow space, underscore, or hyphen between words (e.g. @Muse AI, @Muse_AI, @Muse-AI)
  return tokens.join('[\\s_-]+');
}

/**
 * Parse @AgentName handoff directives from an agent's reply.
 * Excludes code blocks, email addresses, and self-mentions.
 */
export function parseAgentHandoffs(replyText, allAgents = [], senderAgentId = '') {
  const raw = String(replyText || '');
  if (!raw || isErrorOrSetupReply(raw)) {
    return { targets: [] };
  }

  // Strip fenced code blocks and inline code so technical examples don't trigger accidental hops
  const cleaned = raw
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/`[^`\n]+`/g, ' ');

  const candidates = (allAgents || [])
    .filter((a) => a && a.id && a.name)
    .sort((a, b) => b.name.length - a.name.length);

  if (candidates.length === 0) {
    return { targets: [] };
  }

  const hits = [];
  const occupiedRanges = [];

  for (const agent of candidates) {
    const namePat = buildNamePattern(agent.name);
    if (!namePat) continue;
    // Must not be preceded by alphanumeric/dot/underscore (avoids emails like user@muse.ai)
    const re = new RegExp(`(^|[^\\w.])@(${namePat})(?=$|[^\\w-])`, 'gi');
    let match;
    while ((match = re.exec(cleaned)) !== null) {
      const mentionStart = match.index + match[1].length;
      const mentionEnd = mentionStart + 1 + match[2].length;
      const overlaps = occupiedRanges.some(
        ([s, e]) => (mentionStart >= s && mentionStart < e) || (mentionEnd > s && mentionEnd <= e)
      );
      if (!overlaps) {
        occupiedRanges.push([mentionStart, mentionEnd]);
        hits.push({ agent, mentionStart, mentionEnd });
      }
    }
  }

  if (hits.length === 0) {
    return { targets: [] };
  }

  hits.sort((a, b) => a.mentionStart - b.mentionStart);

  const seenAgentIds = new Set();
  const targets = [];

  for (let i = 0; i < hits.length; i++) {
    const hit = hits[i];
    if (hit.agent.id === senderAgentId) continue;
    if (seenAgentIds.has(hit.agent.id)) continue;
    seenAgentIds.add(hit.agent.id);

    const nextStart = i + 1 < hits.length ? hits[i + 1].mentionStart : cleaned.length;
    let slice = cleaned.slice(hit.mentionEnd, nextStart);
    // Stop at paragraph break if the slice has multiple paragraphs
    const paraIdx = slice.search(/\n\s*\n/);
    if (paraIdx !== -1) {
      slice = slice.slice(0, paraIdx);
    }
    const subPrompt = slice.replace(/^[\s:,\-–—>]+/, '').trim();

    targets.push({
      agent: hit.agent,
      subPrompt: subPrompt || cleaned.trim()
    });
  }

  return { targets };
}

export function buildCollaborationPrompt({
  userCommand,
  agent,
  allAgents = [],
  incomingContext = null,
  collectedReplies = [],
  isSupervisorInitial = false,
  isSupervisorSynthesis = false,
  hop = 0,
  maxHops = DEFAULT_MAX_HOPS
}) {
  const supervisor = allAgents.find((a) => a.id !== agent.id && a.isSupervisor);
  const teammates = (allAgents || [])
    .filter((a) => a.id !== agent.id)
    .map((a) => `@${a.name}${a.role ? ` (${a.role})` : ''}${a.id === supervisor?.id ? ' [CEO/Supervisor]' : ''}`);

  const teamLine = teammates.length > 0
    ? `Connected teammates: ${teammates.join(', ')}.`
    : 'No other teammates connected.';

  if (isSupervisorSynthesis) {
    const transcript = collectedReplies
      .map((r) => `[${r.senderName}]:\n${String(r.text || '').trim()}`)
      .join('\n\n---\n\n');
    return [
      `[CEO / SUPERVISOR FINAL SYNTHESIS]`,
      `Original user request: ${userCommand}`,
      '',
      `Team responses collected:`,
      transcript,
      '',
      `Instructions: Act as the CEO / Supervisor. Synthesize the team's work into one clear, authoritative final answer or decision for the user. Do NOT @mention any teammate in this final synthesis.`
    ].join('\n');
  }

  if (isSupervisorInitial) {
    return [
      `[CEO / SUPERVISOR MODE · Round 1/${maxHops}]`,
      teamLine,
      `Instructions: You are the CEO / Supervisor. Review the user's request below.`,
      `- If you need specialist input or work from teammates, delegate specific tasks on separate lines using \`@AgentName: <clear task>\` (mention ONLY the teammates you actually want to invoke).`,
      `- If no teammate input is needed, answer the user directly without @mentioning anyone.`,
      '',
      `User request: ${userCommand}`
    ].join('\n');
  }

  if (incomingContext) {
    const roundLabel = `Round ${hop}/${maxHops}`;
    const handoffInstruction =
      hop < maxHops && teammates.length > 0
        ? `If—and only if—you need another teammate's input before finishing, write \`@AgentName: <specific request>\` (${hop}/${maxHops} max rounds). Otherwise answer directly without @mentioning anyone.`
        : `This is the final interaction round (${roundLabel}). Provide a direct, complete answer and do NOT @mention any teammate.`;

    const lines = [
      `[TEAM HANDOFF · ${roundLabel}]`,
      teamLine,
      `Original user request: ${userCommand}`,
      `Request from @${incomingContext.fromName}: ${incomingContext.subPrompt}`
    ];
    if (incomingContext.sourceText && incomingContext.sourceText !== incomingContext.subPrompt) {
      lines.push(`Context from @${incomingContext.fromName}:\n${incomingContext.sourceText.slice(0, 1500)}`);
    }
    lines.push('', `Instructions: ${handoffInstruction}`);
    return lines.join('\n');
  }

  // Standard boardroom turn with optional peer @AgentName handoff enabled
  if (teammates.length === 0) return userCommand;
  return [
    `[BOARDROOM COLLABORATION · Round 1/${maxHops}]`,
    teamLine,
    `Instructions: Answer the user's request. If—and only if—you genuinely need another teammate's input or review, include \`@AgentName: <specific request>\`. Do NOT @mention any agent unless you want them to reply.`,
    '',
    `User request: ${userCommand}`
  ].join('\n');
}
