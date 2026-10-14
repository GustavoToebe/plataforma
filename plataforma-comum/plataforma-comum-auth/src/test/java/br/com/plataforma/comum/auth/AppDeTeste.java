package br.com.plataforma.comum.auth;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** O "sistema" do teste: usuários em memória e um PasswordEncoder que conta as comparações. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
class AppDeTeste {

    record Usuario(String sujeito, String hash, boolean ativo, long versao) {
    }

    static final Map<String, Usuario> USUARIOS = new ConcurrentHashMap<>();
    static final AtomicInteger COMPARACOES = new AtomicInteger();
    static final RelogioMutavel RELOGIO = new RelogioMutavel();

    @Bean
    RelogioMutavel relogio() {
        return RELOGIO;
    }

    @Bean
    PasswordEncoder senhas() {
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(4);
        return new PasswordEncoder() {
            @Override
            public String encode(CharSequence senha) {
                return bcrypt.encode(senha);
            }

            @Override
            public boolean matches(CharSequence senha, String hash) {
                COMPARACOES.incrementAndGet();
                return bcrypt.matches(senha, hash);
            }
        };
    }

    @Bean
    UsuariosDaAutenticacao usuarios() {
        return new UsuariosDaAutenticacao() {
            @Override
            public Optional<Credencial> buscarPorLogin(String login) {
                return Optional.ofNullable(USUARIOS.get(login))
                        .map(u -> new Credencial(u.sujeito(), u.hash(), u.ativo(), u.versao()));
            }

            @Override
            public Optional<Long> versaoDeCredenciais(String sujeito) {
                return USUARIOS.values().stream().filter(u -> u.sujeito().equals(sujeito) && u.ativo())
                        .map(Usuario::versao).findFirst();
            }
        };
    }
}
