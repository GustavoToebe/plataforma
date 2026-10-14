-- Migration do "sistema" de teste: prova que o Flyway do sistema segue rodando ao lado do Flyway do módulo.
create table instancia_app (id varchar(120) primary key, nome varchar(200) not null);
