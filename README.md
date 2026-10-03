# servirea-comum

Código que o **Servirea** e a **Central** (e qualquer sistema futuro da família) precisam fazer **igual**. Fica fora dos sistemas para ser corrigido em um lugar só.

## O que entra aqui

Só o que é igual nos dois lados e não depende do domínio: contrato de assinatura da integração, TOTP, limites de login, filtro de monitoramento e, depois, componentes de tela comuns. Regra de negócio de um sistema (financeiro, escalas, cobranças) **fica no sistema**.

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

## Como usar num sistema

1. No `pom.xml` do sistema:
   ```xml
   <repositories>
     <repository><id>github</id><url>https://maven.pkg.github.com/GustavoToebe/servirea-comum</url></repository>
   </repositories>
   <dependency>
     <groupId>br.com.servirea</groupId><artifactId>servirea-comum</artifactId><version>0.1.0</version>
   </dependency>
   ```
2. O Maven precisa de um token com `read:packages` (servidor `github` no `settings.xml`). No GitHub Actions use `secrets.GITHUB_TOKEN` com `packages: read`; num build Docker ou local, um token pessoal passado como segredo de build, nunca gravado na imagem.
3. Fixe sempre a versão. Para atualizar, mude a versão no `pom.xml` do sistema e rode os testes dele.

## Como publicar uma versão

1. Mude a versão em `java/pom.xml` (ex.: `0.2.0`) e registre o commit na main autorizada.
2. Crie a tag `java-v0.2.0` no mesmo commit. O workflow testa e publica sozinho.
3. Versões publicadas **não se alteram**: correção vira versão nova.

## Regras de mudança

- Mudou comportamento que os dois lados usam (ex.: formato da assinatura)? Suba versão **maior** e atualize os dois sistemas juntos.
- Mudança só interna ou correção compatível: versão de correção (0.1.0 para 0.1.1).
- Cada classe tem teste aqui. Os sistemas mantêm só os testes que dependem do seu domínio.

## Próximas etapas

1. `ContadorJanelas`, `LimiteLogin`, `ContadorEmMemoria`, `LimiteLoginException` (pedem separar o contador do banco: a interface fica aqui, a implementação JDBC em cada sistema).
2. `MonitoramentoFilter` e `IntegracaoNonceService`.
3. Componentes Angular comuns (barra de filtros, cabeçalho, modal, estado de lista, rodapé de formulário, favoritos e busca global).
4. Tirar `scripts/verificar-copias.py` da Central quando as cópias acabarem.

## Versão 0.2.0 — núcleo de limites de login

Pacote `br.com.servirea.comum.login`: ContadorJanelas público, ContadorEmMemoria público, LimiteLogin e LimiteLoginException. As fontes dos dois backends foram comparadas e são iguais após normalizar produto/pacote. O núcleo foi separado da injeção Spring e do mapeamento HTTP; não depende de Spring, JDBC ou ApiException. A aplicação fornece contador, Clock e sal pelo construtor público. A exceção mantém mensagem, segundos e código LOGIN_LIMITADO, mas é RuntimeException: uma futura integração precisa mapear HTTP 429 no domínio.

ContadorBanco permanece em cada backend. Quando houver integração com 0.2.0, deverá implementar a interface da biblioteca e o bean Spring deverá construir o limitador com a configuração própria. **Os backends seguem fixados em 0.1.0 e mantêm suas classes de login**: nada dessa segunda etapa foi ligado a eles. Não trocar automaticamente a versão.

Maven Packages exige token pessoal classic com read:packages para leitura fora do Actions; fine-grained não é suportado. Permissões Maven são herdadas do repositório; Manage Actions access pertence aos registries com permissões granulares. Ver [documentação oficial](https://docs.github.com/en/packages/learn-github-packages/about-permissions-for-github-packages).

- [Instruções para agentes](AGENTS.md) e [registro de retomada](docs/RETOMADA.md).
