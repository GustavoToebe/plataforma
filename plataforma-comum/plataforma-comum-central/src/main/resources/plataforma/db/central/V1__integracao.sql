-- Lado do app do contrato de integração v1 (módulo plataforma-comum-central). Schema e histórico próprios.
-- "instancia_id" é o id da instância deste app na Central (tenant_id no Servirea, consultoria_id no consultoria-app), em texto.

-- Nonce do HMAC de entrada: a restrição única impede o replay; apagado depois de 10 minutos (o dobro da janela).
create table integracao_nonce (
    chave_id    varchar(80)  not null,
    nonce       varchar(128) not null,
    recebido_em timestamptz  not null default now(),
    primary key (chave_id, nonce)
);
create index ix_integracao_nonce_recebido on integracao_nonce (recebido_em);

-- Idempotência do provisionamento: a mesma Idempotency-Key com o mesmo corpo repete a resposta; com outro corpo é conflito.
create table integracao_operacao (
    idempotency_key varchar(120) primary key,
    tipo            varchar(40)  not null,
    hash_corpo      char(64)     not null,
    status_http     integer      not null,
    resposta        text         not null,
    criado_em       timestamptz  not null default now()
);

-- Direitos da instância (snapshot completo da Central, aplicado só se a versão for maior).
create table direitos_locais (
    instancia_id    varchar(120) primary key,
    contratacao_id  varchar(120) not null,
    versao          bigint       not null,
    situacao        varchar(20)  not null,
    acesso_liberado boolean      not null,
    motivo_bloqueio text,
    vigente_ate     date,
    plano_codigo    varchar(80),
    plano_nome      varchar(160),
    limites         text         not null default '{}',
    funcionalidades text         not null default '[]',
    confirmado_em   timestamptz  not null,
    atualizado_em   timestamptz  not null
);
