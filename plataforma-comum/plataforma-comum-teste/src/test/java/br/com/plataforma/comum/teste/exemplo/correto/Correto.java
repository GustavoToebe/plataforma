package br.com.plataforma.comum.teste.exemplo.correto;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Código no formato que as regras querem: controller fala com o service, a transação é do service. */
public final class Correto {

    private Correto() { }

    @RestController
    public static class PessoaController {
        private final PessoaService service;

        public PessoaController(PessoaService service) {
            this.service = service;
        }

        @GetMapping("/pessoas")
        public String listar() {
            return service.listar();
        }
    }

    @Service
    public static class PessoaService {
        @Transactional
        public String listar() {
            return "ok";
        }
    }
}
