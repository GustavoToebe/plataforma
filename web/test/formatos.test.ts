import { describe, expect, it } from 'vitest';

import {
  EMAIL,
  UFS,
  cepValido,
  cnpjValido,
  cpfValido,
  emailValido,
  formatar,
  formatarCep,
  formatarCnpj,
  formatarCpf,
  formatarTelefone,
  somenteDigitos,
  telefoneValido,
  ufValida,
} from '../src/formatos';
import fixtures from './fixtures/formatos.json';

interface Caso {
  entrada: string;
  formatado: string;
}

const formatadores = {
  cpf: formatarCpf,
  cnpj: formatarCnpj,
  cep: formatarCep,
  telefone: formatarTelefone,
} as const;

const validadores = {
  cpf: cpfValido,
  cnpj: cnpjValido,
  cep: cepValido,
  telefone: telefoneValido,
  uf: ufValida,
} as const;

describe('formatos: fixtures compartilhadas com os backends', () => {
  for (const tipo of ['cpf', 'cnpj', 'cep', 'telefone', 'uf'] as const) {
    const { validos, invalidos } = fixtures[tipo] as { validos: Caso[]; invalidos: string[] };

    it(`${tipo}: aceita os válidos`, () => {
      for (const { entrada } of validos) {
        expect(validadores[tipo](entrada), entrada).toBe(true);
      }
    });

    it(`${tipo}: recusa os inválidos e os vazios`, () => {
      for (const entrada of [...invalidos, ...fixtures.vazios]) {
        expect(validadores[tipo](entrada), `"${entrada}"`).toBe(false);
      }
      expect(validadores[tipo](null)).toBe(false);
      expect(validadores[tipo](undefined)).toBe(false);
    });

    if (tipo !== 'uf') {
      it(`${tipo}: formata como o backend grava`, () => {
        for (const { entrada, formatado } of validos) {
          expect(formatadores[tipo](entrada), entrada).toBe(formatado);
          expect(formatar(tipo, entrada), entrada).toBe(formatado);
        }
      });
    }
  }

  it('e-mail: aceita os válidos e recusa os inválidos e os vazios', () => {
    for (const email of fixtures.email.validos) expect(emailValido(email), email).toBe(true);
    for (const email of [...fixtures.email.invalidos, ...fixtures.vazios]) expect(emailValido(email), email).toBe(false);
    expect(emailValido(null)).toBe(false);
    expect(emailValido(undefined)).toBe(false);
  });

  it('formatar vazio devolve texto vazio', () => {
    for (const mascara of ['cpf', 'cnpj', 'cep', 'telefone'] as const) {
      expect(formatar(mascara, '')).toBe('');
      expect(formatar(mascara, null)).toBe('');
      expect(formatar(mascara, undefined)).toBe('');
    }
  });
});

describe('formatos: máscara enquanto digita', () => {
  it('CPF', () => {
    expect(formatarCpf('529')).toBe('529');
    expect(formatarCpf('5299822')).toBe('529.982.2');
    expect(formatarCpf('52998224725999')).toBe('529.982.247-25');
  });

  it('CNPJ numérico e alfanumérico, em maiúsculas', () => {
    expect(formatarCnpj('11222333000181')).toBe('11.222.333/0001-81');
    expect(formatarCnpj('12abc34501de35')).toBe('12.ABC.345/01DE-35');
    expect(formatarCnpj('12abc3')).toBe('12.ABC.3');
    expect(formatarCnpj('12abc34501de3599')).toBe('12.ABC.345/01DE-35');
  });

  it('CEP', () => {
    expect(formatarCep('85800')).toBe('85800');
    expect(formatarCep('858000')).toBe('85800-0');
    expect(formatarCep('858000009999')).toBe('85800-000');
  });

  it('telefone: fixo, celular e +55', () => {
    expect(formatarTelefone('4')).toBe('(4');
    expect(formatarTelefone('45')).toBe('(45');
    expect(formatarTelefone('45999')).toBe('(45) 999');
    expect(formatarTelefone('4532221')).toBe('(45) 3222-1');
    expect(formatarTelefone('4532221111')).toBe('(45) 3222-1111');
    expect(formatarTelefone('45999998888')).toBe('(45) 99999-8888');
    expect(formatarTelefone('+55 45 99999-8888')).toBe('(45) 99999-8888');
    expect(formatarTelefone('+55 45 3222-1111')).toBe('(45) 3222-1111');
    expect(formatarTelefone('45999998888777')).toBe('(45) 99999-8888');
  });

  it('somenteDigitos aceita vazio', () => {
    expect(somenteDigitos('(45) 99999-8888')).toBe('45999998888');
    expect(somenteDigitos(null)).toBe('');
    expect(somenteDigitos(undefined)).toBe('');
  });
});

describe('formatos: regras do backend', () => {
  it('as 27 UFs, sem repetição', () => {
    expect(UFS).toHaveLength(27);
    expect(new Set(UFS).size).toBe(27);
  });

  it('o CNPJ vale cada caractere pelo código menos 48 (A = 17)', () => {
    // Exemplo oficial da Receita Federal para o CNPJ alfanumérico: o dígito errado em qualquer posição derruba.
    expect(cnpjValido('12ABC34501DE35')).toBe(true);
    expect(cnpjValido('12ABC34501DE34')).toBe(false);
    expect(cnpjValido('12ABC34501DF35')).toBe(false);
  });

  it('o DDD não pode ter zero em nenhuma das duas posições (como o Formatos.java)', () => {
    expect(telefoneValido('(05) 99999-8888')).toBe(false);
    expect(telefoneValido('(10) 3222-1111')).toBe(false);
    expect(telefoneValido('(11) 3222-1111')).toBe(true);
  });

  it('e-mail é a expressão de Formatos.EMAIL; o @Email do Hibernate, que o backend soma a ela, é mais rígido', () => {
    expect(EMAIL.source).toBe('^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$');
    // Passa aqui e o servidor recusa (Hibernate não aceita pontos seguidos no domínio): o servidor é quem decide.
    expect(emailValido('a@b..com')).toBe(true);
  });
});
