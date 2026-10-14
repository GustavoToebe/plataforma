package br.com.plataforma.comum.central;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import br.com.plataforma.comum.seguranca.HmacAssinatura;
import br.com.plataforma.comum.teste.PostgresDeTeste;

/** O filtro de entrada e o cliente de saída contra um servidor de verdade, com o banco do Testcontainers. */
@SpringBootTest(classes = AppDeTeste.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "plataforma.central.habilitado=true",
        "plataforma.central.chaves-entrada=" + IntegracaoIntegrationTest.ENTRADA,
        "plataforma.central.central-url=http://localhost:1",
        "plataforma.central.chave-saida-id=central-teste",
        "plataforma.central.chave-saida-segredo=" + IntegracaoIntegrationTest.SEGREDO_A,
        "plataforma.central.limite-corpo-bytes=2048",
        "spring.main.banner-mode=off"
})
class IntegracaoIntegrationTest {

    static final String SEGREDO_A = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    static final String SEGREDO_B = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=";
    static final String ENTRADA = "central-teste:" + SEGREDO_A + ",central-nova:" + SEGREDO_B;
    static final PostgreSQLContainer POSTGRES = PostgresDeTeste.iniciar("central_test", "central_test", "central_test");

    @DynamicPropertySource
    static void banco(DynamicPropertyRegistry registry) {
        PostgresDeTeste.registrar(registry, POSTGRES);
    }

    @LocalServerPort int portaLocal;
    @Autowired NonceStore nonces;
    @Autowired JdbcTemplate jdbc;
    @Autowired CentralProperties propriedades;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void limpar() {
        AppDeTeste.RELOGIO.reiniciar();
        jdbc.update("delete from plataforma_integracao.integracao_nonce");
    }

    /** A Central do teste é o próprio servidor; o cliente do contexto aponta para uma porta fixa, então este usa a sorteada. */
    private CentralClient clienteLocal() {
        CentralProperties local = new CentralProperties(true, ENTRADA, "central-teste", SEGREDO_A, "http://localhost:" + portaLocal,
                "/integracao/", 72, Duration.ofSeconds(5), Duration.ofSeconds(10), 1 << 20, 2048, propriedades.migracao());
        return new CentralClient(local, AppDeTeste.RELOGIO);
    }

    private record Assinado(String chave, String timestamp, String nonce, String assinatura) {
    }

    private Assinado assinar(String segredoBase64, String chave, String metodo, String caminho, long timestamp, String nonce, byte[] corpo) {
        String assinatura = HmacAssinatura.assinar(Base64.getDecoder().decode(segredoBase64), metodo, caminho, String.valueOf(timestamp),
                nonce, corpo);
        return new Assinado(chave, String.valueOf(timestamp), nonce, "v1=" + assinatura);
    }

    private HttpResponse<String> enviar(String metodo, String caminho, byte[] corpo, Assinado a) throws Exception {
        HttpRequest.Builder pedido = HttpRequest.newBuilder(URI.create("http://localhost:" + portaLocal + caminho))
                .method(metodo, corpo.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(corpo));
        if (corpo.length > 0) {
            pedido.header("Content-Type", "application/json");
        }
        if (a.chave() != null) {
            pedido.header(IntegracaoFiltro.H_CHAVE, a.chave());
        }
        if (a.timestamp() != null) {
            pedido.header(IntegracaoFiltro.H_TIMESTAMP, a.timestamp());
        }
        if (a.nonce() != null) {
            pedido.header(IntegracaoFiltro.H_NONCE, a.nonce());
        }
        if (a.assinatura() != null) {
            pedido.header(IntegracaoFiltro.H_ASSINATURA, a.assinatura());
        }
        return http.send(pedido.build(), HttpResponse.BodyHandlers.ofString());
    }

    private long agora() {
        return AppDeTeste.RELOGIO.instant().getEpochSecond();
    }

    private static String codigo(HttpResponse<String> resposta) {
        String corpo = resposta.body();
        int i = corpo.indexOf("\"codigo\":\"");
        return i < 0 ? null : corpo.substring(i + 10, corpo.indexOf('"', i + 10));
    }

    private HttpResponse<String> postValido(String nonce, String corpoTexto) throws Exception {
        byte[] corpo = corpoTexto.getBytes(StandardCharsets.UTF_8);
        return enviar("POST", "/integracao/v1/eco", corpo, assinar(SEGREDO_A, "central-teste", "POST", "/integracao/v1/eco", agora(), nonce, corpo));
    }

    @Test
    void clienteDeSaidaAssinaEOFiltroAceitaIdaEVolta() {
        CentralClient cliente = clienteLocal();
        CentralClient.Resposta resposta = cliente.enviar("POST", "/integracao/v1/eco", "{\"a\":1}".getBytes(StandardCharsets.UTF_8), "chave-1");
        assertThat(resposta.status()).isEqualTo(200);
        assertThat(resposta.texto()).isEqualTo("central-teste|integracao:central-teste|{\"a\":1}");
    }

    @Test
    void queryStringEntraNaAssinaturaExatamenteComoEnviada() {
        CentralClient.Resposta resposta = clienteLocal().get("/integracao/v1/eco?pagina=0&nome=a%20b");
        assertThat(resposta.status()).isEqualTo(200);
        assertThat(resposta.texto()).isEqualTo("consulta:pagina=0&nome=a%20b");
    }

    @Test
    void asDuasChavesAtivasValemParaTrocaSemParada() throws Exception {
        byte[] corpo = "{}".getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> resposta = enviar("POST", "/integracao/v1/eco", corpo,
                assinar(SEGREDO_B, "central-nova", "POST", "/integracao/v1/eco", agora(), UUID.randomUUID().toString(), corpo));
        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat(resposta.body()).startsWith("central-nova|");
    }

    @Test
    void chaveDesconhecidaOuAusenteEhRecusada() throws Exception {
        byte[] corpo = "{}".getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> desconhecida = enviar("POST", "/integracao/v1/eco", corpo,
                assinar(SEGREDO_A, "outra", "POST", "/integracao/v1/eco", agora(), UUID.randomUUID().toString(), corpo));
        assertThat(desconhecida.statusCode()).isEqualTo(401);
        assertThat(codigo(desconhecida)).isEqualTo("CHAVE_DESCONHECIDA");
        HttpResponse<String> ausente = enviar("POST", "/integracao/v1/eco", corpo, new Assinado(null, null, null, null));
        assertThat(codigo(ausente)).isEqualTo("CHAVE_DESCONHECIDA");
    }

    @Test
    void janelaDe300SegundosParaOsDoisLados() throws Exception {
        byte[] corpo = "{}".getBytes(StandardCharsets.UTF_8);
        for (long desvio : new long[] {300, -300}) {
            HttpResponse<String> dentro = enviar("POST", "/integracao/v1/eco", corpo,
                    assinar(SEGREDO_A, "central-teste", "POST", "/integracao/v1/eco", agora() + desvio, UUID.randomUUID().toString(), corpo));
            assertThat(dentro.statusCode()).as("desvio %s", desvio).isEqualTo(200);
        }
        for (long desvio : new long[] {301, -301}) {
            HttpResponse<String> fora = enviar("POST", "/integracao/v1/eco", corpo,
                    assinar(SEGREDO_A, "central-teste", "POST", "/integracao/v1/eco", agora() + desvio, UUID.randomUUID().toString(), corpo));
            assertThat(fora.statusCode()).as("desvio %s", desvio).isEqualTo(401);
            assertThat(codigo(fora)).isEqualTo("TIMESTAMP_FORA_DA_JANELA");
        }
        HttpResponse<String> semTimestamp = enviar("POST", "/integracao/v1/eco", corpo,
                new Assinado("central-teste", "abc", UUID.randomUUID().toString(), "v1=00"));
        assertThat(codigo(semTimestamp)).isEqualTo("TIMESTAMP_FORA_DA_JANELA");
    }

    @Test
    void corpoAlteradoCaminhoOutroOuMetodoOutroInvalidamAAssinatura() throws Exception {
        byte[] original = "{\"valor\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] alterado = "{\"valor\":2}".getBytes(StandardCharsets.UTF_8);
        Assinado a = assinar(SEGREDO_A, "central-teste", "POST", "/integracao/v1/eco", agora(), UUID.randomUUID().toString(), original);
        assertThat(codigo(enviar("POST", "/integracao/v1/eco", alterado, a))).isEqualTo("ASSINATURA_INVALIDA");

        Assinado b = assinar(SEGREDO_A, "central-teste", "POST", "/integracao/v1/eco", agora(), UUID.randomUUID().toString(), original);
        assertThat(codigo(enviar("POST", "/integracao/v1/falha", original, b))).isEqualTo("ASSINATURA_INVALIDA");

        Assinado c = assinar(SEGREDO_A, "central-teste", "GET", "/integracao/v1/eco", agora(), UUID.randomUUID().toString(), new byte[0]);
        assertThat(codigo(enviar("POST", "/integracao/v1/eco", new byte[0], c))).isEqualTo("ASSINATURA_INVALIDA");

        Assinado sinal = new Assinado("central-teste", String.valueOf(agora()), UUID.randomUUID().toString(), "sem-prefixo");
        assertThat(codigo(enviar("POST", "/integracao/v1/eco", original, sinal))).isEqualTo("ASSINATURA_INVALIDA");
    }

    @Test
    void repeticaoComOMesmoNonceEhRecusadaENonceRuimNaoFicaGravado() throws Exception {
        String nonce = UUID.randomUUID().toString();
        assertThat(postValido(nonce, "{}").statusCode()).isEqualTo(200);
        HttpResponse<String> repetida = postValido(nonce, "{}");
        assertThat(repetida.statusCode()).isEqualTo(401);
        assertThat(codigo(repetida)).isEqualTo("NONCE_REPETIDO");

        // Assinatura inválida não grava o nonce (ninguém lota a tabela sem conhecer o segredo).
        String semSegredo = UUID.randomUUID().toString();
        byte[] corpo = "{}".getBytes(StandardCharsets.UTF_8);
        enviar("POST", "/integracao/v1/eco", corpo, new Assinado("central-teste", String.valueOf(agora()), semSegredo, "v1=" + "0".repeat(64)));
        assertThat(jdbc.queryForObject("select count(*) from plataforma_integracao.integracao_nonce where nonce = ?", Integer.class, semSegredo))
                .isZero();
    }

    @Test
    void nonceFicaGravadoMesmoQuandoOHandlerFalha() throws Exception {
        byte[] corpo = "{}".getBytes(StandardCharsets.UTF_8);
        String nonce = UUID.randomUUID().toString();
        Assinado a = assinar(SEGREDO_A, "central-teste", "POST", "/integracao/v1/falha", agora(), nonce, corpo);
        assertThat(enviar("POST", "/integracao/v1/falha", corpo, a).statusCode()).isEqualTo(500);
        assertThat(jdbc.queryForObject("select count(*) from plataforma_integracao.integracao_nonce where nonce = ?", Integer.class, nonce))
                .isEqualTo(1);
        assertThat(codigo(enviar("POST", "/integracao/v1/falha", corpo, a))).isEqualTo("NONCE_REPETIDO");
    }

    @Test
    void mesmoNonceEmChavesDiferentesNaoColide() throws Exception {
        String nonce = UUID.randomUUID().toString();
        byte[] corpo = "{}".getBytes(StandardCharsets.UTF_8);
        assertThat(postValido(nonce, "{}").statusCode()).isEqualTo(200);
        HttpResponse<String> outra = enviar("POST", "/integracao/v1/eco", corpo,
                assinar(SEGREDO_B, "central-nova", "POST", "/integracao/v1/eco", agora(), nonce, corpo));
        assertThat(outra.statusCode()).isEqualTo(200);
    }

    @Test
    void corpoAcimaDoLimiteRetorna413ComOuSemContentLength() throws Exception {
        byte[] grande = "x".repeat(2049).getBytes(StandardCharsets.UTF_8);
        Assinado a = assinar(SEGREDO_A, "central-teste", "POST", "/integracao/v1/eco", agora(), UUID.randomUUID().toString(), grande);
        HttpResponse<String> comTamanho = enviar("POST", "/integracao/v1/eco", grande, a);
        assertThat(comTamanho.statusCode()).isEqualTo(413);
        assertThat(codigo(comTamanho)).isEqualTo("CORPO_EXCEDIDO");

        Assinado b = assinar(SEGREDO_A, "central-teste", "POST", "/integracao/v1/eco", agora(), UUID.randomUUID().toString(), grande);
        HttpRequest emPedacos = HttpRequest.newBuilder(URI.create("http://localhost:" + portaLocal + "/integracao/v1/eco"))
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(grande)))
                .header(IntegracaoFiltro.H_CHAVE, b.chave()).header(IntegracaoFiltro.H_TIMESTAMP, b.timestamp())
                .header(IntegracaoFiltro.H_NONCE, b.nonce()).header(IntegracaoFiltro.H_ASSINATURA, b.assinatura()).build();
        HttpResponse<String> semTamanho = http.send(emPedacos, HttpResponse.BodyHandlers.ofString());
        assertThat(semTamanho.statusCode()).isEqualTo(413);

        // No limite exato passa.
        byte[] limite = "y".repeat(2048).getBytes(StandardCharsets.UTF_8);
        Assinado c = assinar(SEGREDO_A, "central-teste", "POST", "/integracao/v1/eco", agora(), UUID.randomUUID().toString(), limite);
        assertThat(enviar("POST", "/integracao/v1/eco", limite, c).statusCode()).isEqualTo(200);
    }

    @Test
    void limpezaApagaSoOsNoncesComMaisDeDezMinutos() throws Exception {
        assertThat(postValido(UUID.randomUUID().toString(), "{}").statusCode()).isEqualTo(200);
        AppDeTeste.RELOGIO.avancar(Duration.ofMinutes(9));
        assertThat(nonces.limpar()).isZero();
        AppDeTeste.RELOGIO.avancar(Duration.ofMinutes(2));
        assertThat(nonces.limpar()).isEqualTo(1);
    }

    @Test
    void rotasForaDoPrefixoNaoPassamPeloFiltro() throws Exception {
        HttpResponse<String> resposta = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + portaLocal + "/outra")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resposta.statusCode()).isEqualTo(404);
    }

    @Test
    void clienteRecusaCaminhoInvalido() {
        CentralClient cliente = clienteLocal();
        assertThatThrownBy(() -> cliente.get("//externo.example/x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cliente.get("sem-barra")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cliente.get("/a b")).isInstanceOf(IllegalArgumentException.class);
    }
}
