# plataforma

Parent Maven e código que os backends da plataforma (Central, Servirea, consultoria-app e os próximos) precisam fazer **igual**. Fica fora dos sistemas para ser corrigido em um lugar só.

## O que entra aqui

Só infraestrutura que é igual nos sistemas e não depende do domínio: assinatura da integração, TOTP, limites de login e, aos poucos, erros e filtros web, monitoramento, base de testes, login e o lado do app do contrato com a Central. Regra de negócio de um sistema (financeiro, escalas, cobranças, treinos) **fica no sistema**.

## Estrutura

| Pasta | O que é | Artefato |
|---|---|---|
| `plataforma-parent/` | POM pai dos backends: Java 25, Spring Boot 4.1.x, Error Prone, JaCoCo, Surefire/Failsafe, imagem do Postgres dos testes | `br.com.plataforma:plataforma-parent` |
| `plataforma-comum/plataforma-comum-seguranca/` | HMAC da integração, TOTP e núcleo do limite de login; sem Spring e sem banco | `br.com.plataforma:plataforma-comum-seguranca` |
| `plataforma-comum/plataforma-comum-web/` | `ApiError` e `ApiException` (e as exceções 400/401/403/404/409/429), handler global de erros, `RequestIdFilter`, `LimiteCorpoFilter`; autoconfigurado (`PlataformaWebAutoConfiguration`) | `br.com.plataforma:plataforma-comum-web` |
| `plataforma-comum/plataforma-comum-monitoramento/` | `GET /monitoramento/metrics` em texto Prometheus (requisições, JVM, pool de conexões) com credencial exclusiva (`MonitoramentoFilter`); métricas do domínio entram por `MetricasDeDominio`; autoconfigurado | `br.com.plataforma:plataforma-comum-monitoramento` |
| `plataforma-comum/plataforma-comum-teste/` | Apoio aos testes (escopo `test`): `PostgresDeTeste` (Postgres descartável na imagem do parent) e `RegrasDeArquitetura` (regras ArchUnit comuns) | `br.com.plataforma:plataforma-comum-teste` |
| `pom.xml` (raiz) | Só agrega os módulos; todos saem com a mesma versão | não é publicado |

Os módulos seguintes (`teste`, `auth`, `central`) entram um por vez.

Pacote `br.com.plataforma.comum.seguranca`: `HmacAssinatura` (um lado assina, o outro confere) e `Totp` (RFC 6238). Pacote `br.com.plataforma.comum.seguranca.login`: `LimiteLogin` (limite de tentativas por IP e por conta, antes do BCrypt), `ContadorJanelas` (a interface do contador), `ContadorEmMemoria` e `LimiteLoginException` (`RuntimeException` com os segundos de espera e o código `LOGIN_LIMITADO`). Cada sistema entrega o contador, o `Clock` e o sal pelo construtor e mapeia a exceção para HTTP 429.

### Módulo web (1.1.0)

Pacote `br.com.plataforma.comum.web`. Basta ter a dependência: a autoconfiguração registra o `RequestIdFilter` (X-Request-Id, MDC `requestId`), o `LimiteCorpoFilter` (1 MiB por padrão, multipart fora) e o `GlobalExceptionHandler`, todos cedendo a um bean do sistema com o mesmo nome/tipo. Propriedades `plataforma.web.*`: `limite-corpo-ativo` (true), `limite-corpo-bytes` (1048576), `prefixo-codigo-proprio` (`br.com.`, usado no log do 500).

- Todo erro sai como `ApiError` (`timestamp, status, error, message, codigo, path, requestId, fieldErrors`), inclusive 404/405/415 do MVC (código `NAO_ENCONTRADO` no 404), 409 `CONFLITO_EDICAO` (lock otimista), 413 `CORPO_EXCEDIDO` e 429 do limite de login. Exceção de negócio: estenda `ApiException` ou use `BadRequestException` (`DADOS_INVALIDOS`), `ConflictException`, `ForbiddenException`, `UnauthorizedException`, `ResourceNotFoundException`, `TooManyRequestsException` (os cinco últimos sem código padrão).
- O 500 devolve mensagem genérica com o requestId e vai para o log **sem dados** (classes da cadeia de causas e até 5 pontos do código do sistema; nunca mensagem nem pilha completa, por causa da LGPD).
- Para saber dos erros (ex.: relatório de erros do Servirea), declare um bean `OuvinteDeErros`.
- Fica de fora (é do domínio de cada sistema): `Formatos` (CPF, CEP...), exceções com regra própria (ex.: `ValidacaoException` 422 do consultoria-app) e o `Retry-After`, que os controllers de login e MFA põem.

### Módulo monitoramento (1.2.0)

Pacote `br.com.plataforma.comum.monitoramento`. A autoconfiguração publica o `MetricasController` (`GET /monitoramento/metrics`, `@PreAuthorize("hasRole('MONITORAMENTO')")`, `Cache-Control: no-store`). O sistema faz duas coisas: (1) na cadeia de segurança, `addFilterBefore(new MonitoramentoFilter(token), JwtAuthenticationFilter.class)` com `monitoramento.token` (env `MONITORAMENTO_TOKEN`, 32 a 128 caracteres `[A-Za-z0-9_-]`; sem token a coleta fica fechada com 401) e `@EnableMethodSecurity`; (2) se tiver métricas próprias, um bean `MetricasDeDominio` que escreve em `SaidaPrometheus` (só agregados; se lançar exceção o grupo é omitido). Métricas comuns: `ecossistema_http_servidor_duracao_seconds`, `ecossistema_http_cliente_duracao_seconds` (por método e classe de status), JVM, processo e Hikari. Fica nos sistemas: o bloco de domínio (Central: instâncias por nível; Servirea: fila de envios), o log de requisição lenta e o `logback-spring.xml` do Servirea.

### Módulo teste (1.3.0)

Pacote `br.com.plataforma.comum.teste`, sempre com `<scope>test</scope>`. `PostgresDeTeste.iniciar(banco, usuario, senha)` sobe um Postgres descartável (tipo `org.testcontainers.postgresql.PostgreSQLContainer`, o do Testcontainers 2; o antigo `containers.PostgreSQLContainer` está deprecado) com a imagem da propriedade `plataforma.postgres.imagem` do parent (padrão `postgres:18-alpine` quando o teste roda fora do Maven); guarde o container num campo `static` da classe base dos testes de integração e chame `PostgresDeTeste.registrar(registry, container)` num `@DynamicPropertySource`. `RegrasDeArquitetura` traz as regras do `ArquiteturaTest` que valem para todos: `CONTROLLER_SO_FALA_COM_SERVICE`, `CONTROLLER_NAO_ABRE_TRANSACAO` (Spring e Jakarta), `NADA_DEPENDE_DE_CONTROLLER`, `JACKSON_TRES`, `SEM_INJECAO_POR_CAMPO`, `SEM_JAVA_UTIL_LOGGING`, `SEM_SAIDA_PADRAO` e `sqlNativoSoEm(lista)` (pega `JdbcTemplate`/`JdbcClient`, qualquer `createNative...`, `@Query(nativeQuery = true)` e `@NativeQuery`) com `exigirQueAsExcecoesExistem(...)` para a lista de exceções. Cada sistema chama `regra.check(codigo)` e mantém no seu teste o que é só dele (a Central não é multi-tenant, por exemplo) e a sua lista de SQL nativo. As regras citam as classes do Spring por nome, então o módulo não força dependência no sistema.

## Como usar num sistema

1. No `pom.xml` do sistema:
   ```xml
   <parent>
     <groupId>br.com.plataforma</groupId><artifactId>plataforma-parent</artifactId><version>1.0.0</version>
   </parent>
   <repositories>
     <repository><id>github</id><url>https://maven.pkg.github.com/GustavoToebe/plataforma</url></repository>
   </repositories>
   <dependency>
     <groupId>br.com.plataforma</groupId><artifactId>plataforma-comum-seguranca</artifactId><version>1.0.0</version>
   </dependency>
   ```
   Copie também o `.mvn/jvm.config` da raiz deste repositório (aberturas do javac para o Error Prone).
2. O Maven precisa de um token com `read:packages` (servidor `github` no `settings.xml`). No GitHub Actions use `secrets.GITHUB_TOKEN` com `packages: read`; num build Docker ou local, um token pessoal passado como segredo de build, nunca gravado na imagem.
3. Fixe sempre a versão. Para atualizar, mude a versão no `pom.xml` do sistema e rode os testes dele.
4. Testes de integração com Postgres: leia a imagem da propriedade de sistema `plataforma.postgres.imagem` (o parent a repassa ao Surefire). Quem ainda não pode usar o 18 sobrescreve a propriedade no próprio `pom.xml`.

## Como publicar uma versão

1. Numa branch curta, mude a versão em todos os `pom.xml` (`mvn versions:set -DnewVersion=1.1.0`) e abra o pull request.
2. Com o CI do pull request verde, crie a tag `v1.1.0` na branch. O workflow testa e publica todos os módulos de uma vez (`deployAtEnd`).
3. Versões publicadas **não se alteram**: correção vira versão nova. O merge do pull request é do Gustavo, sem squash.

## Regras de mudança

- Mudou comportamento que os sistemas usam (ex.: formato da assinatura)? Suba versão **maior** e atualize os sistemas juntos.
- Mudança só interna ou correção compatível: versão de correção (1.0.0 para 1.0.1).
- Cada classe tem teste aqui. Os sistemas mantêm só os testes que dependem do seu domínio.

Maven Packages exige token pessoal classic com `read:packages` para leitura fora do Actions; fine-grained não é suportado. Ver a [documentação oficial](https://docs.github.com/en/packages/learn-github-packages/about-permissions-for-github-packages).

- [Instruções para agentes](AGENTS.md) e [registro de retomada](docs/RETOMADA.md).
