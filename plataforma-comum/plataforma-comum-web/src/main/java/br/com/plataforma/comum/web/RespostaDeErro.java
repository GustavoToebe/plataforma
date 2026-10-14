package br.com.plataforma.comum.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Instant;

/** Escreve um {@link ApiError} direto na resposta, para filtros que rodam antes dos controllers. */
public final class RespostaDeErro {

    private RespostaDeErro() { }

    public static void escrever(HttpServletResponse response, HttpServletRequest request, HttpStatus status,
                                String message, String codigo, JsonMapper jsonMapper) throws IOException {
        ApiError body = new ApiError(Instant.now(), status.value(), status.name(), message, codigo,
                request.getRequestURI(), MDC.get(RequestIdFilter.MDC_KEY), null);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        jsonMapper.writeValue(response.getWriter(), body);
    }
}
