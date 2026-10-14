package br.com.plataforma.comum.monitoramento;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Credencial exclusiva de coleta: {@code Authorization: Monitoring <token>} em {@code GET /monitoramento/metrics}.
 * Não autentica nenhuma outra rota nem concede acesso ao negócio. Sem token configurado (ou fora do formato
 * {@code [A-Za-z0-9_-]{32,128}}), a coleta fica fechada. O sistema registra o filtro na cadeia de segurança, antes do
 * filtro de JWT, com o valor da propriedade {@code monitoramento.token}.
 */
public final class MonitoramentoFilter extends OncePerRequestFilter {

    public static final String CAMINHO = "/monitoramento/metrics";
    public static final String PAPEL = "MONITORAMENTO";

    private static final String PREFIXO = "Monitoring ";
    private static final int TAMANHO_MAXIMO = 128;
    private static final Pattern FORMATO_DO_TOKEN = Pattern.compile("[A-Za-z0-9_-]{32,128}");

    private final byte[] esperado;

    public MonitoramentoFilter(String token) {
        esperado = token != null && FORMATO_DO_TOKEN.matcher(token).matches()
                ? token.getBytes(StandardCharsets.UTF_8) : null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!CAMINHO.equals(request.getRequestURI().substring(request.getContextPath().length()))) {
            chain.doFilter(request, response);
            return;
        }
        String cabecalho = request.getHeader("Authorization");
        String recebido = cabecalho != null && cabecalho.startsWith(PREFIXO) ? cabecalho.substring(PREFIXO.length()) : "";
        if (!"GET".equals(request.getMethod()) || esperado == null || recebido.length() > TAMANHO_MAXIMO
                || !MessageDigest.isEqual(esperado, recebido.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("Cache-Control", "no-store");
            return;
        }
        SecurityContext anterior = SecurityContextHolder.getContext();
        SecurityContext contexto = SecurityContextHolder.createEmptyContext();
        contexto.setAuthentication(new UsernamePasswordAuthenticationToken("coletor", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + PAPEL))));
        SecurityContextHolder.setContext(contexto);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.setContext(anterior);
        }
    }
}
