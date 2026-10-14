package br.com.plataforma.comum.central;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

import br.com.plataforma.comum.seguranca.HmacAssinatura;
import br.com.plataforma.comum.web.RespostaDeErro;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Valida a assinatura HMAC das rotas de integração que a Central chama (contrato v1, seção 2.4), nesta ordem: chave conhecida,
 * janela de 300 s, assinatura (tempo constante), nonce novo. Depois autentica a requisição com a autoridade
 * {@link #AUTORIDADE} e guarda o id da chave em {@link #ATRIBUTO_CHAVE}.
 *
 * <p>O filtro <b>não</b> se registra sozinho no contêiner: a autenticação precisa acontecer dentro da cadeia do Spring Security,
 * numa cadeia própria das rotas de integração (sem JWT, sem CSRF, sem sessão), com
 * {@code http.addFilterBefore(filtro, AuthorizationFilter.class)} e a rota exigindo a autoridade. Cabeçalho ausente ou mal
 * formado cai no código do passo correspondente. O corpo é lido até {@code limite-corpo-bytes} (413 {@code CORPO_EXCEDIDO}) e
 * reentregue ao controller.
 */
public class IntegracaoFiltro extends OncePerRequestFilter {

    public static final String AUTORIDADE = "PERM_INTEGRACAO";
    public static final String ATRIBUTO_CHAVE = "plataforma.central.chave";

    static final String H_CHAVE = "X-Integracao-Chave";
    static final String H_TIMESTAMP = "X-Integracao-Timestamp";
    static final String H_NONCE = "X-Integracao-Nonce";
    static final String H_ASSINATURA = "X-Integracao-Assinatura";
    static final long JANELA_SEGUNDOS = 300L;
    private static final int NONCE_MAX = 128;
    private static final int CABECALHO_MAX = 512;

    private static final Logger log = LoggerFactory.getLogger(IntegracaoFiltro.class);

    private final CentralProperties propriedades;
    private final ChavesDeIntegracao chaves;
    private final NonceStore nonces;
    private final Clock relogio;
    private final JsonMapper json;
    private final SecurityContextHolderStrategy estrategia = SecurityContextHolder.getContextHolderStrategy();

    IntegracaoFiltro(CentralProperties propriedades, ChavesDeIntegracao chaves, NonceStore nonces, Clock relogio, JsonMapper json) {
        this.propriedades = propriedades;
        this.chaves = chaves;
        this.nonces = nonces;
        this.relogio = relogio;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String caminho = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
        return !caminho.startsWith(propriedades.caminhoBase());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain cadeia)
            throws ServletException, IOException {
        String chaveId = limpar(request.getHeader(H_CHAVE));
        byte[] segredo = chaves.segredo(chaveId);
        if (segredo == null) {
            recusar(request, response, HttpStatus.UNAUTHORIZED, "CHAVE_DESCONHECIDA", chaveId);
            return;
        }
        Long timestamp = lerTimestamp(request.getHeader(H_TIMESTAMP));
        if (timestamp == null || Math.abs(relogio.instant().getEpochSecond() - timestamp) > JANELA_SEGUNDOS) {
            recusar(request, response, HttpStatus.UNAUTHORIZED, "TIMESTAMP_FORA_DA_JANELA", chaveId);
            return;
        }
        String nonce = limpar(request.getHeader(H_NONCE));
        String recebida = limpar(request.getHeader(H_ASSINATURA));
        if (nonce == null || nonce.length() > NONCE_MAX || recebida == null || !recebida.startsWith("v1=")) {
            recusar(request, response, HttpStatus.UNAUTHORIZED, "ASSINATURA_INVALIDA", chaveId);
            return;
        }
        if (request.getContentLengthLong() > propriedades.limiteCorpoBytes()) {
            recusar(request, response, HttpStatus.CONTENT_TOO_LARGE, "CORPO_EXCEDIDO", chaveId);
            return;
        }
        byte[] corpo = request.getInputStream().readNBytes(propriedades.limiteCorpoBytes() + 1);
        if (corpo.length > propriedades.limiteCorpoBytes()) {
            recusar(request, response, HttpStatus.CONTENT_TOO_LARGE, "CORPO_EXCEDIDO", chaveId);
            return;
        }
        String consulta = request.getQueryString();
        String caminho = request.getRequestURI() + (consulta == null ? "" : "?" + consulta);
        String esperada = HmacAssinatura.assinar(segredo, request.getMethod().toUpperCase(Locale.ROOT),
                caminho, String.valueOf(timestamp), nonce, corpo);
        if (!HmacAssinatura.confere(esperada, recebida)) {
            recusar(request, response, HttpStatus.UNAUTHORIZED, "ASSINATURA_INVALIDA", chaveId);
            return;
        }
        if (!nonces.registrar(chaveId, nonce)) {
            recusar(request, response, HttpStatus.UNAUTHORIZED, "NONCE_REPETIDO", chaveId);
            return;
        }

        request.setAttribute(ATRIBUTO_CHAVE, chaveId);
        SecurityContext anterior = estrategia.getContext();
        SecurityContext contexto = estrategia.createEmptyContext();
        contexto.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "integracao:" + chaveId, null, List.of(new SimpleGrantedAuthority(AUTORIDADE))));
        estrategia.setContext(contexto);
        try {
            cadeia.doFilter(new CorpoEmCache(request, corpo), response);
        } finally {
            estrategia.setContext(anterior);
        }
    }

    private void recusar(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String codigo, String chaveId)
            throws IOException {
        // O log guarda o motivo e a chave (id público, nunca o segredo); a resposta só leva o código.
        log.warn("Integração recusada: codigo={} chave={} metodo={} caminho={}", codigo, chaveId, request.getMethod(),
                request.getRequestURI());
        String mensagem = status == HttpStatus.CONTENT_TOO_LARGE
                ? "O corpo da requisição excede o limite permitido."
                : "Autenticação de integração inválida.";
        RespostaDeErro.escrever(response, request, status, mensagem, codigo, json);
    }

    private static String limpar(String valor) {
        if (valor == null) {
            return null;
        }
        String cortado = valor.trim();
        return cortado.isEmpty() || cortado.length() > CABECALHO_MAX ? null : cortado;
    }

    private static Long lerTimestamp(String valor) {
        String texto = limpar(valor);
        if (texto == null || !texto.matches("\\d{1,12}")) {
            return null;
        }
        return Long.parseLong(texto);
    }

    /** Reentrega o corpo já lido ao resto da cadeia. */
    private static final class CorpoEmCache extends HttpServletRequestWrapper {

        private final byte[] corpo;

        CorpoEmCache(HttpServletRequest request, byte[] corpo) {
            super(request);
            this.corpo = corpo;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream entrada = new ByteArrayInputStream(corpo);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return entrada.read();
                }

                @Override
                public int read(byte[] destino, int inicio, int tamanho) {
                    return entrada.read(destino, inicio, tamanho);
                }

                @Override
                public boolean isFinished() {
                    return entrada.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new IllegalStateException("Leitura assíncrona não é suportada nas rotas de integração.");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String codificacao = getCharacterEncoding();
            Charset charset = codificacao == null ? StandardCharsets.UTF_8 : Charset.forName(codificacao);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return corpo.length;
        }

        @Override
        public long getContentLengthLong() {
            return corpo.length;
        }
    }
}
