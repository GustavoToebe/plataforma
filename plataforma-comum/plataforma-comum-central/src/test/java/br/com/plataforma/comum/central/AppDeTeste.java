package br.com.plataforma.comum.central;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/** O "sistema" do teste: a cadeia de segurança das rotas de integração e dois endpoints de eco. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
class AppDeTeste {

    static final RelogioMutavel RELOGIO = new RelogioMutavel();

    @Bean
    RelogioMutavel relogio() {
        return RELOGIO;
    }

    @Bean
    SecurityFilterChain cadeiaDeIntegracao(HttpSecurity http, IntegracaoFiltro filtro) throws Exception {
        return http.securityMatcher("/integracao/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sessao -> sessao.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(filtro, AuthorizationFilter.class)
                .authorizeHttpRequests(a -> a.anyRequest().hasAuthority(IntegracaoFiltro.AUTORIDADE))
                .build();
    }

    @RestController
    @RequestMapping("/integracao/v1")
    static class Eco {

        @PostMapping("/eco")
        ResponseEntity<String> postar(@RequestBody String corpo, HttpServletRequest request) {
            return ResponseEntity.ok(request.getAttribute(IntegracaoFiltro.ATRIBUTO_CHAVE) + "|"
                    + SecurityContextHolder.getContext().getAuthentication().getName() + "|" + corpo);
        }

        @GetMapping("/eco")
        ResponseEntity<String> consultar(HttpServletRequest request) {
            return ResponseEntity.ok("consulta:" + request.getQueryString());
        }

        @PostMapping("/falha")
        ResponseEntity<String> falhar() {
            throw new IllegalStateException("falha de teste");
        }
    }
}
