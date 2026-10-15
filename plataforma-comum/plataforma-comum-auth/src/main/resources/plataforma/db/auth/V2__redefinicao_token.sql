-- Links de redefinição de senha e de convite (módulo plataforma-comum-auth, 1.9.0). Só o SHA-256 do token é guardado; o link
-- (com o token no fragmento) nunca fica no banco. Uso único: ao usar um token, apagam-se todos os do mesmo sujeito.
create table redefinicao_token (
    id          uuid primary key default gen_random_uuid(),
    sujeito     varchar(120) not null,
    finalidade  varchar(12)  not null check (finalidade in ('REDEFINICAO', 'CONVITE')),
    token_hash  char(64)     not null unique,
    criado_em   timestamptz  not null default now(),
    expira_em   timestamptz  not null
);
create index ix_redefinicao_token_sujeito on redefinicao_token (sujeito, finalidade);
create index ix_redefinicao_token_expira on redefinicao_token (expira_em);
