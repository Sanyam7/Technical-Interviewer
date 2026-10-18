'use strict';
// Test harness for a JavaScript or TypeScript solution. Generated per run; runs inside the sandbox container.
// Protocol (one line each, prefixed with the run's nonce): RESULT <index> <OK|ERROR|TIMEOUT> <millis> <json>, then DONE;
// or COMPILE_ERROR followed by the message. It reports what the function returned; the expected outputs are not in the
// container and are compared by the server. TypeScript is type-stripped by Node, not type-checked.
const fs = require('node:fs');
const vm = require('node:vm');

const NONCE = process.argv[2];
const FUNCTION_NAME = '__FUNCTION__';
const LANGUAGE = '__LANGUAGE__';
const TIME_LIMIT_MS = __TIME_LIMIT_MS__;

const write = (line) => fs.writeSync(1, `${line}\n`);
const emit = (index, status, millis, payload) => write(`${NONCE} RESULT ${index} ${status} ${millis} ${payload}`);
const compileError = (message) => {
  write(`${NONCE} COMPILE_ERROR`);
  write(String(message).slice(0, 8000));
  process.exit(0);
};
const show = (value) => {
  if (typeof value === 'string') return value;
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
};
const describe = (error) => {
  if (!error || typeof error !== 'object') return String(error);
  const where = String(error.stack || '')
    .split('\n')
    .find((line) => line.includes('solution:'));
  const line = where ? where.match(/solution:(\d+)/) : null;
  const text = `${error.name || 'Error'}: ${error.message}${line ? ` (line ${line[1]})` : ''}`;
  return text.slice(0, 300);
};

let source = fs.readFileSync(LANGUAGE === 'typescript' ? 'solution.ts' : 'solution.js', 'utf8');
// Candidates often write "export function"; the harness calls the function by name, so the keyword is dropped.
source = source.replace(/^(\s*)export\s+(default\s+)?/gm, '$1');
if (LANGUAGE === 'typescript') {
  try {
    source = require('node:module').stripTypeScriptTypes(source, { mode: 'transform' });
  } catch (error) {
    compileError(error && error.message ? error.message : error);
  }
}

const print = (...parts) => write(parts.map(show).join(' '));
const context = vm.createContext({
  console: { log: print, info: print, warn: print, error: print, debug: print },
});
try {
  new vm.Script(source, { filename: 'solution' }).runInContext(context, { timeout: TIME_LIMIT_MS });
} catch (error) {
  compileError(error && error.stack ? error.stack.split('\n').slice(0, 6).join('\n') : error);
}
if (vm.runInContext(`typeof ${FUNCTION_NAME}`, context) !== 'function') {
  compileError(`function ${FUNCTION_NAME} is not defined`);
}

const tests = JSON.parse(fs.readFileSync('input.json', 'utf8'));
const call = new vm.Script(
  `(() => { const __result = ${FUNCTION_NAME}(...JSON.parse(__arguments)); return JSON.stringify(__result === undefined ? null : __result); })()`,
  { filename: 'harness-call' },
);
for (let index = 0; index < tests.length; index++) {
  context.__arguments = JSON.stringify(tests[index].args);
  const start = process.hrtime.bigint();
  const elapsed = () => Number((process.hrtime.bigint() - start) / 1000000n);
  try {
    const output = call.runInContext(context, { timeout: TIME_LIMIT_MS });
    emit(index, 'OK', elapsed(), output === undefined ? 'null' : output);
  } catch (error) {
    if (error && error.code === 'ERR_SCRIPT_EXECUTION_TIMEOUT') {
      emit(index, 'TIMEOUT', elapsed(), 'null');
    } else {
      emit(index, 'ERROR', elapsed(), JSON.stringify(describe(error)));
    }
  }
}
write(`${NONCE} DONE`);
