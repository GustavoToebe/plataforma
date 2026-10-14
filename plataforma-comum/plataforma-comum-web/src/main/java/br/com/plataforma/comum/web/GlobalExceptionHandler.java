package br.com.plataforma.comum.web;

import br.com.plataforma.comum.seguranca.login.LimiteLoginException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;
import java.util.List;

/**
 * Todo erro da API sai como {@link ApiError}. Erro inesperado (500) devolve mensagem genérica com o request id e vai
 * para o log <b>sem dados</b>: só as classes da cadeia de causas e os pontos do código do sistema, nunca a mensagem
 * nem a pilha completa (erro de banco traz valores da linha, que podem ser dados pessoais, LGPD).
 * Sistemas podem estender esta classe e registrar a subclasse como bean; ela cede à autoconfiguração.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final int MAXIMO_CAUSAS = 6;
    private static final int MAXIMO_PONTOS = 5;

    private final List<OuvinteDeErros> ouvintes;
    private final String prefixoCodigoProprio;

    public GlobalExceptionHandler(List<OuvinteDeErros> ouvintes, String prefixoCodigoProprio) {
        this.ouvintes = List.copyOf(ouvintes);
        this.prefixoCodigoProprio = prefixoCodigoProprio;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex, HttpServletRequest request) {
        log.warn("Erro de negócio tratado: status={} codigo={}", ex.getStatus().value(), ex.getCodigo());
        avisar(ex.getStatus().value(), ex.getCodigo(), ex.getMessage(), request);
        return resposta(ex.getStatus(), ex.getMessage(), ex.getCodigo(), request, null);
    }

    @ExceptionHandler(LimiteLoginException.class)
    public ResponseEntity<ApiError> handleLimiteLogin(LimiteLoginException ex, HttpServletRequest request) {
        log.warn("Login limitado: codigo={}", ex.getCodigo());
        avisar(HttpStatus.TOO_MANY_REQUESTS.value(), ex.getCodigo(), ex.getMessage(), request);
        return resposta(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), ex.getCodigo(), request, null);
    }

    /** Outra aba ou pessoa salvou antes: o app mostra "Recarregar". */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleLockOtimista(OptimisticLockingFailureException ex, HttpServletRequest request) {
        return resposta(HttpStatus.CONFLICT,
                "Outra pessoa ou aba alterou este registro. Recarregue para ver a versão atual.", "CONFLITO_EDICAO",
                request, null);
    }

    /** Relança para o filtro de segurança; sem isto o catch-all transformaria o 403 em 500. */
    @ExceptionHandler(AccessDeniedException.class)
    public void handleAccessDenied(AccessDeniedException ex) {
        throw ex;
    }

    @ExceptionHandler(AuthenticationException.class)
    public void handleAuthentication(AuthenticationException ex) {
        throw ex;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        List<ApiError.FieldError> fieldErrors = ex.getConstraintViolations().stream()
                .map(v -> new ApiError.FieldError(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return resposta(HttpStatus.BAD_REQUEST, "Requisição inválida.", "DADOS_INVALIDOS", request, fieldErrors);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Erro não tratado ao processar requisição: {}", semDados(ex));
        String mensagem = "Ocorreu um erro inesperado. Tente novamente ou contate o suporte informando o requestId.";
        avisar(HttpStatus.INTERNAL_SERVER_ERROR.value(), null, mensagem, request);
        return resposta(HttpStatus.INTERNAL_SERVER_ERROR, mensagem, null, request, null);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ApiError.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return objeto(HttpStatus.BAD_REQUEST, "Um ou mais campos são inválidos.", "DADOS_INVALIDOS", request, fieldErrors);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (causadoPorCorpoExcedido(ex)) {
            return corpoExcedido(request);
        }
        return objeto(HttpStatus.BAD_REQUEST, "Corpo da requisição malformado ou ilegível.", "DADOS_INVALIDOS", request, null);
    }

    /** Upload acima de {@code spring.servlet.multipart}. */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return corpoExcedido(request);
    }

    /** Demais erros do Spring MVC (rota inexistente, 405, 415...) também saem como {@link ApiError}. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String codigo = status == HttpStatus.NOT_FOUND ? "NAO_ENCONTRADO" : null;
        return ResponseEntity.status(status).headers(headers)
                .body(corpo(status, status.getReasonPhrase(), codigo, http(request), null));
    }

    /**
     * Resumo do erro para o log sem a mensagem: erro de banco traz os valores da linha ("Key (email)=(...)",
     * "Failing row contains (...)"). Vão só as classes da cadeia de causas e os pontos do código do sistema por onde
     * o erro passou, o que basta para achar o problema junto com o requestId.
     */
    String semDados(Throwable erro) {
        StringBuilder texto = new StringBuilder();
        Throwable atual = erro;
        for (int i = 0; atual != null && i < MAXIMO_CAUSAS; i++, atual = atual.getCause()) {
            if (i > 0) {
                texto.append(" <- ");
            }
            texto.append(atual.getClass().getName());
        }
        int pontos = 0;
        for (StackTraceElement e : erro.getStackTrace()) {
            if (e.getClassName().startsWith(prefixoCodigoProprio) && pontos++ < MAXIMO_PONTOS) {
                texto.append(" | em ").append(e);
            }
        }
        return texto.toString();
    }

    private ResponseEntity<Object> corpoExcedido(WebRequest request) {
        CorpoExcedidoException erro = new CorpoExcedidoException();
        return objeto(erro.getStatus(), erro.getMessage(), erro.getCodigo(), request, null);
    }

    private void avisar(int status, String codigo, String mensagem, HttpServletRequest request) {
        for (OuvinteDeErros ouvinte : ouvintes) {
            try {
                ouvinte.registrar(status, codigo, mensagem, request);
            } catch (RuntimeException falha) {
                log.warn("Ouvinte de erros falhou: {}", falha.getClass().getName());
            }
        }
    }

    private static boolean causadoPorCorpoExcedido(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof LimiteCorpoFilter.CorpoExcedidoIOException) {
                return true;
            }
        }
        return false;
    }

    private static HttpServletRequest http(WebRequest request) {
        return request instanceof ServletWebRequest servlet ? servlet.getRequest() : null;
    }

    private static ResponseEntity<ApiError> resposta(HttpStatus status, String message, String codigo,
                                                     HttpServletRequest request, List<ApiError.FieldError> fieldErrors) {
        return ResponseEntity.status(status).body(corpo(status, message, codigo, request, fieldErrors));
    }

    private static ResponseEntity<Object> objeto(HttpStatus status, String message, String codigo, WebRequest request,
                                                 List<ApiError.FieldError> fieldErrors) {
        return ResponseEntity.status(status).body(corpo(status, message, codigo, http(request), fieldErrors));
    }

    private static ApiError corpo(HttpStatus status, String message, String codigo, HttpServletRequest request,
                                  List<ApiError.FieldError> fieldErrors) {
        return new ApiError(Instant.now(), status.value(), status.name(), message, codigo,
                request != null ? request.getRequestURI() : null, MDC.get(RequestIdFilter.MDC_KEY), fieldErrors);
    }
}
