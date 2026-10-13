# Retomada

## Versões publicadas
- **0.1.0:** `seguranca` (`HmacAssinatura` e `Totp`), tag `java-v0.1.0`.
- **0.2.0:** acrescenta `login` (`ContadorJanelas`, `ContadorEmMemoria`, `LimiteLogin`, `LimiteLoginException`), tag `java-v0.2.0`. As fontes dos dois backends foram comparadas e o núcleo é igual depois de normalizar produto e pacote.

Versão publicada não muda: correção vira versão nova.

## Uso pelos sistemas
Central e Servirea usam a 0.2.0. Em cada um ficam o `ContadorBanco` (implementa `ContadorJanelas`), o bean Spring do limitador (limites vindos da configuração do sistema) e o `@ExceptionHandler` que mapeia `LimiteLoginException` para HTTP 429 com o código `LOGIN_LIMITADO`. O `Retry-After` é posto pelos controllers de login e de MFA.

## Validação
`cd java; mvn verify` (9 testes). A publicação usa só o `GITHUB_TOKEN` do Actions, pela tag.
