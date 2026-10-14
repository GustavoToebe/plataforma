package br.com.plataforma.comum.central;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import br.com.plataforma.comum.seguranca.HmacAssinatura;

/**
 * Cliente assinado para as chamadas do app à Central (contrato v1, seção 6): monta os cinco cabeçalhos de integração, com
 * nonce novo a cada envio (inclusive em repetição), e devolve o status e o corpo sem interpretar. Nunca segue redirecionamento
 * (a assinatura vale para um caminho só), tem prazo de conexão e de resposta, e limita o tamanho da resposta lida. Quem chama
 * decide o que repetir, conforme a tabela de erros do contrato.
 */
public class CentralClient {

    /** Agenda o fim do prazo de leitura do corpo das respostas (uma thread de plataforma, daemon, para todos os clientes). */
    private static final ScheduledExecutorService PRAZOS = Executors.newSingleThreadScheduledExecutor(tarefa -> {
        Thread thread = new Thread(tarefa, "plataforma-central-prazos");
        thread.setDaemon(true);
        return thread;
    });

    /** Status HTTP e corpo da resposta da Central, sem interpretação. */
    public static final class Resposta {

        private final int status;
        private final byte[] corpo;

        Resposta(int status, byte[] corpo) {
            this.status = status;
            this.corpo = corpo;
        }

        public int status() {
            return status;
        }

        public byte[] corpo() {
            return corpo.clone();
        }

        public String texto() {
            return new String(corpo, StandardCharsets.UTF_8);
        }

        public boolean sucesso() {
            return status >= 200 && status < 300;
        }
    }

    /** Falha de rede, prazo esgotado ou resposta grande demais: a Central não respondeu. Em geral, repetir com espera. */
    public static class CentralIndisponivelException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public CentralIndisponivelException(String mensagem, Throwable causa) {
            super(mensagem, causa);
        }
    }

    private final HttpClient http;
    private final String base;
    private final String chaveId;
    private final byte[] segredo;
    private final CentralProperties propriedades;
    private final Clock relogio;

    CentralClient(CentralProperties propriedades, Clock relogio) {
        this.propriedades = propriedades;
        this.relogio = relogio;
        String url = propriedades.centralUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("plataforma.central.central-url é obrigatório para o cliente de saída.");
        }
        URI uri = URI.create(url.trim());
        if (uri.getHost() == null || !("https".equals(uri.getScheme())
                || ("http".equals(uri.getScheme()) && (loopback(uri.getHost()) || (propriedades.redeInterna() && nomeDeServico(uri.getHost())))))) {
            // As respostas da Central não são assinadas: em http puro, alguém no caminho adulteraria a sincronização de direitos.
            throw new IllegalStateException("plataforma.central.central-url precisa ser https (http só para localhost"
                    + " ou, com plataforma.central.rede-interna, para um nome de serviço da rede interna).");
        }
        this.base = url.trim().replaceAll("/+$", "");
        this.chaveId = propriedades.chaveSaidaId();
        if (chaveId == null || !chaveId.matches("[A-Za-z0-9._-]{1,80}")) {
            throw new IllegalStateException("plataforma.central.chave-saida-id inválido.");
        }
        try {
            this.segredo = Base64.getDecoder().decode(propriedades.chaveSaidaSegredo() == null ? "" : propriedades.chaveSaidaSegredo().trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("plataforma.central.chave-saida-segredo precisa estar em Base64.");
        }
        if (segredo.length < 32) {
            throw new IllegalStateException("plataforma.central.chave-saida-segredo precisa ter pelo menos 32 bytes.");
        }
        this.http = HttpClient.newBuilder()
                .connectTimeout(propriedades.timeoutConexao())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private static void fecharSemErro(InputStream corpo) {
        try {
            corpo.close();
        } catch (IOException e) {
            // a leitura em andamento já vai falhar; nada a fazer aqui
        }
    }

    /** Um só rótulo, sem ponto e sem ':' nem número IP (ex.: {@code central-api}): só resolve na rede interna, nunca na internet. */
    private static boolean nomeDeServico(String host) {
        return host.matches("[A-Za-z][A-Za-z0-9-]{0,62}");
    }

    private static boolean loopback(String host) {
        return "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
    }

    public Resposta get(String caminho) {
        return enviar("GET", caminho, new byte[0], null);
    }

    /**
     * @param caminho        caminho com a query string já em percent-encoding, exatamente como vai na linha de requisição
     * @param idempotencyKey só no provisionamento; igual em toda repetição
     */
    public Resposta enviar(String metodo, String caminho, byte[] corpo, String idempotencyKey) {
        if (caminho == null || !caminho.startsWith("/") || caminho.startsWith("//") || caminho.chars().anyMatch(c -> c <= 0x20 || c == 0x7f)) {
            throw new IllegalArgumentException("Caminho inválido para a Central.");
        }
        String verbo = metodo.toUpperCase(Locale.ROOT);
        byte[] bytes = corpo == null ? new byte[0] : corpo;
        String timestamp = String.valueOf(relogio.instant().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        String assinatura = HmacAssinatura.assinar(segredo, verbo, caminho, timestamp, nonce, bytes);

        HttpRequest.Builder pedido = HttpRequest.newBuilder(URI.create(base + caminho))
                .timeout(propriedades.timeoutResposta())
                .header(IntegracaoFiltro.H_CHAVE, chaveId)
                .header(IntegracaoFiltro.H_TIMESTAMP, timestamp)
                .header(IntegracaoFiltro.H_NONCE, nonce)
                .header(IntegracaoFiltro.H_ASSINATURA, assinatura)
                .header("Accept", "application/json")
                .method(verbo, bytes.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(bytes));
        if (bytes.length > 0) {
            pedido.header("Content-Type", "application/json");
        }
        if (idempotencyKey != null) {
            pedido.header("Idempotency-Key", idempotencyKey);
        }
        try {
            HttpResponse<InputStream> resposta = http.send(pedido.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream corpoResposta = resposta.body()) {
                // O timeout do pedido só cobre a chegada dos cabeçalhos: o corpo tem o seu próprio prazo, senão uma Central
                // que manda o corpo devagar prende a thread de quem chamou para sempre. Fechar o stream destrava a leitura.
                ScheduledFuture<?> prazo = PRAZOS.schedule(() -> fecharSemErro(corpoResposta),
                        propriedades.timeoutResposta().toMillis(), TimeUnit.MILLISECONDS);
                byte[] lido;
                try {
                    lido = corpoResposta.readNBytes(propriedades.limiteRespostaBytes() + 1);
                } finally {
                    prazo.cancel(false);
                }
                if (lido.length > propriedades.limiteRespostaBytes()) {
                    throw new CentralIndisponivelException("Resposta da Central maior que o limite permitido.", null);
                }
                return new Resposta(resposta.statusCode(), lido);
            }
        } catch (IOException e) {
            throw new CentralIndisponivelException("A Central não respondeu (" + e.getClass().getSimpleName() + ").", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CentralIndisponivelException("Chamada à Central interrompida.", e);
        }
    }
}
