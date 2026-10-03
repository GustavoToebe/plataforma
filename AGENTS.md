# servirea-comum

Biblioteca Java 21 independente de Spring e banco. Código, documentação e commits em português. Ler README.md e [retomada](docs/RETOMADA.md). Fontes e testes ficam em java/src.

- Só extrair comportamento comum, comparado nos dois sistemas; domínio e adaptadores Spring/JDBC ficam nos backends.
- Validar com `cd java; mvn -o verify`. Testes devem estar verdes antes de commit/tag.
- Publicação por tag java-vX.Y.Z igual à versão do java/pom.xml. Versão publicada é imutável. Consumidores fixam versão explicitamente.
- Nesta tarefa: main autorizada para commit/push e tags; sem merge, deploy, tokens pessoais ou configuração Maven do usuário. Publicação usa GITHUB_TOKEN do Actions.
- Backends mantêm 0.1.0. Núcleo login de 0.2.0 está disponível para revisão e não integrado aos consumidores.
