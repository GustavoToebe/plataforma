# Retomada

## Versões publicadas
- **`br.com.servirea:servirea-comum` 0.1.0 e 0.2.0** (nome antigo, Java 21, tags `java-v0.1.0` e `java-v0.2.0`): continuam publicadas e imutáveis. Estavam em uso pela Central e pelo Servirea até a adoção da 1.0.0.
- **`br.com.plataforma` 1.0.0** (tag `v1.0.0`): `plataforma-parent` e `plataforma-comum-seguranca` (HMAC, TOTP e `seguranca.login`, o mesmo código da 0.2.0 com os pacotes renomeados). Java 25. Duas correções do Error Prone, sem mudar o comportamento: `toUpperCase(Locale.ROOT)` no HMAC e laço por `charAt` no TOTP.

- **`plataforma-comum-web` 1.1.0** (tag `v1.1.0`, PR empilhado sobre o da 1.0.0): erro padrão da API, request id e limite de corpo. Extraído da Central e comparado com Servirea e consultoria-app. Decisões: `ApiException` com `codigo` e `cause` (o Servirea nunca preenchia o código); log do 500 sem dados (o do consultoria-app, por LGPD, no lugar do stack trace de Central e Servirea); lock otimista vira 409 `CONFLITO_EDICAO` (no Servirea era `codigo=null`, na Central caía no 500); gancho `OuvinteDeErros` no lugar do `RelatorioDeErros` do Servirea; `LimiteCorpoFilter` do consultoria-app com limite configurável. Fica nos sistemas: `Formatos`, `ClientIp`, `ZipSeguro`, `PaginaLista`, `Exigir`, `Fuso`, as exceções com regra própria do consultoria-app, a medição de requisição lenta e o atributo `servire.jwtMs` do `RequestIdFilter` do Servirea (um filtro do sistema pode ficar ao lado ou estender o do módulo).

- **`plataforma-comum-monitoramento` 1.2.0** (tag `v1.2.0`, PR empilhado sobre o do `web`): `MonitoramentoFilter` (idêntico em Central e Servirea) e `MetricasController`, o mesmo texto Prometheus dos dois com os nomes `ecossistema_*` de hoje. O bloco de domínio de cada sistema vira um bean `MetricasDeDominio`. Mudança pequena na saída: valor inteiro sai sem `.0` (`5` em vez de `5.0`), que o Prometheus aceita igual. consultoria-app ainda não tem monitoramento (só `actuator/health`): adotará o módulo.
- **`plataforma-comum-teste` 1.3.0** (tag `v1.3.0`, PR empilhado sobre o do `monitoramento`): `PostgresDeTeste` (a lógica do `AbstractIntegrationTest` da Central, com a imagem vinda do parent) e `RegrasDeArquitetura` (as regras genéricas do `ArquiteturaTest` da Central como `ArchRule` reutilizáveis, citando o Spring por nome). Ficam nos sistemas: a base de integração (Flyway, `@SpringBootTest`, limpeza de tabelas), a lista de SQL nativo e as regras de domínio. Servirea e consultoria-app têm `ArquiteturaTest` próprio; a adoção troca as regras repetidas pelas do módulo.

- **`plataforma-comum-auth` 1.4.0** (tag `v1.4.0`, PR empilhado sobre o do `teste`): `Autenticador`, `JwtService` (access de 15 min com `cv`, propósitos `access` e `suporte`), `RefreshTokens` (opaco, rotação, reuso revoga tudo com a revogação confirmada antes do 401), `CookieDeRefresh`, `PoliticaCsrf`, `Mfa` (TOTP cifrado + recuperação) e o limite de login no banco, tudo no schema `plataforma` com Flyway próprio. Extraído de Central e Servirea (o consultoria-app não tem MFA nem sessão de suporte; o limite dele tem regras extras que ficam no app). Ver README, seção "Módulo auth".

- **`plataforma-comum-central` 1.5.0** (tag `v1.5.0`, PR empilhado sobre o do `auth`): filtro HMAC de entrada com nonce (`IntegracaoFiltro`), `CentralClient` assinado, `Idempotencia` e `DireitosLocais`, no schema `plataforma_integracao` com Flyway próprio. Extraído comparando Servirea e consultoria-app com o contrato da Central; onde divergem, vale o contrato. Ver README, seção "Módulo central".

- **1.6.0** (tag `v1.6.0`, PR empilhado sobre o do `central`): correções da revisão dos módulos 1.1.0–1.5.0, todas com teste que falhava antes.
  - `web`: multipart com `Content-Length` acima de 1 MiB era recusado (quebraria a importação do Servirea); `getReader()` sem limite; estouro lido fora do MVC virava 500.
  - `auth`: contador de login e revogação por reuso eram desfeitos pela transação do sistema (agora `REQUIRES_NEW`); refresh guarda a versão das credenciais; `iss`/`exp` obrigatórios; `PoliticaCsrf.tratador()` (SPA); `emissor` e `sal` obrigatórios.
  - `central`: `sincronizar` dentro de `@Transactional` perdia todas as confirmações com um item ruim; replay responde 200 + `Idempotency-Replayed`; `provisionar` confere a chave = contratação; prazo na leitura do corpo da resposta; formato estrito da assinatura.
  - `teste`: repositório em português e do Spring Data, `@Controller`, mais formas de JDBC (inclusive cru), lista de exceções por nome completo, caso de falha do `JACKSON_TRES`.

- **1.7.0** (tag `v1.7.0`, PR sobre a `main`; só acréscimos para a Fase 5): `LimiteLoginException.primeiroBloqueio()`; propósitos do aplicativo (`plataforma.auth.jwt.propositos`, `JwtService.emitir`, `Autenticador.autenticarComo`); `Autenticador.verificarCredenciais`, `novaSessao(..., extras)` e `renovar(refresh, ip, extrasDoSujeito)` (a função roda na transação da rotação; exceção desfaz); `emitirSuporte(..., extras)`; `UsuariosDaAutenticacao.versaoDeSuporte`. Nada que existia mudou de comportamento (o construtor do record `AuthProperties.Jwt` ganhou o componente `propositos`).
- **1.8.0** (PR sobre a `main`, tag só depois da revisão; só acréscimo): `Mfa.renovarRecuperacao(sujeito, codigo)`, que o botão "Gerar novos códigos" do Servirea usa.

- **`@plataforma/web` 0.1.0** (pasta `web/`, tag `web-v0.1.0` só depois da revisão; PR sobre a `main`): TypeScript puro para os fronts, fora do reator Maven. `destino-api`, `formatos` (as regras do `Formatos.java`, sem RG e sexo), `erros` (`ErroApi`, `mensagemApi`), `autenticacao` (refresh compartilhado, XSRF só em escrita, desistir só com 401/403) e `tokens` (os 3 pilares do design). Release do GitHub com o `.tgz`, sem token. O `consultoria-app` adota na sequência; os fronts Angular adotam depois.

Versão publicada não muda: correção vira versão nova. Os sistemas adotam os módulos a partir da 1.6.0 (a Fase 5 usa a 1.7.0 e, no Servirea, a 1.8.0; as 1.1.0–1.5.0 ficam publicadas, sem uso).

## Uso pelos sistemas
Central e Servirea adotam o parent e a `plataforma-comum-seguranca` na Fase 4. Em cada um ficam o `ContadorBanco` (implementa `ContadorJanelas`), o bean Spring do limitador (limites vindos da configuração do sistema) e o `@ExceptionHandler` que mapeia `LimiteLoginException` para HTTP 429 com o código `LOGIN_LIMITADO`. O `Retry-After` é posto pelos controllers de login e de MFA.

## Próximos módulos (um por vez, cada um num pull request empilhado)
~~`web`~~ (1.1.0) → ~~`monitoramento`~~ (1.2.0) → ~~`teste`~~ (1.3.0) → ~~`auth`~~ (1.4.0) → ~~`central`~~ (1.5.0). Extraídos da Central (a referência mais testada); o `central` compara Servirea e consultoria-app contra `docs/contrato-integracao-v1.md` da Central. Central e Servirea só adotam depois da revisão do Gustavo.

## Validação
`mvn verify` na raiz, com o JDK 25 (9 testes no módulo de segurança). A publicação usa só o `GITHUB_TOKEN` do Actions, pela tag.
