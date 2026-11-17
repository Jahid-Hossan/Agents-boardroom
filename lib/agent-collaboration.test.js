import test from 'node:test';
import assert from 'node:assert/strict';
import {
  buildCollaborationPrompt,
  clampMaxHops,
  isErrorOrSetupReply,
  parseAgentHandoffs
} from './agent-collaboration.js';

const AGENTS = [
  { id: 'muse', name: 'Muse AI', role: 'Muse AI' },
  { id: 'muse-2', name: 'Muse AI 2', role: 'Creative Muse' },
  { id: 'gemini', name: 'Gemini', role: 'Google Gemini' },
  { id: 'chatgpt', name: 'ChatGPT', role: 'OpenAI ChatGPT' },
  { id: 'grok', name: 'Grok', role: 'xAI Grok' }
];

test('clampMaxHops enforces 1..5 bounds with default 3', () => {
  assert.equal(clampMaxHops(undefined), 3);
  assert.equal(clampMaxHops(0), 1);
  assert.equal(clampMaxHops(3), 3);
  assert.equal(clampMaxHops(99), 5);
});

test('parseAgentHandoffs matches @AgentName directives, longest name first, ignoring self/emails/code', () => {
  const reply = [
    'Here is my plan (contact me at team@muse.ai).',
    '```js',
    '// @Grok should not trigger inside code',
    '```',
    '@Muse AI 2: Draft visual concepts for the launch.',
    '@Gemini: Verify technical architecture tradeoffs.',
    '@Muse AI: ignore self mention.'
  ].join('\n');

  const { targets } = parseAgentHandoffs(reply, AGENTS, 'muse');
  assert.equal(targets.length, 2);
  assert.equal(targets[0].agent.id, 'muse-2');
  assert.equal(targets[0].subPrompt, 'Draft visual concepts for the launch.');
  assert.equal(targets[1].agent.id, 'gemini');
  assert.equal(targets[1].subPrompt, 'Verify technical architecture tradeoffs.');
});

test('parseAgentHandoffs supports underscore/hyphen @mentions and deduplicates targets', () => {
  const reply = '@Muse_AI please inspect this. Also @Muse AI follow up.';
  const { targets } = parseAgentHandoffs(reply, AGENTS, 'gemini');
  assert.equal(targets.length, 1);
  assert.equal(targets[0].agent.id, 'muse');
});

test('parseAgentHandoffs ignores auth/setup/error replies', () => {
  const errReply = '**Muse AI needs login in the In-App Browser.** Click @Gemini to fix.';
  assert.equal(isErrorOrSetupReply(errReply), true);
  const { targets } = parseAgentHandoffs(errReply, AGENTS, 'muse');
  assert.equal(targets.length, 0);
});

test('buildCollaborationPrompt formats Supervisor initial, Worker handoff (max 3), and Synthesis prompts', () => {
  const initPrompt = buildCollaborationPrompt({
    userCommand: 'Plan Q4 launch',
    agent: AGENTS[2],
    allAgents: AGENTS,
    isSupervisorInitial: true,
    hop: 1,
    maxHops: 3
  });
  assert.match(initPrompt, /CEO \/ SUPERVISOR MODE/);
  assert.match(initPrompt, /@Muse AI/);
  assert.match(initPrompt, /Plan Q4 launch/);

  const finalRound = buildCollaborationPrompt({
    userCommand: 'Plan Q4 launch',
    agent: AGENTS[0],
    allAgents: AGENTS,
    incomingContext: { fromName: 'Gemini', subPrompt: 'Draft visuals', sourceText: '@Muse AI: Draft visuals' },
    hop: 3,
    maxHops: 3
  });
  assert.match(finalRound, /Round 3\/3/);
  assert.match(finalRound, /do NOT @mention any teammate/i);

  const synth = buildCollaborationPrompt({
    userCommand: 'Plan Q4 launch',
    agent: AGENTS[2],
    allAgents: AGENTS,
    collectedReplies: [{ senderName: 'Muse AI', text: 'Visual deck ready' }],
    isSupervisorSynthesis: true
  });
  assert.match(synth, /FINAL SYNTHESIS/);
  assert.match(synth, /Visual deck ready/);
});
