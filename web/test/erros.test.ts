import { describe, expect, it } from 'vitest';

import {
  ErroApi,
  MENSAGEM_ERRO_DO_SERVIDOR,
  MENSAGEM_NAO_CONCLUIDO,
  MENSAGEM_SEM_CONEXAO,
  codigoApi,
  corpoDoErro,
  erroApiDaResposta,
  erroDeConexao,
  errosPorCampo,
  mensagemApi,
  mensagemDoStatus,
} from '../src/erros';

/** O que o `HttpErrorResponse` do Angular tem de relevante: `status` e `error` (o corpo). */
const http = (status: number, error: unknown) => ({ status, error });

describe('corpoDoErro', () => {
  it('lê todos os campos do ApiError', () => {
    const corpo = corpoDoErro(
      {
        timestamp: '2026-10-14T10:00:00Z',
        status: 422,
        error: 'Unprocessable Entity',
        message: 'Confira os campos.',
        codigo: 'PLANO_INVALIDO',
        path: '/planos/1',
        requestId: 'abc-123',
        fieldErrors: [{ field: 'titulo', message: 'Obrigatório.' }],
      },
      422,
    );
    expect(corpo).toEqual({
      timestamp: '2026-10-14T10:00:00Z',
      status: 422,
      error: 'Unprocessable Entity',
      message: 'Confira os campos.',
      codigo: 'PLANO_INVALIDO',
      path: '/planos/1',
      requestId: 'abc-123',
      fieldErrors: [{ field: 'titulo', message: 'Obrigatório.' }],
    });
  });

  it('sem corpo legível usa o texto padrão do status e campos vazios', () => {
    const vazio = {
      timestamp: '',
      error: '',
      codigo: null,
      path: '',
      requestId: null,
      fieldErrors: null,
    };
    expect(corpoDoErro(undefined, 502)).toEqual({ ...vazio, status: 502, message: MENSAGEM_ERRO_DO_SERVIDOR });
    expect(corpoDoErro(null, 400)).toEqual({ ...vazio, status: 400, message: MENSAGEM_NAO_CONCLUIDO });
    expect(corpoDoErro('<html>', 404)).toEqual({ ...vazio, status: 404, message: MENSAGEM_NAO_CONCLUIDO });
    expect(corpoDoErro([1, 2], 500)).toEqual({ ...vazio, status: 500, message: MENSAGEM_ERRO_DO_SERVIDOR });
  });

  it('ignora campo com tipo errado e item de fieldErrors incompleto', () => {
    const corpo = corpoDoErro(
      { message: 42, codigo: 7, status: '422', fieldErrors: [{ field: 'a', message: 'ok' }, { field: 'b' }, 3, null] },
      422,
    );
    expect(corpo.message).toBe(MENSAGEM_NAO_CONCLUIDO);
    expect(corpo.codigo).toBeNull();
    expect(corpo.status).toBe(422);
    expect(corpo.fieldErrors).toEqual([{ field: 'a', message: 'ok' }]);
    expect(corpoDoErro({ fieldErrors: 'x' }, 400).fieldErrors).toBeNull();
  });

  it('mensagemDoStatus separa o erro do servidor do resto', () => {
    expect(mensagemDoStatus(500)).toBe(MENSAGEM_ERRO_DO_SERVIDOR);
    expect(mensagemDoStatus(499)).toBe(MENSAGEM_NAO_CONCLUIDO);
  });
});

describe('ErroApi', () => {
  it('guarda status, código e campos; status 0 é sem conexão', () => {
    const erro = new ErroApi(422, 'PLANO_INVALIDO', 'Confira.', [{ field: 'x', message: 'y' }]);
    expect(erro).toBeInstanceOf(Error);
    expect(erro.name).toBe('ErroApi');
    expect(erro.message).toBe('Confira.');
    expect(erro.status).toBe(422);
    expect(erro.codigo).toBe('PLANO_INVALIDO');
    expect(erro.campos).toEqual([{ field: 'x', message: 'y' }]);
    expect(erro.semConexao).toBe(false);
    expect(new ErroApi(0, 'SEM_CONEXAO', 'Sem rede.').semConexao).toBe(true);
    expect(new ErroApi(500, null, 'x').campos).toEqual([]);
  });

  it('erroApiDaResposta monta o erro a partir do status e do corpo', () => {
    const erro = erroApiDaResposta(409, { message: 'Outra aba alterou.', codigo: 'CONFLITO_EDICAO' });
    expect(erro).toMatchObject({ status: 409, codigo: 'CONFLITO_EDICAO', message: 'Outra aba alterou.', campos: [] });
    expect(erroApiDaResposta(503, undefined)).toMatchObject({
      status: 503,
      codigo: null,
      message: MENSAGEM_ERRO_DO_SERVIDOR,
    });
  });
});

describe('mensagemApi', () => {
  it('prefere a message da API ao primeiro campo', () => {
    const corpo = { message: 'Confira os campos.', fieldErrors: [{ field: 'a', message: 'Campo A.' }] };
    expect(mensagemApi(http(422, corpo), 'fallback')).toBe('Confira os campos.');
    expect(mensagemApi(new ErroApi(422, null, 'Confira os campos.', corpo.fieldErrors), 'fallback')).toBe(
      'Confira os campos.',
    );
  });

  it('sem message usa o primeiro campo inválido', () => {
    expect(mensagemApi(http(422, { fieldErrors: [{ field: 'a', message: 'Campo A.' }] }), 'fallback')).toBe('Campo A.');
    expect(mensagemApi(new ErroApi(422, null, '', [{ field: 'a', message: 'Campo A.' }]), 'fallback')).toBe('Campo A.');
  });

  it('status 0 sem mensagem usa o texto de sem conexão', () => {
    expect(mensagemApi(http(0, null), 'fallback')).toBe(MENSAGEM_SEM_CONEXAO);
    expect(mensagemApi(new ErroApi(0, 'SEM_CONEXAO', ''), 'fallback')).toBe(MENSAGEM_SEM_CONEXAO);
  });

  it('sem nada utilizável usa o fallback', () => {
    expect(mensagemApi(http(500, null), 'fallback')).toBe('fallback');
    expect(mensagemApi(http(500, {}), 'fallback')).toBe('fallback');
    expect(mensagemApi(new ErroApi(500, null, ''), 'fallback')).toBe('fallback');
  });

  it('erro que não veio da API usa sempre o fallback, nunca texto técnico', () => {
    expect(mensagemApi(new Error('Cannot read properties of undefined'), 'fallback')).toBe('fallback');
    expect(mensagemApi(new TypeError('Failed to fetch'), 'fallback')).toBe('fallback');
    expect(mensagemApi('texto', 'fallback')).toBe('fallback');
    expect(mensagemApi(null, 'fallback')).toBe('fallback');
    expect(mensagemApi(undefined, 'fallback')).toBe('fallback');
    expect(mensagemApi({ status: 'x', error: {} }, 'fallback')).toBe('fallback');
  });

  it('reconhece o ErroApi de outra cópia do módulo', () => {
    const estrangeiro = Object.assign(new Error('Vinda de outra cópia.'), { name: 'ErroApi', status: 400, campos: [] });
    expect(mensagemApi(estrangeiro, 'fallback')).toBe('Vinda de outra cópia.');
    const semCampos = Object.assign(new Error('x'), { name: 'ErroApi', status: 400 });
    expect(mensagemApi(semCampos, 'fallback')).toBe('fallback');
    const semStatus = Object.assign(new Error('x'), { name: 'ErroApi', campos: [] });
    expect(mensagemApi(semStatus, 'fallback')).toBe('fallback');
  });
});

describe('codigoApi, errosPorCampo e erroDeConexao', () => {
  it('codigoApi lê o código do ErroApi e do corpo; senão null', () => {
    expect(codigoApi(new ErroApi(409, 'CONFLITO_EDICAO', 'x'))).toBe('CONFLITO_EDICAO');
    expect(codigoApi(http(409, { codigo: 'CONFLITO' }))).toBe('CONFLITO');
    expect(codigoApi(http(409, {}))).toBeNull();
    expect(codigoApi(new ErroApi(500, null, 'x'))).toBeNull();
    expect(codigoApi(new Error('x'))).toBeNull();
    expect(codigoApi(undefined)).toBeNull();
  });

  it('errosPorCampo indexa pelo nome do campo (o último vence)', () => {
    const campos = [
      { field: 'nome', message: 'Obrigatório.' },
      { field: 'email', message: 'Inválido.' },
      { field: 'nome', message: 'Muito curto.' },
    ];
    expect(errosPorCampo(new ErroApi(422, null, 'x', campos))).toEqual({ nome: 'Muito curto.', email: 'Inválido.' });
    expect(errosPorCampo(http(422, { fieldErrors: campos }))).toEqual({ nome: 'Muito curto.', email: 'Inválido.' });
    expect(errosPorCampo(http(422, {}))).toEqual({});
    expect(errosPorCampo(new Error('x'))).toEqual({});
  });

  it('erroDeConexao só é verdadeiro com status 0', () => {
    expect(erroDeConexao(new ErroApi(0, 'SEM_CONEXAO', 'x'))).toBe(true);
    expect(erroDeConexao(http(0, null))).toBe(true);
    expect(erroDeConexao(new ErroApi(500, null, 'x'))).toBe(false);
    expect(erroDeConexao(new Error('x'))).toBe(false);
  });
});
