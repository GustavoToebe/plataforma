// Fumaça do que o consumidor recebe: cada entrada do `exports` resolve nas duas condições (import e require) e traz o
// que se espera. Roda depois do build; o pacote se importa pelo próprio nome (autorreferência do Node).
import { createRequire } from 'node:module';

const esperado = {
  '@plataforma/web': ['caminhoDaApi', 'cpfValido', 'ErroApi', 'mensagemApi', 'comRenovacao', 'significado'],
  '@plataforma/web/destino-api': ['caminhoDaApi'],
  '@plataforma/web/formatos': ['cnpjValido', 'formatarTelefone', 'UFS'],
  '@plataforma/web/erros': ['ErroApi', 'mensagemApi', 'codigoApi', 'corpoDoErro'],
  '@plataforma/web/autenticacao': ['umaPorVez', 'comRenovacao', 'deveRenovar', 'desfechoDoRefresh', 'cabecalhosDeCredencial'],
  '@plataforma/web/tokens': ['significado', 'microBadge', 'blocos', 'tabela'],
};

const require = createRequire(import.meta.url);
let falhas = 0;

for (const [especificador, nomes] of Object.entries(esperado)) {
  const modulos = { import: await import(especificador), require: require(especificador) };
  for (const [condicao, modulo] of Object.entries(modulos)) {
    const faltando = nomes.filter((nome) => !(nome in modulo));
    if (faltando.length > 0) {
      console.error(`${especificador} (${condicao}): faltam ${faltando.join(', ')}`);
      falhas++;
    }
  }
}

// Mesma classe nas duas cargas não é garantido (pacote duplo): `mensagemApi` precisa funcionar entre elas.
const viaImport = await import('@plataforma/web');
const viaRequire = require('@plataforma/web');
const erro = new viaRequire.ErroApi(400, null, 'vinda do CJS');
if (viaImport.mensagemApi(erro, 'x') !== 'vinda do CJS') {
  console.error('mensagemApi (ESM) não reconheceu o ErroApi do CJS');
  falhas++;
}

if (falhas > 0) process.exit(1);
console.log('dist ok: entradas import e require resolvem');
