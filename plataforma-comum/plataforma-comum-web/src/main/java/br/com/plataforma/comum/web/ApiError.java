package br.com.plataforma.comum.web;

import java.time.Instant;
import java.util.List;

/**
 * Corpo de erro único da API: o mesmo nos backends da plataforma, com {@code codigo} estável para a máquina
 * (contrato de integração, seção 3) e {@code requestId} para o suporte achar o log.
 */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String codigo,
        String path,
        String requestId,
        List<FieldError> fieldErrors
) {
    public record FieldError(String field, String message) {
    }
}
