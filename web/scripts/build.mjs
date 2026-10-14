// Compila duas vezes (ESM e CJS) e marca a pasta CJS como CommonJS: o pacote é "type": "module".
import { spawnSync } from 'node:child_process';
import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const raiz = join(dirname(fileURLToPath(import.meta.url)), '..');
const tsc = createRequire(import.meta.url).resolve('typescript/bin/tsc');

rmSync(join(raiz, 'dist'), { recursive: true, force: true });

for (const configuracao of ['tsconfig.esm.json', 'tsconfig.cjs.json']) {
  const resultado = spawnSync(process.execPath, [tsc, '-p', join(raiz, configuracao)], { stdio: 'inherit' });
  if (resultado.status !== 0) {
    process.exit(resultado.status ?? 1);
  }
}

mkdirSync(join(raiz, 'dist', 'cjs'), { recursive: true });
writeFileSync(join(raiz, 'dist', 'cjs', 'package.json'), '{"type":"commonjs"}\n');
