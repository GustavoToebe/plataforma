import { describe, expect, it, vi } from 'vitest';

import {
  CABECALHO_XSRF,
  cabecalhosDeCredencial,
  comRenovacao,
  desfechoDoRefresh,
  deveRenovar,
  lerCookie,
  metodoPrecisaXsrf,
  umaPorVez,
} from '../src/autenticacao';

/** Promessa que o teste resolve ou recusa quando quiser. */
function adiada<T>() {
  let resolver!: (valor: T) => void;
  let recusar!: (motivo: unknown) => void;
  const promessa = new Promise<T>((r, j) => {
    resolver = r;
    recusar = j;
  });
  return { promessa, resolver, recusar };
}

describe('XSRF', () => {
  it('só a escrita precisa do token, em qualquer caixa', () => {
    for (const metodo of ['POST', 'PUT', 'PATCH', 'DELETE', 'post', 'Delete']) {
      expect(metodoPrecisaXsrf(metodo), metodo).toBe(true);
    }
    for (const metodo of ['GET', 'HEAD', 'OPTIONS', 'TRACE', 'get', 'Head']) {
      expect(metodoPrecisaXsrf(metodo), metodo).toBe(false);
    }
  });

  it('cabecalhosDeCredencial leva o Bearer e, só em escrita com cookie, o XSRF', () => {
    expect(cabecalhosDeCredencial({ token: 'abc', xsrf: 'xyz', metodo: 'POST' })).toEqual({
      Authorization: 'Bearer abc',
      [CABECALHO_XSRF]: 'xyz',
    });
    expect(cabecalhosDeCredencial({ token: 'abc', xsrf: 'xyz', metodo: 'GET' })).toEqual({ Authorization: 'Bearer abc' });
    expect(cabecalhosDeCredencial({ token: 'abc', metodo: 'POST' })).toEqual({ Authorization: 'Bearer abc' });
    expect(cabecalhosDeCredencial({ token: null, xsrf: 'xyz', metodo: 'DELETE' })).toEqual({ [CABECALHO_XSRF]: 'xyz' });
    expect(cabecalhosDeCredencial({ token: null, xsrf: null, metodo: 'POST' })).toEqual({});
    expect(CABECALHO_XSRF).toBe('X-XSRF-TOKEN');
  });

  it('lerCookie acha o cookie pelo nome exato e decodifica o valor', () => {
    const cookies = 'a=1; TREINOS-XSRF-TOKEN=abc%20def; B=2';
    expect(lerCookie(cookies, 'TREINOS-XSRF-TOKEN')).toBe('abc def');
    expect(lerCookie(cookies, 'XSRF-TOKEN')).toBeNull();
    expect(lerCookie(cookies, 'a')).toBe('1');
    expect(lerCookie('', 'a')).toBeNull();
    expect(lerCookie('token=a=b=c', 'token')).toBe('a=b=c');
  });

  it('lerCookie devolve o valor cru se a decodificação falha', () => {
    expect(lerCookie('x=100%', 'x')).toBe('100%');
  });
});

describe('umaPorVez', () => {
  it('chamadas simultâneas esperam a mesma execução', async () => {
    const espera = adiada<string>();
    const tarefa = vi.fn(() => espera.promessa);
    const renovar = umaPorVez(tarefa);

    const a = renovar();
    const b = renovar();
    expect(a).toBe(b);
    expect(tarefa).toHaveBeenCalledTimes(1);

    espera.resolver('sessão');
    await expect(Promise.all([a, b])).resolves.toEqual(['sessão', 'sessão']);
  });

  it('depois de terminar, a próxima chamada começa uma execução nova', async () => {
    const tarefa = vi.fn(() => Promise.resolve('ok'));
    const renovar = umaPorVez(tarefa);
    await renovar();
    await renovar();
    expect(tarefa).toHaveBeenCalledTimes(2);
  });

  it('a falha vai a todos que esperavam e não trava a próxima', async () => {
    const espera = adiada<string>();
    const tarefa = vi.fn().mockReturnValueOnce(espera.promessa).mockResolvedValueOnce('de novo');
    const renovar = umaPorVez<string>(tarefa);

    const a = renovar();
    const b = renovar();
    espera.recusar(new Error('sem rede'));
    await expect(a).rejects.toThrow('sem rede');
    await expect(b).rejects.toThrow('sem rede');
    await expect(renovar()).resolves.toBe('de novo');
  });

  it('erro síncrono da tarefa vira promessa recusada e também não trava', async () => {
    const tarefa = vi
      .fn<() => Promise<string>>()
      .mockImplementationOnce(() => {
        throw new Error('quebrou');
      })
      .mockResolvedValueOnce('ok');
    const renovar = umaPorVez(tarefa);
    await expect(renovar()).rejects.toThrow('quebrou');
    await expect(renovar()).resolves.toBe('ok');
  });
});

describe('deveRenovar', () => {
  it('só o 401 renova', () => {
    expect(deveRenovar({ status: 401 })).toBe(true);
    for (const status of [0, 200, 400, 403, 404, 409, 422, 500]) {
      expect(deveRenovar({ status }), String(status)).toBe(false);
    }
  });

  it('rotas de sessão não renovam: exata ou por prefixo com barra', () => {
    const regras = { rotasSemRenovar: ['/auth/refresh', '/auth/logout', '/public/'] };
    expect(deveRenovar({ status: 401, caminho: '/auth/refresh' }, regras)).toBe(false);
    expect(deveRenovar({ status: 401, caminho: '/auth/refresh/x' }, regras)).toBe(false);
    expect(deveRenovar({ status: 401, caminho: '/auth/refreshing' }, regras)).toBe(true);
    expect(deveRenovar({ status: 401, caminho: '/auth/logout' }, regras)).toBe(false);
    expect(deveRenovar({ status: 401, caminho: '/public/inscricao' }, regras)).toBe(false);
    expect(deveRenovar({ status: 401, caminho: '/publico' }, regras)).toBe(true);
    expect(deveRenovar({ status: 401, caminho: '/aluno/painel' }, regras)).toBe(true);
    expect(deveRenovar({ status: 401, caminho: '/auth/mfa' }, { rotasSemRenovar: ['/auth/'] })).toBe(false);
  });

  it('sem caminho a lista de rotas não se aplica', () => {
    expect(deveRenovar({ status: 401 }, { rotasSemRenovar: ['/auth/'] })).toBe(true);
  });

  it('código de regra que veio como 401 não renova: exato ou por prefixo com asterisco', () => {
    const regras = { codigosSemRenovar: ['CONFLITO_EDICAO', 'MFA_*'] };
    expect(deveRenovar({ status: 401, codigo: 'CONFLITO_EDICAO' }, regras)).toBe(false);
    expect(deveRenovar({ status: 401, codigo: 'CONFLITO_EDICAO_2' }, regras)).toBe(true);
    expect(deveRenovar({ status: 401, codigo: 'MFA_CODIGO_INVALIDO' }, regras)).toBe(false);
    expect(deveRenovar({ status: 401, codigo: 'MFA_NECESSARIO' }, regras)).toBe(false);
    expect(deveRenovar({ status: 401, codigo: 'TOKEN_EXPIRADO' }, regras)).toBe(true);
    expect(deveRenovar({ status: 401, codigo: null }, regras)).toBe(true);
    expect(deveRenovar({ status: 401, codigo: '' }, regras)).toBe(true);
  });
});

describe('desfechoDoRefresh', () => {
  it('2xx renovou, 401 e 403 são recusa e o resto é falha que mantém a sessão', () => {
    expect(desfechoDoRefresh(200)).toBe('renovada');
    expect(desfechoDoRefresh(204)).toBe('renovada');
    expect(desfechoDoRefresh(401)).toBe('encerrar');
    expect(desfechoDoRefresh(403)).toBe('encerrar');
    for (const status of [0, 400, 404, 429, 500, 502, 503]) {
      expect(desfechoDoRefresh(status), String(status)).toBe('falhou');
    }
  });
});

describe('comRenovacao', () => {
  const sempre = () => true;

  it('resposta que não pede renovação passa direto', async () => {
    const enviar = vi.fn(() => Promise.resolve('ok'));
    const renovar = vi.fn();
    const aoEncerrar = vi.fn();

    await expect(comRenovacao(enviar, { deveRenovar: () => false, renovar, aoEncerrar })).resolves.toBe('ok');
    expect(enviar).toHaveBeenCalledTimes(1);
    expect(renovar).not.toHaveBeenCalled();
    expect(aoEncerrar).not.toHaveBeenCalled();
  });

  it('renova e repete uma vez, devolvendo a resposta da repetição (sem renovar de novo)', async () => {
    const enviar = vi.fn().mockResolvedValueOnce('401').mockResolvedValueOnce('200');
    const renovar = vi.fn(() => Promise.resolve(true));
    const aoEncerrar = vi.fn();

    await expect(comRenovacao(enviar, { deveRenovar: sempre, renovar, aoEncerrar })).resolves.toBe('200');
    expect(enviar).toHaveBeenCalledTimes(2);
    expect(renovar).toHaveBeenCalledTimes(1);
    expect(aoEncerrar).not.toHaveBeenCalled();
  });

  it('a repetição que também vem 401 é devolvida como está, sem laço', async () => {
    const enviar = vi.fn().mockResolvedValue('401');
    const renovar = vi.fn(() => Promise.resolve(true));

    await expect(comRenovacao(enviar, { deveRenovar: sempre, renovar, aoEncerrar: vi.fn() })).resolves.toBe('401');
    expect(enviar).toHaveBeenCalledTimes(2);
    expect(renovar).toHaveBeenCalledTimes(1);
  });

  it('renovação recusada encerra a sessão e devolve a resposta original', async () => {
    const enviar = vi.fn().mockResolvedValue('401');
    const aoEncerrar = vi.fn();

    await expect(
      comRenovacao(enviar, { deveRenovar: sempre, renovar: () => Promise.resolve(false), aoEncerrar }),
    ).resolves.toBe('401');
    expect(enviar).toHaveBeenCalledTimes(1);
    expect(aoEncerrar).toHaveBeenCalledTimes(1);
  });

  it('erro ao renovar (sem rede) propaga e não encerra a sessão', async () => {
    const enviar = vi.fn().mockResolvedValue('401');
    const aoEncerrar = vi.fn();
    const renovar = () => Promise.reject(new Error('sem rede'));

    await expect(comRenovacao(enviar, { deveRenovar: sempre, renovar, aoEncerrar })).rejects.toThrow('sem rede');
    expect(aoEncerrar).not.toHaveBeenCalled();
  });

  it('deveRenovar pode ser assíncrono (ler o corpo do erro)', async () => {
    const enviar = vi.fn().mockResolvedValueOnce('401').mockResolvedValueOnce('200');
    const deve = vi.fn(() => Promise.resolve(true));

    await expect(
      comRenovacao(enviar, { deveRenovar: deve, renovar: () => Promise.resolve(true), aoEncerrar: vi.fn() }),
    ).resolves.toBe('200');
    expect(deve).toHaveBeenCalledWith('401');
  });

  it('com umaPorVez, dois 401 simultâneos fazem uma renovação só e cada requisição se repete uma vez', async () => {
    const espera = adiada<boolean>();
    const refresh = vi.fn(() => espera.promessa);
    const renovar = umaPorVez(refresh);
    const vistos = new Map<string, number>();
    const enviarDe = (caminho: string) => () => {
      const vez = (vistos.get(caminho) ?? 0) + 1;
      vistos.set(caminho, vez);
      return Promise.resolve(vez === 1 ? 401 : 200);
    };
    const opcoes = { deveRenovar: (status: number) => status === 401, renovar, aoEncerrar: vi.fn() };

    const a = comRenovacao(enviarDe('/a'), opcoes);
    const b = comRenovacao(enviarDe('/b'), opcoes);
    await Promise.resolve();
    espera.resolver(true);

    await expect(Promise.all([a, b])).resolves.toEqual([200, 200]);
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(vistos.get('/a')).toBe(2);
    expect(vistos.get('/b')).toBe(2);
    expect(opcoes.aoEncerrar).not.toHaveBeenCalled();
  });
});
