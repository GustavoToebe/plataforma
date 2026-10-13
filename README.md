# servirea-comum

Código que o **Servirea** e a **Central** (e qualquer sistema futuro da família) precisam fazer **igual**. Fica fora dos sistemas para ser corrigido em um lugar só.

## O que entra aqui

Só o que é igual nos dois lados e não depende do domínio: contrato de assinatura da integração, TOTP, limites de login e, depois, filtro de monitoramento e componentes de tela comuns. Regra de negócio de um sistema (financeiro, escalas, cobranças) **fica no sistema**.

## Estrutura

| Pasta | O que é | Como é publicado |
|---|---|---|
| `java/` | Biblioteca Java 21 sem Spring e sem banco (`br.com.servirea:servirea-comum`) | GitHub Packages (Maven), por tag `java-vX.Y.Z` |
| `angular/` | (futuro) componentes e utilitários Angular comuns | GitHub Packages (npm), por tag `ui-vX.Y.Z` |

## Etapa 1 (Java): o que já está aqui

Pacote `br.com.servirea.comum.seguranca`:

- `HmacAssinatura`: assinatura HMAC da integração (um lado assina, o outro confere).
- `Totp`: código de uso único do MFA (RFC 6238).

Os testes são os mesmos dos dois sistemas, com os vetores oficiais.

Pacote `br.com.servirea.comum.login` (desde a 0.2.0): `LimiteLogin` (limite de tentativas por IP e por conta, antes do BCrypt), `ContadorJanelas` (a interface do contador), `ContadorEmMemoria` e `LimiteLoginException` (`RuntimeException` com os segundos de espera e o código `LOGIN_LIMITADO`). A biblioteca não depende de Spring, JDBC nem de HTTP: cada sistema entrega o contador, o `Clock` e o sal pelo construtor público, e mapeia a exceção para HTTP 429. O `ContadorBanco` (JDBC) e o bean Spring do limitador ficam em cada sistema.

## Como usar num sistema

1. No `pom.xml` do sistema:
   ```xml
   <repositories>
     <repository><id>github</id><url>https://maven.pkg.github.com/GustavoToebe/servirea-comum</url></repository>
   </repositories>
   <dependency>
     <groupId>br.com.servirea</groupId><artifactId>servirea-comum</artifactId><version>0.2.0</version>
   </dependency>
   ```
2. O Maven precisa de um token com `read:packages` (servidor `github` no `settings.xml`). No GitHub Actions use `secrets.GITHUB_TOKEN` com `packages: read`; num build Docker ou local, um token pessoal passado como segredo de build, nunca gravado na imagem.
3. Fixe sempre a versão. Para atualizar, mude a versão no `pom.xml` do sistema e rode os testes dele.

## Como publicar uma versão

1. Mude a versão em `java/pom.xml` (ex.: `0.3.0`) numa branch curta e abra o pull request. O merge é do Gustavo.
2. Depois do merge, crie a tag `java-v0.3.0` no commit da `main`. O workflow testa e publica sozinho.
3. Versões publicadas **não se alteram**: correção vira versão nova.

## Regras de mudança

- Mudou comportamento que os dois lados usam (ex.: formato da assinatura)? Suba versão **maior** e atualize os dois sistemas juntos.
- Mudança só interna ou correção compatível: versão de correção (0.2.0 para 0.2.1).
- Cada classe tem teste aqui. Os sistemas mantêm só os testes que dependem do seu domínio.

## Próximas etapas

1. `MonitoramentoFilter` e `IntegracaoNonceService`.
2. Componentes Angular comuns (barra de filtros, cabeçalho, modal, estado de lista, rodapé de formulário, favoritos e busca global).
3. Tirar `scripts/verificar-copias.py` da Central quando as cópias acabarem.

Maven Packages exige token pessoal classic com `read:packages` para leitura fora do Actions; fine-grained não é suportado. Ver a [documentação oficial](https://docs.github.com/en/packages/learn-github-packages/about-permissions-for-github-packages).

- [Instruções para agentes](AGENTS.md) e [registro de retomada](docs/RETOMADA.md).
