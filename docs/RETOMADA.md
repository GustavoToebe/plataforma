# Retomada — 03/10/2026

0.1.0 validada com `mvn verify` (5 testes), publicada pela tag java-v0.1.0 no commit 379ce3a. Workflow 37131221029 concluiu testes e publicação com sucesso. `mvn -o install` instalou a versão no cache local sem token pessoal.

0.2.0 extrai ContadorJanelas, ContadorEmMemoria, LimiteLogin e LimiteLoginException após comparar as fontes normalizadas dos dois backends. Adaptadores Spring, configuração e exceção HTTP permanecem nos sistemas. O núcleo não depende de Spring/JDBC; a exceção de biblioteca é RuntimeException com segundos/código e pede mapeamento HTTP numa futura integração. `mvn -o verify`: 9 testes, zero falhas/erros/ignorados. Inclui contrato de injeção de contador externo com chaves anonimizadas e prazo.

Backends ligados somente a HMAC/TOTP 0.1.0; ContadorBanco e as classes locais de login permanecem. Não trocar a dependência para 0.2.0 automaticamente. Seus commits devem permanecer locais até o usuário configurar leitura de pacotes.

Sem token pessoal, alterações em ~/.m2/settings.xml, merge ou deploy. Apenas a publicação das tags usa o GITHUB_TOKEN próprio dos Actions. Permissões Maven são herdadas do repositório; token pessoal de leitura precisa ser classic, não fine-grained. README e manuais dos consumidores descrevem essa correção ao roteiro original.
