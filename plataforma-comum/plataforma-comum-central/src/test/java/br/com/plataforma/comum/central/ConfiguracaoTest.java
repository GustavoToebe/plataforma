package br.com.plataforma.comum.central;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

import br.com.plataforma.comum.seguranca.HmacAssinatura;

/** Validação da configuração e do cliente de saída, sem banco. */
class ConfiguracaoTest {

    private static final String SEGREDO = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String CURTO = Base64.getEncoder().encodeToString("curto".getBytes(StandardCharsets.UTF_8));

    private static CentralProperties propriedades(String url, int limiteResposta, Duration timeoutResposta) {
        return new CentralProperties(true, null, "app-teste", SEGREDO, url, "/integracao/", 72, Duration.ofSeconds(2), timeoutResposta,
                limiteResposta, 1 << 20, new CentralProperties.Migracao(null, null, null, null));
    }

    // ---- chaves de entrada ----

    @Test
    void chavesValidasEmAteDuasUnidades() {
        ChavesDeIntegracao uma = new ChavesDeIntegracao("a:" + SEGREDO);
        assertThat(uma.segredo("a")).hasSize(32);
        assertThat(uma.segredo(" a ")).isNotNull();
        assertThat(uma.segredo("b")).isNull();
        assertThat(uma.segredo(null)).isNull();
        assertThat(new ChavesDeIntegracao("a:" + SEGREDO + ",b:" + SEGREDO).segredo("b")).isNotNull();
        assertThat(new ChavesDeIntegracao(null).vazia()).isTrue();
        assertThat(new ChavesDeIntegracao("  ").vazia()).isTrue();
    }

    @Test
    void configuracaoDeChaveInvalidaDerrubaASubida() {
        for (String ruim : new String[] {
                "a:" + SEGREDO + ",b:" + SEGREDO + ",c:" + SEGREDO,
                "a:" + CURTO,
                "a:isto-nao-e-base64!",
                "sem-separador",
                ":" + SEGREDO,
                "a:" + SEGREDO + ",a:" + SEGREDO,
                "a:" + SEGREDO + ",",
                "id com espaco:" + SEGREDO,
                "a:" }) {
            assertThatThrownBy(() -> new ChavesDeIntegracao(ruim)).as(ruim).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void mensagemDeErroNuncaImprimeOSegredo() {
        assertThatThrownBy(() -> new ChavesDeIntegracao("a:" + CURTO)).satisfies(e -> {
            assertThat(e.getMessage()).doesNotContain(CURTO).doesNotContain("curto");
        });
    }

    // ---- propriedades ----

    @Test
    void propriedadesRecusamValoresForaDoPermitido() {
        assertThatThrownBy(() -> new CentralProperties(true, null, null, null, null, "/integracao/", 0, Duration.ofSeconds(1),
                Duration.ofSeconds(1), 4096, 4096, new CentralProperties.Migracao(null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CentralProperties(true, null, null, null, null, "integracao", 72, Duration.ofSeconds(1),
                Duration.ofSeconds(1), 4096, 4096, new CentralProperties.Migracao(null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CentralProperties(true, null, null, null, null, "/integracao/", 72, Duration.ofSeconds(1),
                Duration.ofSeconds(1), 10, 4096, new CentralProperties.Migracao(null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CentralProperties(true, null, null, null, null, "/integracao/", 72, Duration.ofSeconds(1),
                Duration.ofSeconds(1), 4096, 4096, new CentralProperties.Migracao(null, null, null, "papel; drop table x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- cliente de saída ----

    @Test
    void clienteExigeUrlChaveESegredoValidos() {
        Clock relogio = Clock.systemUTC();
        assertThatThrownBy(() -> new CentralClient(propriedades(null, 4096, Duration.ofSeconds(2)), relogio)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CentralClient(propriedades("ftp://central", 4096, Duration.ofSeconds(2)), relogio))
                .isInstanceOf(IllegalStateException.class);
        CentralProperties segredoCurto = new CentralProperties(true, null, "app", CURTO, "http://localhost:1", "/integracao/", 72,
                Duration.ofSeconds(1), Duration.ofSeconds(1), 4096, 4096, new CentralProperties.Migracao(null, null, null, null));
        assertThatThrownBy(() -> new CentralClient(segredoCurto, relogio)).isInstanceOf(IllegalStateException.class);
        CentralProperties chaveRuim = new CentralProperties(true, null, "id ruim", SEGREDO, "http://localhost:1", "/integracao/", 72,
                Duration.ofSeconds(1), Duration.ofSeconds(1), 4096, 4096, new CentralProperties.Migracao(null, null, null, null));
        assertThatThrownBy(() -> new CentralClient(chaveRuim, relogio)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void clienteEnviaOsCincoCabecalhosComAssinaturaConferivelENonceNovoACadaEnvio() throws Exception {
        AtomicReference<Headers> ultimos = new AtomicReference<>();
        AtomicReference<String> metodo = new AtomicReference<>();
        AtomicReference<String> alvo = new AtomicReference<>();
        AtomicReference<byte[]> corpoRecebido = new AtomicReference<>();
        HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", troca -> {
            ultimos.set(troca.getRequestHeaders());
            metodo.set(troca.getRequestMethod());
            alvo.set(troca.getRequestURI().getRawPath() + (troca.getRequestURI().getRawQuery() == null ? "" : "?" + troca.getRequestURI().getRawQuery()));
            corpoRecebido.set(troca.getRequestBody().readAllBytes());
            byte[] resposta = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            troca.sendResponseHeaders(201, resposta.length);
            try (OutputStream saida = troca.getResponseBody()) {
                saida.write(resposta);
            }
        });
        servidor.start();
        try {
            CentralClient cliente = new CentralClient(propriedades("http://127.0.0.1:" + servidor.getAddress().getPort() + "/", 4096,
                    Duration.ofSeconds(5)), Clock.systemUTC());
            byte[] corpo = "{\"x\":1}".getBytes(StandardCharsets.UTF_8);

            CentralClient.Resposta resposta = cliente.enviar("post", "/integracao/v1/instancias?a=1&b=%C3%A9", corpo, "idem-1");
            assertThat(resposta.status()).isEqualTo(201);
            assertThat(resposta.sucesso()).isTrue();
            assertThat(resposta.texto()).isEqualTo("{\"ok\":true}");
            assertThat(metodo).hasValue("POST");
            assertThat(alvo).hasValue("/integracao/v1/instancias?a=1&b=%C3%A9");
            assertThat(corpoRecebido.get()).isEqualTo(corpo);

            Headers h = ultimos.get();
            String nonce1 = h.getFirst(IntegracaoFiltro.H_NONCE);
            assertThat(h.getFirst(IntegracaoFiltro.H_CHAVE)).isEqualTo("app-teste");
            assertThat(h.getFirst("Idempotency-Key")).isEqualTo("idem-1");
            String esperada = "v1=" + HmacAssinatura.assinar(Base64.getDecoder().decode(SEGREDO), "POST", alvo.get(),
                    h.getFirst(IntegracaoFiltro.H_TIMESTAMP), nonce1, corpo);
            assertThat(h.getFirst(IntegracaoFiltro.H_ASSINATURA)).isEqualTo(esperada);

            cliente.get("/integracao/v1/produtos/APP/direitos");
            assertThat(ultimos.get().getFirst(IntegracaoFiltro.H_NONCE)).isNotEqualTo(nonce1);
            assertThat(ultimos.get().getFirst("Idempotency-Key")).isNull();
        } finally {
            servidor.stop(0);
        }
    }

    @Test
    void clienteNaoSegueRedirecionamentoELimitaARespostaEOPrazo() throws Exception {
        HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/redireciona", troca -> {
            troca.getResponseHeaders().add("Location", "http://127.0.0.1:1/outro");
            troca.sendResponseHeaders(302, -1);
            troca.close();
        });
        servidor.createContext("/grande", troca -> {
            byte[] resposta = new byte[5000];
            troca.sendResponseHeaders(200, resposta.length);
            try (OutputStream saida = troca.getResponseBody()) {
                saida.write(resposta);
            }
        });
        servidor.createContext("/lento", troca -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            troca.sendResponseHeaders(200, -1);
            troca.close();
        });
        servidor.start();
        try {
            String base = "http://127.0.0.1:" + servidor.getAddress().getPort();
            CentralClient cliente = new CentralClient(propriedades(base, 4096, Duration.ofMillis(500)), Clock.systemUTC());
            assertThat(cliente.get("/redireciona").status()).isEqualTo(302);
            assertThatThrownBy(() -> cliente.get("/grande")).isInstanceOf(CentralClient.CentralIndisponivelException.class);
            assertThatThrownBy(() -> cliente.get("/lento")).isInstanceOf(CentralClient.CentralIndisponivelException.class);
        } finally {
            servidor.stop(0);
        }
    }

    @Test
    void centralForaDoArEhIndisponibilidadeNaoErroDeProgramacao() {
        CentralClient cliente = new CentralClient(propriedades("http://127.0.0.1:1", 4096, Duration.ofSeconds(1)), Clock.systemUTC());
        assertThatThrownBy(() -> cliente.get("/integracao/v1/x")).isInstanceOf(CentralClient.CentralIndisponivelException.class)
                .hasMessageNotContaining(SEGREDO);
    }
}
