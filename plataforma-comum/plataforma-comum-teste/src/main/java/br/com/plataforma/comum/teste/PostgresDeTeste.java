package br.com.plataforma.comum.teste;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Postgres descartável dos testes de integração, com a imagem escolhida pelo {@code plataforma-parent} (propriedade de
 * sistema {@code plataforma.postgres.imagem}, repassada pelo Surefire e pelo Failsafe). Um container por JVM de teste:
 * guarde o retorno de {@link #iniciar} num campo {@code static} da classe base e registre as propriedades com
 * {@link #registrar}.
 *
 * <pre>{@code
 * static final PostgreSQLContainer POSTGRES = PostgresDeTeste.iniciar("central_test", "central_test", "central_test");
 *
 * @DynamicPropertySource
 * static void banco(DynamicPropertyRegistry registry) { PostgresDeTeste.registrar(registry, POSTGRES); }
 * }</pre>
 */
public final class PostgresDeTeste {

    /** Nome da propriedade de sistema com a imagem. */
    public static final String PROPRIEDADE_IMAGEM = "plataforma.postgres.imagem";

    /** Imagem usada quando a propriedade não chega (teste rodado fora do Maven, na IDE). */
    public static final String IMAGEM_PADRAO = "postgres:18-alpine";

    private PostgresDeTeste() { }

    /** Nome da imagem em uso: a propriedade de sistema ou {@link #IMAGEM_PADRAO}. */
    public static String imagem() {
        String valor = System.getProperty(PROPRIEDADE_IMAGEM);
        return valor == null || valor.isBlank() ? IMAGEM_PADRAO : valor;
    }

    /** Cria e inicia o container; ele é encerrado pelo Ryuk do Testcontainers ao fim da JVM. */
    public static PostgreSQLContainer iniciar(String banco, String usuario, String senha) {
        PostgreSQLContainer container = new PostgreSQLContainer(imagem())
                .withDatabaseName(banco)
                .withUsername(usuario)
                .withPassword(senha);
        container.start();
        return container;
    }

    /** Aponta o {@code spring.datasource} para o container. */
    public static void registrar(DynamicPropertyRegistry registry, PostgreSQLContainer container) {
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
    }
}
