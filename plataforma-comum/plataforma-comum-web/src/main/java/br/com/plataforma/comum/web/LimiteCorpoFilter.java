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

import java.io.IOException;
import java.util.Locale;

/**
 * Limita o corpo da requisição (padrão 1 MiB, propriedade {@code plataforma.web.limite-corpo-bytes}). O tamanho
 * declarado é conferido antes da leitura; sem {@code Content-Length} (chunked), a leitura que passa do limite falha com
 * {@link CorpoExcedidoIOException}, que o {@link GlobalExceptionHandler} traduz em 413. Upload multipart segue os
 * limites próprios do Tomcat ({@code spring.servlet.multipart}).
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
        if (request.getContentLengthLong() > limiteBytes) {
            CorpoExcedidoException erro = new CorpoExcedidoException();
            RespostaDeErro.escrever(response, request, erro.getStatus(), erro.getMessage(), erro.getCodigo(), jsonMapper);
            return;
        }
        String tipo = request.getContentType();
        if (tipo != null && tipo.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
            chain.doFilter(request, response);
            return;
        }
        chain.doFilter(new CorpoLimitado(request, limiteBytes), response);
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
