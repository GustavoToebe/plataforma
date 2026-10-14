package br.com.plataforma.comum.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Limita o corpo da requisição (padrão 1 MiB, propriedade {@code plataforma.web.limite-corpo-bytes}). O tamanho
 * declarado é conferido antes da leitura; sem {@code Content-Length} (chunked), a leitura que passa do limite (pelo
 * stream ou pelo reader) falha com {@link CorpoExcedidoIOException}. Dentro do Spring MVC o {@link GlobalExceptionHandler}
 * a traduz em 413; quem lê o corpo antes do MVC (um filtro da cadeia de segurança, como o da integração) recebe o 413
 * deste próprio filtro, desde que a resposta ainda não tenha sido enviada.
 *
 * <p>Upload multipart é conferido primeiro e segue só os limites próprios ({@code spring.servlet.multipart}): o
 * navegador sempre manda {@code Content-Length} no formulário, e um arquivo de 5 MiB não pode cair no limite do JSON.
 */
public class LimiteCorpoFilter extends OncePerRequestFilter {

    public static final long LIMITE_PADRAO_BYTES = 1024L * 1024L;

    private final JsonMapper jsonMapper;
    private final long limiteBytes;

    public LimiteCorpoFilter(JsonMapper jsonMapper, long limiteBytes) {
        this.jsonMapper = jsonMapper;
        this.limiteBytes = limiteBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String tipo = request.getContentType();
        if (tipo != null && tipo.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > limiteBytes) {
            recusar(request, response);
            return;
        }
        try {
            chain.doFilter(new CorpoLimitado(request, limiteBytes), response);
        } catch (IOException | ServletException | RuntimeException e) {
            if (!causadoPorCorpoExcedido(e) || response.isCommitted()) {
                throw e;
            }
            response.resetBuffer();
            recusar(request, response);
        }
    }

    /** A leitura passou do limite em algum ponto da cadeia de causas (filtros costumam embrulhar a exceção). */
    static boolean causadoPorCorpoExcedido(Throwable erro) {
        for (Throwable t = erro; t != null; t = t.getCause()) {
            if (t instanceof CorpoExcedidoIOException) {
                return true;
            }
        }
        return false;
    }

    private void recusar(HttpServletRequest request, HttpServletResponse response) throws IOException {
        CorpoExcedidoException erro = new CorpoExcedidoException();
        RespostaDeErro.escrever(response, request, erro.getStatus(), erro.getMessage(), erro.getCodigo(), jsonMapper);
    }

    /** Sinaliza a leitura que passou do limite; vira 413 no handler. */
    public static final class CorpoExcedidoIOException extends IOException {
        private static final long serialVersionUID = 1L;

        CorpoExcedidoIOException() {
            super("Corpo da requisição acima do limite.");
        }
    }

    private static final class CorpoLimitado extends HttpServletRequestWrapper {

        private final long limiteBytes;
        private ServletInputStream limitado;
        private BufferedReader leitor;

        CorpoLimitado(HttpServletRequest request, long limiteBytes) {
            super(request);
            this.limiteBytes = limiteBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (limitado == null) {
                limitado = new Contador(super.getInputStream(), limiteBytes);
            }
            return limitado;
        }

        /** O reader do contêiner leria o corpo sem passar pelo contador; este lê do stream limitado. */
        @Override
        public BufferedReader getReader() throws IOException {
            if (leitor == null) {
                String charset = getCharacterEncoding();
                leitor = new BufferedReader(new InputStreamReader(getInputStream(),
                        charset != null ? Charset.forName(charset) : StandardCharsets.ISO_8859_1));
            }
            return leitor;
        }
    }

    private static final class Contador extends ServletInputStream {

        private final ServletInputStream origem;
        private final long limiteBytes;
        private long lidos;

        Contador(ServletInputStream origem, long limiteBytes) {
            this.origem = origem;
            this.limiteBytes = limiteBytes;
        }

        @Override
        public int read() throws IOException {
            int b = origem.read();
            if (b >= 0) {
                somar(1);
            }
            return b;
        }

        @Override
        public int read(byte[] destino, int inicio, int tamanho) throws IOException {
            int n = origem.read(destino, inicio, tamanho);
            if (n > 0) {
                somar(n);
            }
            return n;
        }

        private void somar(int n) throws CorpoExcedidoIOException {
            lidos += n;
            if (lidos > limiteBytes) {
                throw new CorpoExcedidoIOException();
            }
        }

        @Override
        public boolean isFinished() {
            return origem.isFinished();
        }

        @Override
        public boolean isReady() {
            return origem.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            origem.setReadListener(listener);
        }
    }
}
