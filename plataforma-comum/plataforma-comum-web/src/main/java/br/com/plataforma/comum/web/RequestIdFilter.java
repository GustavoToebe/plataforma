package br.com.plataforma.comum.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.GenericFilterBean;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Dá um identificador a cada requisição: aproveita o {@code X-Request-Id} do cliente se for seguro (letras, números,
 * {@code _} e {@code -}, até 64), senão gera um UUID. O valor volta no cabeçalho de resposta e fica no MDC
 * ({@link #MDC_KEY}) durante a requisição, para o log, o {@link ApiError} e o suporte falarem da mesma coisa.
 */
public class RequestIdFilter extends GenericFilterBean {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final Pattern SEGURO = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String requestId = httpRequest.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || !SEGURO.matcher(requestId).matches()) {
            requestId = UUID.randomUUID().toString();
        }

        httpResponse.setHeader(REQUEST_ID_HEADER, requestId);
        MDC.put(MDC_KEY, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
