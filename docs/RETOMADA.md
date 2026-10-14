# Retomada

## Versões publicadas
- **`br.com.servirea:servirea-comum` 0.1.0 e 0.2.0** (nome antigo, Java 21, tags `java-v0.1.0` e `java-v0.2.0`): continuam publicadas e imutáveis. Estavam em uso pela Central e pelo Servirea até a adoção da 1.0.0.
- **`br.com.plataforma` 1.0.0** (tag `v1.0.0`): `plataforma-parent` e `plataforma-comum-seguranca` (HMAC, TOTP e `seguranca.login`, o mesmo código da 0.2.0 com os pacotes renomeados). Java 25. Duas correções do Error Prone, sem mudar o comportamento: `toUpperCase(Locale.ROOT)` no HMAC e laço por `charAt` no TOTP.

Versão publicada não muda: correção vira versão nova.

## Uso pelos sistemas
Central e Servirea adotam o parent e a `plataforma-comum-seguranca` na Fase 4. Em cada um ficam o `ContadorBanco` (implementa `ContadorJanelas`), o bean Spring do limitador (limites vindos da configuração do sistema) e o `@ExceptionHandler` que mapeia `LimiteLoginException` para HTTP 429 com o código `LOGIN_LIMITADO`. O `Retry-After` é posto pelos controllers de login e de MFA.

## Próximos módulos (um por vez, cada um num pull request empilhado)
`web` → `monitoramento` → `teste` → `auth` → `central`. Extraídos da Central (a referência mais testada); o `central` compara Servirea e consultoria-app contra `docs/contrato-integracao-v1.md` da Central. Central e Servirea só adotam depois da revisão do Gustavo.

## Validação
`mvn verify` na raiz, com o JDK 25 (9 testes no módulo de segurança). A publicação usa só o `GITHUB_TOKEN` do Actions, pela tag.
