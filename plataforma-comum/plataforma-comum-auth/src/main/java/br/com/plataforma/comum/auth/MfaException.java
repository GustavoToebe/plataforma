package br.com.plataforma.comum.auth;

import org.springframework.http.HttpStatus;

import br.com.plataforma.comum.web.ApiException;

/** Erro de MFA com código estável ({@code MFA_NECESSARIO}, {@code MFA_CODIGO_INVALIDO}...). Nunca carrega segredo nem código. */
public class MfaException extends ApiException {

    private static final long serialVersionUID = 1L;

    public MfaException(HttpStatus status, String mensagem, String codigo) {
        super(status, mensagem, codigo);
    }

    static MfaException necessario() {
        return new MfaException(HttpStatus.UNAUTHORIZED,
                "Informe o código do aplicativo autenticador ou um código de recuperação.", "MFA_NECESSARIO");
    }

    static MfaException invalido() {
        return new MfaException(HttpStatus.UNAUTHORIZED, "Código de verificação inválido.", "MFA_CODIGO_INVALIDO");
    }

    static MfaException indisponivel() {
        return new MfaException(HttpStatus.SERVICE_UNAVAILABLE,
                "Não foi possível validar o segundo fator. Contate o responsável.", "MFA_INDISPONIVEL");
    }
}
