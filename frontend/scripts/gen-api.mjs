import { spawnSync } from 'node:child_process';

import { loadApiTarget } from './apiTarget.mjs';

const OUTPUT_FILE = 'src/api/schema.d.ts';

let target;
try {
  target = loadApiTarget('development');
} catch (error) {
  console.error(error instanceof Error ? error.message : error);
  process.exit(1);
}

const documentUrl = `${target}/v3/api-docs`;
console.log(`Generating ${OUTPUT_FILE} from ${documentUrl}`);

const result = spawnSync('openapi-typescript', [documentUrl, '-o', OUTPUT_FILE], {
  stdio: 'inherit',
  shell: process.platform === 'win32',
});
if (result.error) {
  console.error(`Could not run openapi-typescript: ${result.error.message}`);
}
process.exit(result.status ?? 1);
