#!/usr/bin/env node
const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');
const readline = require('node:readline');
const crypto = require('node:crypto');

// Load .env file (env vars take precedence)
const envPath = path.join(__dirname, '.env');
if (fs.existsSync(envPath)) {
  for (const line of fs.readFileSync(envPath, 'utf8').split('\n')) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const eq = trimmed.indexOf('=');
    if (eq < 0) continue;
    const key = trimmed.slice(0, eq).trim();
    let val = trimmed.slice(eq + 1).trim();
    if ((val.startsWith('"') && val.endsWith('"')) || (val.startsWith("'") && val.endsWith("'"))) {
      val = val.slice(1, -1);
    }
    if (!(key in process.env)) process.env[key] = val;
  }
}

const PORT = parseInt(process.env.PORT || '5000');
const COOLDOWN = parseInt(process.env.COOLDOWN || '60');
const BUILDS_DIR = path.resolve(__dirname, process.env.BUILDS_DIR || '../builds');
const APP_DIR = path.resolve(__dirname, process.env.APP_DIR || '../threadly');
const APP_NAME = process.env.APP_NAME || 'Threadly';
const ANALYSIS_PHASE_CONTEXT = path.join(__dirname, 'CLAUDE_SRE_ANALYSIS_PHASE.md');
const REMEDIATION_PHASE_CONTEXT = path.join(__dirname, 'CLAUDE_SRE_REMEDIATION_PHASE.md');
const LOG_FILE = process.env.CLAUDE_LOG || '/tmp/claude-sre.log';
const APP_PORT = process.env.APP_PORT || '8180';
const APP_LOG = process.env.APP_LOG || '/tmp/threadly.log';
const DEMO_HOST = process.env.DEMO_HOST || 'thor';
const DEMO_TITLE = process.env.DEMO_TITLE || 'Threadly - Closed-Loop Remediation';
const PAYMENTS_DIR = path.resolve(__dirname, process.env.PAYMENTS_DIR || '../threadly-payments');
const PAYMENTS_BUILDS_DIR = path.resolve(__dirname, process.env.PAYMENTS_BUILDS_DIR || '../threadly-payments/builds');
const PAYMENTS_NAME = process.env.PAYMENTS_NAME || 'ThreadlyPayments';
const PAYMENTS_PORT = process.env.PAYMENTS_PORT || '8181';
const PAYMENTS_LOG = process.env.PAYMENTS_LOG || '/tmp/payments.log';
const PAYMENTS_URL = process.env.PAYMENTS_URL || 'http://localhost:8181';
const THREADLY_DB = process.env.THREADLY_DB || '/tmp/threadly.db';
const PAYMENTS_DB = process.env.PAYMENTS_DB || '/tmp/payments.db';

let state = 'idle'; // idle | analyzing | awaiting_choice | executing
let sseClients = [];
let lastDispatch = 0;
let currentOptions = null;
let currentDiagnosis = null;
let currentTrigger = null;

function getVersion() {
  try {
    return fs.readFileSync(path.join(BUILDS_DIR, 'active/version.txt'), 'utf8').trim();
  } catch { return 'unknown'; }
}

function broadcast(event, data) {
  const msg = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
  sseClients = sseClients.filter(res => {
    try { res.write(msg); return true; }
    catch { return false; }
  });
}

function setState(newState) {
  state = newState;
  broadcast('status', { state, version: getVersion() });
}

function renderPrompt(filePath) {
  let text = fs.readFileSync(filePath, 'utf8');
  const vars = {
    APP_DIR,
    APP_NAME,
    BUILDS_DIR,
    APP_PORT,
    APP_LOG,
    DEPLOY_SCRIPT: path.join(__dirname, 'deploy.sh'),
    CLOSED_LOOP_DIR: __dirname,
    PAYMENTS_DIR,
    PAYMENTS_BUILDS_DIR,
    PAYMENTS_NAME,
    PAYMENTS_PORT,
    PAYMENTS_LOG,
    PAYMENTS_URL,
    PAYMENTS_DEPLOY_SCRIPT: path.join(__dirname, 'deploy-payments.sh'),
    THREADLY_DB,
    PAYMENTS_DB,
  };
  for (const [key, val] of Object.entries(vars)) {
    text = text.replaceAll(`{{${key}}}`, val);
  }
  return text;
}

function dispatchClaude(prompt, phase, contextFile) {
  return new Promise((resolve, reject) => {
    const logStream = fs.createWriteStream(LOG_FILE, { flags: 'a' });
    const phaseName = phase === 1 ? 'analysis' : 'remediation';
    const ts = new Date().toISOString();
    logStream.write(`\n${'='.repeat(60)}\n[${ts}] DISPATCH (${phaseName})\n${'='.repeat(60)}\n`);

    const rendered = renderPrompt(contextFile);
    const tmpFile = path.join(os.tmpdir(), `claude-sre-${phaseName}-${Date.now()}.md`);
    fs.writeFileSync(tmpFile, rendered);

    const args = [
      '-p',
      '--append-system-prompt-file', tmpFile,
      '--add-dir', APP_DIR,
      '--add-dir', PAYMENTS_DIR,
      '--output-format', 'stream-json',
      '--verbose',
    ];

    // Phase 1 (analysis): read-only investigation. Allow any Bash but no Edit/Write —
    // fine-grained Bash(<pattern>) was tried first, but it doesn't reliably match
    // piped commands like `tail ... | grep ... | tail ...`, which Claude routinely
    // wants during root-cause analysis. Edit/Write aren't in the allowlist, so the
    // analysis prompt's "diagnose only" guarantee still holds.
    // Phase 2 (remediation): full toolset; --dangerously-skip-permissions bypasses
    // the per-call confirmation prompt that would block headless dispatch.
    if (phase === 1) {
      args.push('--allowedTools', 'Read,Grep,Glob,Bash');
      args.push('--dangerously-skip-permissions');
    } else {
      args.push('--allowedTools', 'Read,Edit,Write,Grep,Glob,Bash');
      args.push('--dangerously-skip-permissions');
    }

    const proc = spawn('claude', args, {
      stdio: ['pipe', 'pipe', 'pipe'],
      env: { ...process.env },
    });

    let resultText = '';
    const prefix = phase === 1 ? 'phase1' : 'phase2';

    const rl = readline.createInterface({ input: proc.stdout });
    rl.on('line', (line) => {
      logStream.write(line + '\n');
      if (!line.startsWith('{')) return;

      let ev;
      try { ev = JSON.parse(line); } catch { return; }

      if (ev.type === 'assistant') {
        const content = ev.message?.content || [];
        for (const block of content) {
          if (block.type === 'text' && block.text) {
            if (phase === 1 && block.text.includes('"diagnosis"')) {
              continue;
            }
            broadcast(`${prefix}_stream`, { text: block.text });
          } else if (block.type === 'tool_use') {
            broadcast(`${prefix}_tool`, {
              tool: block.name,
              input: formatToolInput(block.name, block.input || {}),
            });
          }
        }
      } else if (ev.type === 'result') {
        resultText = ev.result || '';
        const cost = ev.total_cost_usd || 0;
        const duration = (ev.duration_ms || 0) / 1000;
        const turns = ev.num_turns || 0;
        broadcast(`${prefix}_meta`, { cost, duration, turns });
      }
    });

    proc.stderr.on('data', (chunk) => {
      logStream.write(chunk);
    });

    proc.on('close', (code) => {
      logStream.write(`\n--- session end (exit=${code}) ---\n`);
      logStream.end();
      try { fs.unlinkSync(tmpFile); } catch {}
      resolve(resultText);
    });

    proc.on('error', (err) => {
      logStream.end();
      reject(err);
    });

    proc.stdin.write(prompt);
    proc.stdin.end();
  });
}

function formatToolInput(name, inp) {
  if (['Read', 'Edit', 'Write'].includes(name)) return inp.file_path || '';
  if (name === 'Bash') return (inp.command || '').slice(0, 120);
  if (name === 'Grep') return `${inp.pattern || ''} ${inp.path || ''}`;
  if (name === 'Glob') return inp.pattern || '';
  return JSON.stringify(inp).slice(0, 120);
}

async function runPhase1(triggerInfo, source) {
  if (state !== 'idle') {
    return { status: 'busy', reason: state };
  }
  const now = Date.now() / 1000;
  if (now - lastDispatch < COOLDOWN) {
    return { status: 'cooldown', remaining: Math.ceil(COOLDOWN - (now - lastDispatch)) };
  }
  lastDispatch = now;

  setState('analyzing');
  currentOptions = null;
  currentDiagnosis = null;
  currentTrigger = triggerInfo;

  broadcast('trigger', triggerInfo);

  const prompt = `An alarm was received from the monitoring system.

## Trigger Details

- **Source:** ${source}
- **Alarm:** ${triggerInfo.alarm_name || 'Unknown'}
- **Severity:** ${triggerInfo.severity || 'Unknown'}
- **Status:** ${triggerInfo.status || 'Unknown'}
- **Host:** ${triggerInfo.host || 'Unknown'}
- **Agent:** ${triggerInfo.agent || 'Unknown'}
- **Component:** ${triggerInfo.component || 'Unknown'}
- **Message:** ${triggerInfo.message || 'No message'}
- **Alert ID:** ${triggerInfo.alert_external_id || 'N/A'}
- **Metric:** ${triggerInfo.metric_name || 'N/A'} = ${triggerInfo.metric_value || 'N/A'}
- **Thresholds:** caution=${triggerInfo.caution_threshold || 'N/A'}, danger=${triggerInfo.danger_threshold || 'N/A'}
- **Alarm Type:** ${triggerInfo.alarm_type || 'Unknown'}

Diagnose the root cause by following the diagnostic procedure in your system prompt. Then provide your analysis and 3 remediation options as instructed.

Include the trigger/alarm details in your diagnosis so it's clear which alert fired and why.

Output ONLY valid JSON matching this schema — no markdown fences, no extra text:
{
  "diagnosis": "string - root cause summary",
  "error_type": "string - exception class name",
  "file": "string - source file name",
  "line": "number - line number",
  "current_version": "string - currently deployed version",
  "log_excerpt": "string - actual log ERROR block from the app log",
  "user_impact": "string - what end users experience",
  "code_snippet": "string - the buggy code with surrounding lines",
  "options": [
    {
      "id": "number (1-3)",
      "action": "rollback | fix | snow",
      "recommendation": "string - one sentence",
      "confidence": "number 0-100",
      "risk": "string - one sentence risk assessment",
      "description": "string - paragraph explaining the option",
      "prompt": "string - detailed prompt for Phase 2 Claude"
    }
  ]
}`;

  try {
    const result = await dispatchClaude(prompt, 1, ANALYSIS_PHASE_CONTEXT);
    let parsed;
    try {
      parsed = JSON.parse(result);
    } catch {
      // Find the outermost JSON object by matching braces
      const start = result.indexOf('{');
      if (start < 0) throw new Error('No JSON found in Phase 1 output');
      let depth = 0;
      let inString = false;
      let escape = false;
      let end = -1;
      for (let i = start; i < result.length; i++) {
        const ch = result[i];
        if (escape) { escape = false; continue; }
        if (ch === '\\' && inString) { escape = true; continue; }
        if (ch === '"') { inString = !inString; continue; }
        if (inString) continue;
        if (ch === '{') depth++;
        else if (ch === '}') { depth--; if (depth === 0) { end = i; break; } }
      }
      if (end < 0) throw new Error('Unbalanced JSON in Phase 1 output');
      parsed = JSON.parse(result.slice(start, end + 1));
    }

    parsed.options.sort((a, b) => b.confidence - a.confidence);
    currentOptions = parsed.options;
    currentDiagnosis = {
      diagnosis: parsed.diagnosis,
      error_type: parsed.error_type,
      file: parsed.file,
      line: parsed.line,
      current_version: parsed.current_version,
      log_excerpt: parsed.log_excerpt || null,
      user_impact: parsed.user_impact || null,
      code_snippet: parsed.code_snippet || null,
    };

    setState('awaiting_choice');
    broadcast('phase1_complete', { diagnosis: currentDiagnosis, options: currentOptions, raw_json: JSON.stringify(parsed, null, 2) });
    return { status: 'analyzed' };
  } catch (err) {
    broadcast('error', { message: `Phase 1 failed: ${err.message}` });
    setState('idle');
    return { status: 'error', reason: err.message };
  }
}

async function runPhase2(optionId) {
  if (state !== 'awaiting_choice' || !currentOptions) {
    return { status: 'error', reason: 'No options available' };
  }

  const option = currentOptions.find(o => o.id === optionId);
  if (!option) {
    return { status: 'error', reason: `Option ${optionId} not found` };
  }

  setState('executing');
  broadcast('phase2_start', { action: option.action, recommendation: option.recommendation });

  try {
    const result = await dispatchClaude(option.prompt, 2, REMEDIATION_PHASE_CONTEXT);
    broadcast('phase2_complete', { result, action: option.action });
    setState('idle');
    return { status: 'completed', action: option.action };
  } catch (err) {
    broadcast('error', { message: `Phase 2 failed: ${err.message}` });
    setState('idle');
    return { status: 'error', reason: err.message };
  }
}

function serveStatic(res, filePath) {
  const ext = path.extname(filePath);
  const types = { '.html': 'text/html', '.css': 'text/css', '.js': 'application/javascript', '.png': 'image/png' };
  try {
    const content = fs.readFileSync(filePath);
    res.writeHead(200, { 'Content-Type': types[ext] || 'text/plain' });
    res.end(content);
  } catch {
    res.writeHead(404);
    res.end('Not found');
  }
}

function readBody(req) {
  return new Promise((resolve) => {
    let body = '';
    req.on('data', c => body += c);
    req.on('end', () => resolve(body));
  });
}

function jsonResponse(res, code, data) {
  res.writeHead(code, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(data));
}

function audit(req, action) {
  const email = req.headers['x-auth-request-email'] || 'anon';
  const line = `[${new Date().toISOString()}] AUDIT user=${email} action=${action}\n`;
  try { fs.appendFileSync(LOG_FILE, line); } catch {}
}

function checkWebhookBearer(req) {
  const expected = process.env.WEBHOOK_BEARER_TOKEN;
  if (!expected) return true; // local dev: no token configured, no check
  const auth = req.headers['authorization'] || '';
  const provided = auth.startsWith('Bearer ') ? auth.slice(7) : '';
  const a = Buffer.from(provided);
  const b = Buffer.from(expected);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);

  if (req.method === 'GET') {
    if (url.pathname === '/' || url.pathname === '/index.html') {
      return serveStatic(res, path.join(__dirname, 'public/index.html'));
    }
    if (url.pathname === '/style.css') {
      return serveStatic(res, path.join(__dirname, 'public/style.css'));
    }
    if (url.pathname === '/health') {
      return jsonResponse(res, 200, { status: 'ok', mode: 'live', state });
    }
    if (url.pathname === '/status') {
      return jsonResponse(res, 200, { state, version: getVersion(), title: DEMO_TITLE, options: currentOptions, diagnosis: currentDiagnosis, trigger: currentTrigger });
    }
    if (url.pathname === '/events') {
      res.writeHead(200, {
        'Content-Type': 'text/event-stream',
        'Cache-Control': 'no-cache',
        'Connection': 'keep-alive',
        'X-Accel-Buffering': 'no',
      });
      res.write(`event: status\ndata: ${JSON.stringify({ state, version: getVersion() })}\n\n`);
      sseClients.push(res);
      req.on('close', () => {
        sseClients = sseClients.filter(c => c !== res);
      });
      return;
    }
    // Try static files in public/
    const filePath = path.join(__dirname, 'public', url.pathname);
    if (fs.existsSync(filePath) && fs.statSync(filePath).isFile()) {
      return serveStatic(res, filePath);
    }
    return jsonResponse(res, 404, { error: 'Not found' });
  }

  if (req.method === 'POST') {
    const body = await readBody(req);
    let payload;
    try { payload = JSON.parse(body || '{}'); }
    catch { return jsonResponse(res, 400, { error: 'Invalid JSON' }); }

    if (url.pathname === '/webhook') {
      if (!checkWebhookBearer(req)) {
        return jsonResponse(res, 401, { error: 'Unauthorized' });
      }
      if (Array.isArray(payload)) payload = payload[0] || {};
      const triggerInfo = {
        alarm_name: payload.alarm_name || payload['Alarm Name'] || payload.alarmName || 'Unknown',
        severity: payload.severity || payload['Severity'] || 'Unknown',
        status: payload.status || payload['Alarm Status'] || 'Unknown',
        host: payload.host || payload['Host'] || 'Unknown',
        agent: payload.agent || payload['Agent'] || 'Unknown',
        component: payload.component_name || payload['Component Name'] || 'Unknown',
        message: payload.message || payload['Message'] || '',
        alert_external_id: payload.alert_external_id || payload['Alert External ID'] || '',
        metric_name: payload.metric_name || payload['Metric Name'] || '',
        metric_value: payload.metric_value || payload['Metric Value'] || '',
        caution_threshold: payload.caution_threshold || '',
        danger_threshold: payload.danger_threshold || '',
        alarm_type: payload.alarm_type || payload['Alarm Type'] || '',
        timestamp: new Date().toISOString(),
      };
      console.log(`[WEBHOOK] Received alarm: ${triggerInfo.alarm_name}`);
      jsonResponse(res, 200, { status: 'accepted' });
      runPhase1(triggerInfo, 'DX Operational Intelligence');
      return;
    }

    if (url.pathname === '/simulate') {
      const triggerInfo = {
        alarm_name: payload.alarm_name || 'Threadly Log Error Rate',
        severity: payload.severity || 'Danger',
        status: payload.status || 'OPEN',
        host: payload.host || DEMO_HOST,
        agent: payload.agent || 'apmia-threadly|Threadly|Spring Boot Agent',
        component: payload.component || 'ProductController',
        message: payload.message || payload.error || 'ArithmeticException: / by zero in DiscountCalculator.percentOff',
        alert_external_id: payload.alert_external_id || 'SuperDomain:Threadly:Log Error Rate',
        metric_name: payload.metric_name || 'Log Events|ERROR:Rate',
        metric_value: payload.metric_value || '42',
        caution_threshold: payload.caution_threshold || '5',
        danger_threshold: payload.danger_threshold || '10',
        alarm_type: payload.alarm_type || 'LOG_ALERT',
        timestamp: new Date().toISOString(),
      };
      console.log(`[SIMULATE] Alarm: ${triggerInfo.alarm_name}`);
      audit(req, 'simulate');
      jsonResponse(res, 200, { status: 'accepted' });
      runPhase1(triggerInfo, 'DX Operational Intelligence (simulated)');
      return;
    }

    if (url.pathname === '/execute') {
      const optionId = payload.optionId;
      if (!optionId) return jsonResponse(res, 400, { error: "Missing 'optionId'" });
      console.log(`[EXECUTE] User chose option ${optionId}`);
      audit(req, `execute option=${optionId}`);
      jsonResponse(res, 200, { status: 'accepted' });
      runPhase2(optionId);
      return;
    }

    if (url.pathname === '/reset') {
      audit(req, 'reset');
      setState('idle');
      currentOptions = null;
      currentDiagnosis = null;
      currentTrigger = null;
      lastDispatch = 0;
      return jsonResponse(res, 200, { status: 'reset' });
    }

    if (url.pathname === '/deploy') {
      const version = payload.version || 'v1.1';
      console.log(`[DEPLOY] Deploying ${version}...`);
      audit(req, `deploy version=${version}`);
      broadcast('deploy_start', { version });
      const deployScript = path.join(__dirname, 'deploy.sh');
      const proc = spawn('bash', [deployScript, version], {
        stdio: ['ignore', 'pipe', 'pipe'],
      });
      let output = '';
      proc.stdout.on('data', (chunk) => { output += chunk.toString(); });
      proc.stderr.on('data', (chunk) => { output += chunk.toString(); });
      proc.on('close', (code) => {
        const success = code === 0;
        console.log(`[DEPLOY] ${version} ${success ? 'succeeded' : 'failed'} (exit ${code})`);
        broadcast('deploy_complete', { version, success, output: output.trim() });
        broadcast('status', { state, version: getVersion() });
      });
      return jsonResponse(res, 200, { status: 'deploying', version });
    }

    return jsonResponse(res, 404, { error: 'Not found' });
  }
});

// Bind on all interfaces: Caddy is the public ingress (gates on bearer + oauth)
// AND fluent-bit reaches us via docker's host-gateway, not VM localhost. The
// GCP firewall (only 80/443 open) is the actual external boundary for :5000.
server.listen(PORT, '0.0.0.0', () => {
  console.log(`${'='.repeat(60)}`);
  console.log(`  DX OI Closed-Loop Remediation Demo v2`);
  console.log(`${'='.repeat(60)}`);
  console.log(`  http://localhost:${PORT}`);
  console.log(`  State: ${state}`);
  console.log(`  Version: ${getVersion()}`);
  console.log(`${'='.repeat(60)}`);
});
