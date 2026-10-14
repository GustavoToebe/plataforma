package br.com.plataforma.comum.auth;

import java.util.Optional;

/**
 * O que o sistema entrega ao módulo de autenticação: achar o usuário pelo login e dizer, a qualquer momento, se ele ainda
 * existe, está ativo e em qual versão de credenciais está. O módulo não conhece a tabela de usuários, papéis nem permissões
 * (isso fica no sistema, na montagem do contexto de segurança depois de {@link Autenticador#autenticar}).
 */
public interface UsuariosDaAutenticacao {

    /** @param sujeito identificador estável do usuário no sistema (até 120 caracteres) */
    record Credencial(String sujeito, String senhaHash, boolean ativo, long versao) {
        @Override
        public String toString() {
            return "Credencial[sujeito=" + sujeito + ", ativo=" + ativo + "]";
        }
    }

    /** Login já normalizado pelo sistema (e-mail em minúsculas, por exemplo). */
    Optional<Credencial> buscarPorLogin(String login);

    /**
     * Versão atual das credenciais do usuário; vazio se ele não existe ou está inativo/bloqueado. É consultada a cada
     * requisição autenticada e a cada rotação do refresh: o módulo recusa o token cujo {@code cv} difere.
     */
    Optional<Long> versaoDeCredenciais(String sujeito);

    /**
     * Versão conferida no token de sessão de suporte, cujo sujeito pode não ser um usuário (no Servirea é o código de uso único
     * que a Central entregou). Vazio recusa o token. Por padrão é igual a {@link #versaoDeCredenciais}; o sistema que emite
     * suporte para um sujeito que não é usuário a implementa sobre o que tiver (o código ainda dentro da janela da sessão, por
     * exemplo: o código é de uso único, então "ainda não usado" não serve, e sim "trocado há menos que a validade do token").
     */
    default Optional<Long> versaoDeSuporte(String sujeito) {
        return versaoDeCredenciais(sujeito);
    }
}
