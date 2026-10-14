-- Autenticação comum (módulo plataforma-comum-auth). Schema próprio e histórico próprio do Flyway: não mistura com as
-- migrations do sistema. "sujeito" é o identificador do usuário no sistema (texto; o módulo não conhece a tabela de usuários).

-- Refresh opaco: só o SHA-256 do token é guardado. Reuso de um token já revogado derruba todas as sessões do sujeito.
-- "versao" é a versão das credenciais no login: a rotação recusa o token quando a do usuário mudou.
create table refresh_token (
    id             uuid primary key default gen_random_uuid(),
    sujeito        varchar(120) not null,
    token_hash     char(64)     not null unique,
    criado_em      timestamptz  not null default now(),
    expira_em      timestamptz  not null,
    revogado_em    timestamptz,
    substituido_por uuid,
    ip             varchar(64),
    versao         bigint       not null
);
create index ix_refresh_token_sujeito on refresh_token (sujeito);
create index ix_refresh_token_expira on refresh_token (expira_em);

-- Limite de tentativas de login: chave já chega como hash (nenhum e-mail nem IP em claro).
create table login_tentativa (
    chave       varchar(100) primary key,
    janela_ate  timestamptz  not null,
    tentativas  integer      not null
);

-- MFA (TOTP). Os segredos ficam cifrados (AES-GCM, chaves fora do banco); ultimo_passo impede reuso do mesmo código.
create table mfa (
    sujeito          varchar(120) primary key,
    segredo_cifrado  text,
    pendente_cifrado text,
    pendente_ate     timestamptz,
    ultimo_passo     bigint       not null default -1,
    ativado_em       timestamptz
);

create table mfa_recuperacao (
    sujeito      varchar(120) not null,
    codigo_hash  char(64)     not null,
    usado_em     timestamptz,
    primary key (sujeito, codigo_hash)
);
