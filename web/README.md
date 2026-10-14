# @plataforma/web

TypeScript puro da plataforma para os fronts (Angular, Expo ou qualquer outro): sem Angular, sem React e **sem
dependência de runtime**. Só infraestrutura, nunca regra de negócio. Cada front fala só com a própria API, na mesma
origem.

| Entrada | O que tem |
|---|---|
| `@plataforma/web/destino-api` | `caminhoDaApi(destino, api, base)`: decide se uma URL é da própria API (origem e fronteira do caminho, nunca `startsWith`) |
| `@plataforma/web/formatos` | CPF, CNPJ alfanumérico, CEP, UF, telefone e e-mail: `formatar*` (máscara enquanto digita) e `*Valido`, com as regras do `Formatos.java` dos backends |
| `@plataforma/web/erros` | `ApiError`, `ErroApi`, `mensagemApi(erro, fallback)`, `codigoApi(erro)`, `errosPorCampo`, `erroDeConexao`, `corpoDoErro` |
| `@plataforma/web/autenticacao` | Lógica do cliente de autenticação, sem HTTP: `umaPorVez`, `comRenovacao`, `deveRenovar`, `desfechoDoRefresh`, XSRF |
| `@plataforma/web/tokens` | Os 3 pilares do design: cores de significado, micro-badge, blocos e tabela (a marca fica em cada app) |

Tudo também sai da raiz (`@plataforma/web`). O pacote tem `sideEffects: false`.

## Como usar

O pacote **não está num registro de pacotes**: é um `.tgz` anexado à Release do GitHub, então não precisa de token em
lugar nenhum (PC, Docker, EAS, CI). Fixe a versão pela URL da Release, no `package.json` do front:

```json
"@plataforma/web": "https://github.com/GustavoToebe/plataforma/releases/download/web-v0.1.0/plataforma-web-0.1.0.tgz"
```

O TypeScript do front precisa de `moduleResolution` `bundler` (Angular e Expo já usam) ou `node16`/`nodenext`: o
`node10` não lê subcaminhos do `exports`.

O `package-lock.json` grava o hash do arquivo: versão publicada nunca muda. Subir a versão é trocar a URL e rodar
`npm install`.

### Cliente de autenticação (o que é do pacote e o que é do app)

O pacote decide; o app envia a requisição (`HttpClient`, `fetch`) e informa o que é dele (nome do cookie XSRF, rotas
que não renovam, códigos que não são sessão expirada, onde guardar o token).

```ts
import { comRenovacao, desfechoDoRefresh, deveRenovar, umaPorVez } from '@plataforma/web';

const renovar = umaPorVez(async () => {
  const resposta = await fetch('/api/auth/refresh', { method: 'POST' /* ... */ });
  if (desfechoDoRefresh(resposta.status) === 'encerrar') return false; // 401 ou 403: limpar a sessão local
  // 2xx: guardar a sessão nova; outra coisa (5xx, sem rede): lançar, a sessão continua
  return true;
});

const resposta = await comRenovacao(() => fetch(url, opcoes), {
  deveRenovar: (r) => deveRenovar({ status: r.status }, { codigosSemRenovar: ['CONFLITO_EDICAO', 'MFA_*'] }),
  renovar,
  aoEncerrar: () => irParaOLogin(),
});
```

- Vários 401 ao mesmo tempo esperam **uma** renovação e cada requisição se repete **uma** vez.
- Renovação recusada (401 ou 403) encerra a sessão. Erro ao renovar (sem rede, 5xx) propaga e **não** encerra.
- `X-XSRF-TOKEN` só vai em escrita (POST, PUT, PATCH, DELETE) e só para a própria API (`caminhoDaApi`).

## Diferenças conhecidas em relação ao backend

- **E-mail:** a expressão é a de `Formatos.EMAIL`. O backend soma a ela o `@Email` do Hibernate, que é mais rígido
  (por exemplo, pontos seguidos no domínio): o servidor sempre decide.
- **Vazio:** o backend devolve `null` para vazio; aqui `*Valido('')` é `false` e `formatar*('')` é `''`.
- **Fora do pacote:** RG e sexo, que só o Servirea usa.

As fixtures `test/fixtures/formatos.json` saem dos `FormatosTest.java` e dos `formatos.spec.ts`. Mudou uma regra de
formato, mude o `Formatos.java` e o pacote juntos.

## Desenvolvimento

```bash
cd web
npm ci
npm run typecheck
npm run test:cobertura   # limite de 95% em src/
npm run build            # dist/esm e dist/cjs (o pacote é dual)
npm run verificar:dist   # cada entrada do exports resolve em import e em require
npm pack                 # gera o .tgz
```

## Como publicar

1. Branch curta saída da `main` e pull request (o CI roda `typecheck`, testes com cobertura, build e `npm pack`).
2. Subir `version` no `package.json`, depois do merge.
3. Tag `web-vX.Y.Z`, igual à versão do `package.json`. O workflow cria a Release com o `.tgz` anexado.
4. Versão publicada é imutável: se algo falhar no meio, sobe o patch seguinte.

A tag `web-v*` não dispara o `java.yml` (só `v*`), e a tag `v*` não dispara o `web.yml`.
