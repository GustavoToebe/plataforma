# servirea-comum

Biblioteca Java 21 independente de Spring e banco. Código, documentação e commits em português. Ler README.md e [retomada](docs/RETOMADA.md). Fontes e testes ficam em java/src.

- Só extrair comportamento comum, comparado nos dois sistemas; domínio e adaptadores Spring/JDBC ficam nos backends.
- Validar com `cd java; mvn -o verify`. Testes devem estar verdes antes de commit/tag.
- Publicação por tag java-vX.Y.Z igual à versão do java/pom.xml. Versão publicada é imutável. Consumidores fixam versão explicitamente.
- Mudança vai em branch curta saída da `main`, com pull request para o Gustavo fazer o merge. Sem push direto na `main`, sem `--force`, sem tokens pessoais nem configuração Maven do usuário. Publicação usa GITHUB_TOKEN do Actions, só pela tag.
- Central e Servirea usam a 0.2.0 (HMAC, TOTP e núcleo de limites de login). O `ContadorBanco`, o bean Spring do limitador e o mapeamento HTTP 429 de `LimiteLoginException` ficam em cada backend.
- Sem compatibilidade com o que é antigo: nenhum app tem cliente ainda, então pode apagar, renomear e alterar sem código de transição nem leitura de formato velho. Vale até o primeiro cliente real. Continuam valendo tag antes de apagar, testes verdes e nunca dado real ou segredo no git.
