package br.com.plataforma.comum.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** E-mail sem banco: conteúdo fixo, validações e o envio pelo Resend contra um servidor HTTP falso (nunca o Resend de verdade). */
@ExtendWith(OutputCaptureExtension.class)
class EmailTest {

    private static final String CHAVE = "re_chave_so_de_teste_123";
    private static final String LINK = "https://app.exemplo.test/redefinir-senha#token=abc_DEF-123";

    private HttpServer servidor;
    private final AtomicReference<String> corpo = new AtomicReference<>();
    private final AtomicReference<String> autorizacao = new AtomicReference<>();
    private final AtomicReference<String> tipo = new AtomicReference<>();
    private final AtomicReference<String> metodo = new AtomicReference<>();
    private final AtomicInteger chamadas = new AtomicInteger();
    private final AtomicInteger redirecionadas = new AtomicInteger();
    private volatile int status = 200;
    private volatile String respostaDoProvedor = "{\"id\":\"1\"}";
    private volatile long demoraMs;
    private volatile String localizacao;

    @BeforeEach
    void subir() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/emails", troca -> {
            chamadas.incrementAndGet();
            metodo.set(troca.getRequestMethod());
            autorizacao.set(troca.getRequestHeaders().getFirst("Authorization"));
            tipo.set(troca.getRequestHeaders().getFirst("Content-Type"));
            corpo.set(new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (demoraMs > 0) {
                try {
                    Thread.sleep(demoraMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (localizacao != null) {
                troca.getResponseHeaders().add("Location", localizacao);
            }
            byte[] bytes = respostaDoProvedor.getBytes(StandardCharsets.UTF_8);
            troca.sendResponseHeaders(status, bytes.length);
            try (OutputStream saida = troca.getResponseBody()) {
                saida.write(bytes);
            }
        });
        servidor.createContext("/outro", troca -> {
            redirecionadas.incrementAndGet();
            troca.sendResponseHeaders(200, -1);
            troca.close();
        });
        servidor.start();
    }

    @AfterEach
    void parar() {
        servidor.stop(0);
    }

    private String urlDoServidor(String caminho) {
        return "http://127.0.0.1:" + servidor.getAddress().getPort() + caminho;
    }

    private EmailProperties resend(Duration timeoutResposta) {
        return new EmailProperties(true, "resend", "App de Teste <contato@exemplo.test>", "App de Teste",
                new EmailProperties.Resend(CHAVE, urlDoServidor("/emails"), Duration.ofSeconds(2), timeoutResposta));
    }

    private static EmailProperties log() {
        return new EmailProperties(true, "log", "contato@exemplo.test", "App de Teste", new EmailProperties.Resend(null, "https://api.resend.com/emails",
                Duration.ofSeconds(5), Duration.ofSeconds(10)));
    }

    private static EnviadorDeEmail.Mensagem mensagem() {
        return new EnviadorDeEmail.Mensagem("ana@x.com", "Assunto", "Texto " + LINK, "<p>" + LINK + "</p>");
    }

    // ---- Resend ----

    @Test
    void resendRecebeUmPostComChaveEOsCincoCampos() {
        new EnviadorResend(resend(Duration.ofSeconds(5))).enviar(mensagem());

        assertThat(metodo.get()).isEqualTo("POST");
        assertThat(autorizacao.get()).isEqualTo("Bearer " + CHAVE);
        assertThat(tipo.get()).startsWith("application/json");
        JsonNode json = JsonMapper.builder().build().readTree(corpo.get());
        assertThat(json.get("from").asString()).isEqualTo("App de Teste <contato@exemplo.test>");
        assertThat(json.get("to").get(0).asString()).isEqualTo("ana@x.com");
        assertThat(json.get("to")).hasSize(1);
        assertThat(json.get("subject").asString()).isEqualTo("Assunto");
        assertThat(json.get("text").asString()).contains(LINK);
        assertThat(json.get("html").asString()).contains(LINK);
    }

    @Test
    void resendQueRecusaViraErroSemEnderecoChaveNemCorpoDaResposta(CapturedOutput saida) {
        status = 422;
        respostaDoProvedor = "{\"message\":\"endereco invalido: ana@x.com\"}";

        assertThatThrownBy(() -> new EnviadorResend(resend(Duration.ofSeconds(5))).enviar(mensagem()))
                .isInstanceOf(EnvioDeEmailException.class)
                .hasMessageContaining("422")
                .hasMessageNotContaining("ana@x.com").hasMessageNotContaining(CHAVE).hasMessageNotContaining("endereco invalido")
                .hasNoCause();
        assertThat(saida.getAll()).doesNotContain(CHAVE).doesNotContain("ana@x.com");
    }

    @Test
    void resendNaoSegueRedirecionamentoNemMandaAChaveParaOutroLugar() {
        status = 302;
        localizacao = urlDoServidor("/outro");

        assertThatThrownBy(() -> new EnviadorResend(resend(Duration.ofSeconds(5))).enviar(mensagem()))
                .isInstanceOf(EnvioDeEmailException.class).hasMessageContaining("302");
        assertThat(redirecionadas).hasValue(0);
        assertThat(chamadas).hasValue(1);
    }

    @Test
    void resendLentoEstouraNoPrazoDaResposta() {
        demoraMs = 1500;

        assertThatThrownBy(() -> new EnviadorResend(resend(Duration.ofMillis(300))).enviar(mensagem()))
                .isInstanceOf(EnvioDeEmailException.class).hasMessageNotContaining("ana@x.com");
    }

    @Test
    void resendForaDoArEhErroDeEnvio() {
        servidor.stop(0);

        assertThatThrownBy(() -> new EnviadorResend(resend(Duration.ofSeconds(2))).enviar(mensagem()))
                .isInstanceOf(EnvioDeEmailException.class).hasMessageNotContaining(CHAVE).hasMessageNotContaining("ana@x.com");
    }

    @Test
    void mensagemEPropriedadesNaoSeMostramEmLog() {
        assertThat(mensagem().toString()).doesNotContain("ana@x.com").doesNotContain("token");
        assertThat(resend(Duration.ofSeconds(1)).resend().toString()).doesNotContain(CHAVE);
    }

    // ---- Provedor log ----

    @Test
    void provedorLogSoRegistraQueHouveEnvio(CapturedOutput saida) {
        new EnviadorRegistro().enviar(mensagem());

        assertThat(saida.getAll()).contains("não enviado").doesNotContain("ana@x.com").doesNotContain("abc_DEF").doesNotContain("token")
                .doesNotContain("Assunto");
    }

    // ---- Conteúdo ----

    private static class Capturador implements EnviadorDeEmail {
        final List<Mensagem> mensagens = new CopyOnWriteArrayList<>();

        @Override
        public void enviar(Mensagem mensagem) {
            mensagens.add(mensagem);
        }
    }

    @Test
    void redefinicaoLevaSoOLinkEUmTextoFixo() {
        Capturador capturador = new Capturador();
        new EmailsDeAcesso(capturador, log()).enviarRedefinicao("ana@x.com", LINK, Duration.ofMinutes(30));

        EnviadorDeEmail.Mensagem m = capturador.mensagens.get(0);
        assertThat(m.para()).isEqualTo("ana@x.com");
        assertThat(m.assunto()).isEqualTo("Redefinição de senha - App de Teste");
        assertThat(m.texto()).isEqualTo("""
                Recebemos um pedido para redefinir a sua senha no App de Teste.

                Para criar uma nova senha, abra o link abaixo. Ele vale por 30 minutos e só pode ser usado uma vez.

                %s

                Se você não fez este pedido, ignore esta mensagem: a sua senha continua a mesma.""".formatted(LINK));
        assertThat(m.html()).contains("<a href=\"" + LINK + "\">");
    }

    @Test
    void conviteLevaSoOLinkQueOAppGerou() {
        Capturador capturador = new Capturador();
        new EmailsDeAcesso(capturador, log()).enviarConvite("bia@x.com", "https://app.exemplo.test/convite?c=xyz&d=1");

        EnviadorDeEmail.Mensagem m = capturador.mensagens.get(0);
        assertThat(m.assunto()).isEqualTo("Convite - App de Teste");
        assertThat(m.texto()).contains("https://app.exemplo.test/convite?c=xyz&d=1").doesNotContain("&amp;");
        assertThat(m.html()).contains("href=\"https://app.exemplo.test/convite?c=xyz&amp;d=1\"");
    }

    @Test
    void nomeDoAppEhEscapadoNoHtml() {
        Capturador capturador = new Capturador();
        EmailProperties props = new EmailProperties(true, "log", "contato@exemplo.test", "A&B <script>", new EmailProperties.Resend(null,
                "https://api.resend.com/emails", Duration.ofSeconds(5), Duration.ofSeconds(10)));
        new EmailsDeAcesso(capturador, props).enviarConvite("bia@x.com", LINK);

        assertThat(capturador.mensagens.get(0).html()).contains("A&amp;B &lt;script&gt;").doesNotContain("<script>");
    }

    @Test
    void duracaoNoTextoVaiDeMinutosAHoras() {
        Capturador capturador = new Capturador();
        EmailsDeAcesso emails = new EmailsDeAcesso(capturador, log());
        emails.enviarRedefinicao("ana@x.com", LINK, Duration.ofMinutes(1));
        emails.enviarRedefinicao("ana@x.com", LINK, Duration.ofHours(3));
        assertThat(capturador.mensagens.get(0).texto()).contains("vale por 1 minuto ");
        assertThat(capturador.mensagens.get(1).texto()).contains("vale por 3 horas ");
    }

    @Test
    void linkEEnderecoInvalidosNaoSaem() {
        Capturador capturador = new Capturador();
        EmailsDeAcesso emails = new EmailsDeAcesso(capturador, log());

        for (String link : new String[] {"javascript:alert(1)", "http://evil.example/x", "https://a.test/\"onclick=1", "https://a.test/ x", "ftp://a.test/x",
                "", null, "https://a.test/\n"}) {
            assertThatThrownBy(() -> emails.enviarConvite("bia@x.com", link)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String endereco : new String[] {"a@b.com, c@d.com", "a@b.com\nBcc: x@y.com", "sem-arroba", "a b@c.com", "<a@b.com>", "", null}) {
            assertThatThrownBy(() -> emails.enviarConvite(endereco, LINK)).isInstanceOf(IllegalArgumentException.class);
        }
        emails.enviarConvite("bia@x.com", "http://localhost:4200/convite?c=1");
        assertThat(capturador.mensagens).hasSize(1);
    }

    // ---- Propriedades ----

    @Test
    void emailLigadoExigeRemetenteENomeDoApp() {
        EmailProperties.Resend semChave = new EmailProperties.Resend(null, "https://api.resend.com/emails", Duration.ofSeconds(5), Duration.ofSeconds(10));
        assertThatThrownBy(() -> new EmailProperties(true, "log", null, "App", semChave)).hasMessageContaining("remetente");
        assertThatThrownBy(() -> new EmailProperties(true, "log", "sem-arroba", "App", semChave)).hasMessageContaining("remetente");
        assertThatThrownBy(() -> new EmailProperties(true, "log", "a@b.com\nBcc: x@y.com", "App", semChave)).hasMessageContaining("remetente");
        assertThatThrownBy(() -> new EmailProperties(true, "log", "a@b.com", " ", semChave)).hasMessageContaining("nome-app");
        assertThatThrownBy(() -> new EmailProperties(false, "smtp", null, null, semChave)).hasMessageContaining("provedor");
        // Desligado, nada é exigido.
        assertThat(new EmailProperties(false, "log", null, null, semChave).habilitado()).isFalse();
    }

    @Test
    void provedorResendExigeChaveEUrlSegura() {
        assertThatThrownBy(() -> new EmailProperties(true, "resend", "a@b.com", "App",
                new EmailProperties.Resend(null, "https://api.resend.com/emails", Duration.ofSeconds(5), Duration.ofSeconds(10))))
                .hasMessageContaining("api-key");
        assertThatThrownBy(() -> new EmailProperties(true, "resend", "a@b.com", "App",
                new EmailProperties.Resend(" ", "https://api.resend.com/emails", Duration.ofSeconds(5), Duration.ofSeconds(10))))
                .hasMessageContaining("api-key");
        assertThatThrownBy(() -> new EmailProperties(true, "resend", "a@b.com", "App",
                new EmailProperties.Resend(CHAVE, "http://api.resend.com/emails", Duration.ofSeconds(5), Duration.ofSeconds(10))))
                .hasMessageContaining("https");
        assertThatThrownBy(() -> new EmailProperties(true, "resend", "a@b.com", "App",
                new EmailProperties.Resend(CHAVE, "https://api.resend.com/emails", Duration.ZERO, Duration.ofSeconds(10))))
                .hasMessageContaining("prazos");
    }

    @Test
    void redefinicaoLigadaExigeUrlBaseSemFragmentoEPrazoRazoavel() {
        assertThatThrownBy(() -> new RedefinicaoProperties(true, null, Duration.ofMinutes(30), 30, 5, 10, 3600)).hasMessageContaining("url-base");
        assertThatThrownBy(() -> new RedefinicaoProperties(true, "http://app.exemplo.test/x", Duration.ofMinutes(30), 30, 5, 10, 3600))
                .hasMessageContaining("url-base");
        assertThatThrownBy(() -> new RedefinicaoProperties(true, "https://app.exemplo.test/x#y", Duration.ofMinutes(30), 30, 5, 10, 3600))
                .hasMessageContaining("url-base");
        assertThatThrownBy(() -> new RedefinicaoProperties(true, "https://app.exemplo.test/x", Duration.ofMinutes(1), 30, 5, 10, 3600))
                .hasMessageContaining("prazo");
        assertThatThrownBy(() -> new RedefinicaoProperties(true, "https://app.exemplo.test/x", Duration.ofHours(25), 30, 5, 10, 3600))
                .hasMessageContaining("prazo");
        assertThatThrownBy(() -> new RedefinicaoProperties(true, "https://app.exemplo.test/x", Duration.ofMinutes(30), 0, 5, 10, 3600))
                .hasMessageContaining("limites");
        assertThat(new RedefinicaoProperties(true, "http://localhost:4200/redefinir-senha", Duration.ofMinutes(30), 30, 5, 10, 3600).habilitado()).isTrue();
        assertThat(new RedefinicaoProperties(false, null, Duration.ofMinutes(30), 0, 0, 0, 0).habilitado()).isFalse();
    }
}
