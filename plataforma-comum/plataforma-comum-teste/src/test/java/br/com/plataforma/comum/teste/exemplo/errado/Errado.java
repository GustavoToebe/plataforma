package br.com.plataforma.comum.teste.exemplo.errado;

import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.sql.SQLException;
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

    /** Repositório com nome em português (JDBC à mão), como o {@code ContaLocalRepositorio} do consultoria-app. */
    public static class ContaRepositorio { }

    /** Repositório do Spring Data com nome que não termina em "Repository". */
    public interface Pessoas extends org.springframework.data.repository.Repository<Object, Long> { }

    @RestController
    public static class ControllerComRepositorioEmPortugues {
        private final ContaRepositorio contas;

        public ControllerComRepositorioEmPortugues(ContaRepositorio contas) {
            this.contas = contas;
        }

        public Object contas() {
            return contas;
        }
    }

    @RestController
    public static class ControllerComSpringData {
        private final Pessoas pessoas;

        public ControllerComSpringData(Pessoas pessoas) {
            this.pessoas = pessoas;
        }

        public Object pessoas() {
            return pessoas;
        }
    }

    /** {@code @Controller} (MVC com {@code @ResponseBody}) vale como controller também. */
    @Controller
    public static class ControllerMvcComRepositorio {
        private final PessoaRepository repositorio;

        public ControllerMvcComRepositorio(PessoaRepository repositorio) {
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

    public static class JdbcPelaInterface {
        private final JdbcOperations jdbc;

        public JdbcPelaInterface(JdbcOperations jdbc) {
            this.jdbc = jdbc;
        }

        public Object jdbc() {
            return jdbc;
        }
    }

    public static class JdbcComNomes {
        private final NamedParameterJdbcOperations jdbc;

        public JdbcComNomes(NamedParameterJdbcOperations jdbc) {
            this.jdbc = jdbc;
        }

        public Object jdbc() {
            return jdbc;
        }
    }

    public static class InsercaoSimples {
        public Object preparar(JdbcTemplate jdbc) {
            return new SimpleJdbcInsert(jdbc);
        }
    }

    /** JDBC cru: {@code DataSource.getConnection()} e {@code createStatement()}. */
    public static class ConexaoCrua {
        public boolean rodar(Connection conexao) throws SQLException {
            try (var comando = conexao.createStatement()) {
                return comando.execute("select 1");
            }
        }
    }

    public static class UsaJacksonDois {
        public String ler() {
            return com.fasterxml.jackson.databind.MapeadorDeMentira.ler();
        }
    }

    public static class NativoPeloEntityManager {
        public void rodar(EntityManager em) {
            em.createNativeQuery("select 1");
        }
    }

    public static class NativoComDoisArgumentos {
        public void rodar(EntityManager em) {
            em.createNativeQuery("select 1", "mapeamento");
        }
    }

    public interface RepositorioComNativeQuery {
        @NativeQuery("select 1")
        int um();
    }

    @RestController
    public static class ControllerComTransacaoDoJakarta {
        @jakarta.transaction.Transactional
        public void gravar() { }
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
