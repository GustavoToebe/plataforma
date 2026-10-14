# plataforma

Parent Maven e biblioteca comum dos backends da plataforma (Central, Servirea, consultoria-app e os próximos). Java 25, Spring Boot 4.1.x. Código, documentação e commits em português. Ler README.md e [retomada](docs/RETOMADA.md).

- Só infraestrutura, nunca regra de negócio. Extrair comportamento comum, comparado nos sistemas; domínio fica nos backends. Módulos com Spring e JDBC são permitidos (roteiro, Fase 4.6), desde que sejam infraestrutura.
- Estrutura: `plataforma-parent` (POM pai dos backends), `plataforma-comum/plataforma-comum-<módulo>` (um por assunto) e o `pom.xml` raiz, que só agrega e publica tudo com a mesma versão. `groupId` `br.com.plataforma`, pacote `br.com.plataforma.comum.<módulo>`.
- Validar com `mvn verify` na raiz, com o **JDK 25** (`JAVA_HOME`). Testes verdes antes de commit/tag.
- Publicação por tag `vX.Y.Z` igual à versão do `pom.xml` raiz, depois do CI do pull request verde. Versão publicada é imutável: se a publicação falhar no meio, sobe o patch seguinte. Consumidores fixam versão explicitamente.
- Mudança vai em branch curta saída da `main`, com pull request para o Gustavo fazer o merge. Sem push direto na `main`, sem `--force`, sem tokens pessoais nem configuração Maven do usuário. Publicação usa GITHUB_TOKEN do Actions, só pela tag. Mergear sem squash, para as tags ficarem no histórico da `main`.
- O `plataforma-parent` fixa Error Prone, JaCoCo, Surefire/Failsafe e a imagem do Postgres dos testes (`plataforma.postgres.imagem`, hoje `postgres:18-alpine`, lida como propriedade de sistema). Projeto que herda o parent copia o `.mvn/jvm.config` da raiz (aberturas do javac para o Error Prone).
- `plataforma-comum-web`: `ApiError`/`ApiException`, handler global (500 sem dados no log), `RequestIdFilter`, `LimiteCorpoFilter`, tudo por autoconfiguração (`PlataformaWebAutoConfiguration`) e propriedades `plataforma.web.*`. Depende da `seguranca` (429 do limite de login). Não leva `Formatos` nem exceção com regra de negócio.
- `plataforma-comum-seguranca`: HMAC da integração, TOTP e o núcleo do limite de login (`seguranca.login`). O `ContadorBanco`, o bean Spring do limitador e o mapeamento HTTP 429 de `LimiteLoginException` ficam em cada backend até o módulo `auth` existir.
- Sem compatibilidade com o que é antigo: nenhum app tem cliente ainda, então pode apagar, renomear e alterar sem código de transição nem leitura de formato velho. Vale até o primeiro cliente real. Continuam valendo tag antes de apagar, testes verdes e nunca dado real ou segredo no git.
