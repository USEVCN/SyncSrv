import { spawnSync } from 'node:child_process';

// npm on Windows normally provides `python`; POSIX installations use `python3`.
const command = process.env.SYNCSRV_PYTHON || (process.platform === 'win32' ? 'python' : 'python3');
const result = spawnSync(command, process.argv.slice(2), {
  stdio: 'inherit',
  env: { ...process.env, PYTHONUTF8: '1' },
});
if (result.error) {
  process.stderr.write('Python 3.9+ is required for build and license checks. Set SYNCSRV_PYTHON to its executable path.\n');
  process.exit(1);
}
process.exit(result.status ?? 1);
