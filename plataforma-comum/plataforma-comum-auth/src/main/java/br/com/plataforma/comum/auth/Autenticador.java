package br.com.plataforma.comum.auth;

import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;

import br.com.plataforma.comum.seguranca.login.LimiteLogin;
import br.com.plataforma.comum.web.UnauthorizedException;

/**
 * Fluxo de sessão do aplicativo: login (limite de tentativas, senha, segundo fator), renovação com rotação do refresh,
 * logout e a validação do token de cada requisição. O sistema só traduz o resultado para HTTP (corpo da resposta, cookie,
 * {@code Retry-After} do {@code LimiteLoginException}) e monta o contexto de segurança com papéis e permissões.
 */
public class Autenticador {

    private static final String LOGIN_INVALIDO = "E-mail ou senha inválidos.";
    private static final String TOKEN_RECUSADO = "Token recusado.";

    /** @param refreshToken valor bruto, para o cookie; nunca vai para o corpo se o cliente for web */
    public record Sessao(String sujeito, String accessToken, long expiraEmSegundos, String refreshToken) {
        @Override
        public String toString() {
            return "Sessao[sujeito=" + sujeito + "]";
        }
    }

    private final UsuariosDaAutenticacao usuarios;
    private final PasswordEncoder senhas;
    private final LimiteLogin limite;
    private final Mfa mfa;
    private final JwtService jwt;
    private final RefreshTokens refresh;
    private volatile String hashFalso;

    public Autenticador(UsuariosDaAutenticacao usuarios, PasswordEncoder senhas, LimiteLogin limite, Mfa mfa, JwtService jwt,
                        RefreshTokens refresh) {
        this.usuarios = usuarios;
        this.senhas = senhas;
        this.limite = limite;
        this.mfa = mfa;
        this.jwt = jwt;
        this.refresh = refresh;
    }

    /**
     * Login. A mensagem de erro é a mesma para usuário inexistente, senha errada e usuário inativo, e a senha é comparada mesmo
     * quando o usuário não existe (o tempo de resposta não revela quais logins existem).
     *
     * @throws br.com.plataforma.comum.seguranca.login.LimiteLoginException se o IP ou a conta passou do limite
     * @throws MfaException                                                  com {@code MFA_NECESSARIO} ou {@code MFA_CODIGO_INVALIDO}
     */
    public Sessao entrar(String login, String senha, String codigoMfa, String ip) {
        limite.registrar(ip, login);
        Optional<UsuariosDaAutenticacao.Credencial> encontrado = usuarios.buscarPorLogin(login);
        String hash = encontrado.map(UsuariosDaAutenticacao.Credencial::senhaHash).orElseGet(this::hashFalso);
        boolean senhaConfere = senha != null && senhas.matches(senha, hash);
        if (encontrado.isEmpty() || !senhaConfere || !encontrado.get().ativo()) {
            throw new UnauthorizedException(LOGIN_INVALIDO);
        }
        UsuariosDaAutenticacao.Credencial credencial = encontrado.get();
        mfa.verificarLogin(credencial.sujeito(), codigoMfa);
        return novaSessao(credencial.sujeito(), credencial.versao(), ip);
    }

    /** Emite access e refresh novos para o sujeito (login, ou depois de trocar a senha e encerrar as demais sessões). */
    public Sessao novaSessao(String sujeito, long versao, String ip) {
        return new Sessao(sujeito, jwt.emitirAcesso(sujeito, versao), jwt.expiraEmSegundos(), refresh.emitir(sujeito, ip));
    }

    /** Renovação: troca o refresh (rotação) e emite um access com a versão atual das credenciais. */
    public Sessao renovar(String refreshBruto, String ip) {
        RefreshTokens.Rotacao rotacao = refresh.rotacionar(refreshBruto, ip);
        return new Sessao(rotacao.sujeito(), jwt.emitirAcesso(rotacao.sujeito(), rotacao.versao()), jwt.expiraEmSegundos(),
                rotacao.refreshToken());
    }

    public void sair(String refreshBruto) {
        refresh.revogar(refreshBruto);
    }

    /** Troca de senha ou de MFA: encerra todas as sessões. O sistema deve subir a versão das credenciais antes. */
    public void encerrarSessoes(String sujeito) {
        refresh.encerrarTodas(sujeito);
    }

    /**
     * Valida o token de acesso de uma requisição: assinatura, propósito, validade e, principalmente, que o {@code cv} do token
     * é a versão atual das credenciais do usuário (e que ele ainda existe e está ativo).
     */
    public JwtService.Token autenticar(String token) {
        return conferir(jwt.validar(token, JwtService.ACESSO));
    }

    /** Igual a {@link #autenticar}, para o token de sessão de suporte (traz operador e motivo, para a auditoria). */
    public JwtService.Token autenticarSuporte(String token) {
        return conferir(jwt.validar(token, JwtService.SUPORTE));
    }

    private JwtService.Token conferir(JwtService.Token token) {
        Optional<Long> atual = usuarios.versaoDeCredenciais(token.sujeito());
        if (atual.isEmpty() || atual.get() != token.versao()) {
            throw new UnauthorizedException(TOKEN_RECUSADO);
        }
        return token;
    }

    private String hashFalso() {
        String hash = hashFalso;
        if (hash == null) {
            hash = senhas.encode("plataforma-sem-usuario");
            hashFalso = hash;
        }
        return hash;
    }
}
