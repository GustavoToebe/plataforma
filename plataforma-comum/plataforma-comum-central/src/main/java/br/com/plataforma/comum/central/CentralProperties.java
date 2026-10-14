package br.com.plataforma.comum.central;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuração do lado do app do contrato v1 ({@code plataforma.central.*}). Só liga com {@code habilitado=true}. Os segredos
 * (Base64, mínimo 32 bytes) vêm do ambiente do sistema, nunca do git.
 *
 * @param habilitado          liga o módulo (migrations, filtro de entrada, cliente de saída)
 * @param chavesEntrada       {@code id:segredoBase64[,id:segredoBase64]}: as chaves com que a Central assina (no máximo duas
 *                            ativas, para trocar sem parada)
 * @param chaveSaidaId        identificador da chave com que este app assina para a Central
 * @param chaveSaidaSegredo   segredo (Base64) dessa chave
 * @param centralUrl          URL base da Central (sem barra no fim); vazio = sem cliente de saída
 * @param caminhoBase      prefixo (decodificado) das rotas de integração que o filtro protege
 * @param toleranciaHoras     por quanto tempo sem confirmação da Central o app ainda libera o acesso (contrato, seção 4)
 * @param redeInterna         aceita {@code http} também quando o host da Central é um nome de serviço da rede interna (um só
 *                            rótulo, sem ponto, como {@code central-api} no compose); o padrão é só {@code https} (e http em localhost)
 */
@ConfigurationProperties(prefix = "plataforma.central")
public record CentralProperties(
        @DefaultValue("false") boolean habilitado,
        String chavesEntrada,
        String chaveSaidaId,
        String chaveSaidaSegredo,
        String centralUrl,
        @DefaultValue("/integracao/") String caminhoBase,
        @DefaultValue("72") int toleranciaHoras,
        @DefaultValue("PT5S") Duration timeoutConexao,
        @DefaultValue("PT10S") Duration timeoutResposta,
        @DefaultValue("8388608") int limiteRespostaBytes,
        @DefaultValue("1048576") int limiteCorpoBytes,
        @DefaultValue Migracao migracao,
        @DefaultValue("false") boolean redeInterna
) {

    /**
     * @param papelApp papel do banco que a API usa em execução, quando difere de quem migra: recebe uso do schema
     *                 {@code plataforma_integracao} e SELECT/INSERT/UPDATE/DELETE nas tabelas (nunca o histórico do Flyway)
     */
    public record Migracao(String url, String user, String password, String papelApp) {
    }

    /** Forma de antes da {@code redeInterna}: só {@code https} (e http em localhost). */
    public CentralProperties(boolean habilitado, String chavesEntrada, String chaveSaidaId, String chaveSaidaSegredo, String centralUrl,
            String caminhoBase, int toleranciaHoras, Duration timeoutConexao, Duration timeoutResposta, int limiteRespostaBytes,
            int limiteCorpoBytes, Migracao migracao) {
        this(habilitado, chavesEntrada, chaveSaidaId, chaveSaidaSegredo, centralUrl, caminhoBase, toleranciaHoras, timeoutConexao,
                timeoutResposta, limiteRespostaBytes, limiteCorpoBytes, migracao, false);
    }

    @ConstructorBinding
    public CentralProperties {
        if (toleranciaHoras < 1 || toleranciaHoras > 24 * 30) {
            throw new IllegalArgumentException("plataforma.central.tolerancia-horas precisa ficar entre 1 e 720.");
        }
        if (caminhoBase == null || !caminhoBase.startsWith("/") || !caminhoBase.endsWith("/")) {
            throw new IllegalArgumentException("plataforma.central.caminho-base precisa começar e terminar com barra.");
        }
        if (limiteCorpoBytes < 1024 || limiteRespostaBytes < 1024) {
            throw new IllegalArgumentException("Limites de corpo e de resposta da integração são pequenos demais.");
        }
        if (migracao.papelApp() != null && !migracao.papelApp().isBlank()
                && !migracao.papelApp().matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("plataforma.central.migracao.papel-app não é um nome de papel válido.");
        }
    }
}
