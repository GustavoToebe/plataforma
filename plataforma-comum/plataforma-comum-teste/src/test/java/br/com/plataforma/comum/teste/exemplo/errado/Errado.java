package br.com.plataforma.comum.teste.exemplo.errado;

import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import java.util.logging.Logger;

/** Uma violação por classe; cada regra tem de apontar a sua e deixar as outras em paz. */
public final class Errado {

    private Errado() { }

    public interface PessoaRepository { }

    @RestController
    public static class ControllerComRepositorio {
        private final PessoaRepository repositorio;

        public ControllerComRepositorio(PessoaRepository repositorio) {
            this.repositorio = repositorio;
        }

        public Object repositorio() {
            return repositorio;
        }
    }

    @RestController
    public static class ControllerComEntityManager {
        private final EntityManager em;

        public ControllerComEntityManager(EntityManager em) {
            this.em = em;
        }

        public Object em() {
            return em;
        }
    }

    @RestController
    @Transactional
    public static class ControllerTransacionalNaClasse { }

    @RestController
    public static class ControllerTransacionalNoMetodo {
        @Transactional
        public void gravar() { }
    }

    public static class ServicoQueConheceController {
        private final ControllerTransacionalNaClasse controller = null;

        public Object controller() {
            return controller;
        }
    }

    public static class JdbcSolto {
        private final JdbcTemplate jdbc;

        public JdbcSolto(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        public Object jdbc() {
            return jdbc;
        }
    }

    public static class NativoPeloEntityManager {
        public void rodar(EntityManager em) {
            em.createNativeQuery("select 1");
        }
    }

    public interface RepositorioNativo {
        @Query(value = "select 1", nativeQuery = true)
        int um();
    }

    /** {@code @Query} comum (JPQL) não é SQL nativo. */
    public interface RepositorioJpql {
        @Query("select 1")
        int um();
    }

    public static class InjecaoPorCampo {
        @Autowired
        private Object colaborador;

        public Object colaborador() {
            return colaborador;
        }
    }

    public static class LogDoJdk {
        private static final Logger LOG = Logger.getLogger("x");

        public void falar() {
            LOG.info("x");
        }
    }

    public static class SaidaPadrao {
        public void falar() {
            System.out.println("x");
        }
    }
}
