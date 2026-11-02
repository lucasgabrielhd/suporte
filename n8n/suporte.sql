-- WA Suporte FC — schema "suporte" no Postgres do CRM (banco crm).
-- Só cria coisas novas: não altera nenhuma tabela existente do CRM (public.*), só lê clientes/pedidos pro card da cliente.
-- Pode rodar de novo quantas vezes quiser (idempotente).
-- Retenção: fotos e áudios somem com 7 dias; texto das mensagens (incluindo transcrição dos áudios) fica 1 ano. A rota 'limpar' roda de hora em hora pelo fluxo n8n.

create schema if not exists suporte;

create table if not exists suporte.conversas (
  conv text primary key,
  aparelho text not null default '',
  nome text not null default '',
  numero text not null default '',
  ultima_msg text not null default '',
  ultima_direcao text not null default 'in',
  ultima_em timestamptz not null default now(),
  nao_lidas int not null default 0,
  status text not null default 'aberta',
  tem_botao boolean not null default true,
  criado_em timestamptz not null default now()
);
create index if not exists conversas_ultima_idx on suporte.conversas (ultima_em desc);
alter table suporte.conversas add column if not exists nome_crm text;
alter table suporte.conversas add column if not exists crm jsonb;
alter table suporte.conversas add column if not exists crm_em timestamptz;

create table if not exists suporte.mensagens (
  id bigserial primary key,
  conv text not null references suporte.conversas(conv) on delete cascade on update cascade,
  direcao text not null,
  tipo text not null default 'texto',
  texto text not null default '',
  transcricao text,
  legenda text,
  duracao int,
  evento text unique,
  status text not null default 'recebida',
  erro text,
  autor text,
  tentativas int not null default 0,
  criado_em timestamptz not null default now(),
  atualizado_em timestamptz not null default now(),
  enviado_em timestamptz
);
create index if not exists mensagens_conv_idx on suporte.mensagens (conv, criado_em, id);
create index if not exists mensagens_saida_idx on suporte.mensagens (status, id) where direcao = 'out';
create index if not exists mensagens_criado_idx on suporte.mensagens (criado_em);

create table if not exists suporte.midias (
  id bigserial primary key,
  mensagem_id bigint not null references suporte.mensagens(id) on delete cascade,
  mime text not null,
  dados text not null,
  criado_em timestamptz not null default now()
);
create index if not exists midias_msg_idx on suporte.midias (mensagem_id);
create index if not exists midias_criado_idx on suporte.midias (criado_em);

create table if not exists suporte.config (
  chave text primary key,
  valor text not null
);

create table if not exists suporte.respostas (
  id bigserial primary key,
  atalho text not null unique,
  texto text not null,
  ordem int not null default 100,
  atualizado_em timestamptz not null default now()
);

insert into suporte.respostas (atalho, texto, ordem)
select v.atalho, v.texto, v.ordem
  from (values
    ('oi', 'Oi, {nome}! Aqui é {atendente}, do suporte da Face Clean 💙 Como posso te ajudar?', 10),
    ('rastreio', E'Olá, {nome}! Tudo bem? 💙\n\nSeu pedido #{pedido} já está a caminho. Seguem os dados para acompanhar:\n\n📦 Código: {rastreio}\n🔎 Link de rastreamento: {link_rastreio}\n📍 Última atualização: {ultima_atualizacao}\n📅 Previsão de entrega: {previsao}\n\n{aviso_envio}\n\nQualquer dúvida, estamos à disposição! 😊\n\nEquipe Face Clean 🌿', 20),
    ('status', E'Olá, {nome}! 💙 Seu pedido #{pedido} está com status: {status}.\nAssim que for postado, te mando o código de rastreio por aqui.', 30),
    ('pedido', 'Pode me passar o número do pedido ou o CPF da compra, por favor? Assim eu já verifico pra você.', 40),
    ('prazo', 'O prazo começa a contar depois que o pedido é postado. Assim que tiver atualização eu te aviso por aqui, tá bom?', 50),
    ('obrigada', 'Por nada, {nome}! Qualquer coisa é só chamar por aqui 💙', 60)
  ) as v(atalho, texto, ordem)
 where not exists (select 1 from suporte.respostas);

create table if not exists suporte.rastreio_cache (
  codigo text primary key,
  dados jsonb not null,
  atualizado_em timestamptz not null default now()
);

create table if not exists suporte.aparelhos (
  aparelho text primary key,
  dados jsonb not null default '{}'::jsonb,
  visto_em timestamptz,
  painel_visto_em timestamptz
);

-- ---------------------------------------------------------------- entrada (app → painel)

create or replace function suporte.r_in(b jsonb) returns jsonb
language plpgsql as $fn$
declare
  v_tipo text := coalesce(b->>'tipo', 'msgs');
  v_ap text := coalesce(b->>'aparelho', '');
  v_conv text := coalesce(b->>'conv', '');
  v_numero text := regexp_replace(coalesce(b->>'numero', ''), '\D', '', 'g');
  v_antiga text;
  m jsonb;
  md jsonb;
  ak jsonb;
  v_id bigint;
  v_novas int := 0;
  v_resumo text := '';
begin
  if v_tipo = 'ack' then
    for ak in select * from jsonb_array_elements(coalesce(b->'acks', '[]'::jsonb)) loop
      if coalesce(ak->>'id', '') ~ '^\d+$' then
        update suporte.mensagens
           set status = coalesce(nullif(ak->>'status', ''), 'falhou'),
               erro = nullif(ak->>'erro', ''),
               enviado_em = case when ak->>'status' = 'enviado' then coalesce(enviado_em, now()) else enviado_em end,
               atualizado_em = now()
         where id = (ak->>'id')::bigint and direcao = 'out' and status <> 'enviado';
      end if;
    end loop;
    return jsonb_build_object('ok', true);
  end if;

  if v_tipo <> 'msgs' or v_conv = '' then
    return jsonb_build_object('ok', false, 'erro', 'payload_invalido');
  end if;

  -- contato salvo chega como "nome:Fulana"; se depois descobrir que é a mesma conversa pelo número, junta na conversa antiga
  if v_numero <> '' then
    select conv into v_antiga from suporte.conversas where numero = v_numero and conv <> v_conv and aparelho = v_ap order by ultima_em desc limit 1;
    if v_antiga is not null and not exists (select 1 from suporte.conversas where conv = v_conv) then
      update suporte.conversas set conv = v_conv where conv = v_antiga;
    end if;
  end if;

  insert into suporte.conversas as c (conv, aparelho, nome, numero)
  values (v_conv, v_ap, coalesce(b->>'nome', ''), v_numero)
  on conflict (conv) do update
     set aparelho = excluded.aparelho,
         nome = case when excluded.nome <> '' then excluded.nome else c.nome end,
         numero = case when excluded.numero <> '' then excluded.numero else c.numero end,
         tem_botao = true;

  update suporte.conversas set nome_crm = suporte.nome_crm(numero)
   where conv = v_conv and numero <> '' and nome_crm is null;
  update suporte.conversas set crm = suporte.crm_resumo(numero), crm_em = now()
   where conv = v_conv and numero <> '' and (crm_em is null or crm_em < now() - interval '30 minutes');

  for m in select * from jsonb_array_elements(coalesce(b->'msgs', '[]'::jsonb)) loop
    v_id := null;
    insert into suporte.mensagens (conv, direcao, tipo, texto, transcricao, legenda, duracao, evento, erro, criado_em)
    values (
      v_conv, 'in',
      coalesce(nullif(m->>'tipo', ''), 'texto'),
      coalesce(m->>'texto', ''),
      nullif(m->>'transcricao', ''),
      nullif(m->>'legenda', ''),
      case when coalesce(m->>'duracao', '') ~ '^\d+$' then (m->>'duracao')::int end,
      nullif(m->>'id', ''),
      nullif(m->>'erro', ''),
      case when coalesce(m->>'t', '') ~ '^\d{12,14}$' then least(to_timestamp((m->>'t')::bigint / 1000.0), now()) else now() end
    )
    on conflict (evento) do nothing
    returning id into v_id;

    if v_id is not null then
      v_novas := v_novas + 1;
      for md in select * from jsonb_array_elements(coalesce(m->'midias', '[]'::jsonb)) loop
        if coalesce(md->>'b64', '') <> '' then
          insert into suporte.midias (mensagem_id, mime, dados) values (v_id, coalesce(nullif(md->>'mime', ''), 'application/octet-stream'), md->>'b64');
        end if;
      end loop;
      v_resumo := case m->>'tipo'
        when 'audio' then '🎤 ' || coalesce(nullif(m->>'transcricao', ''), 'Áudio')
        when 'imagem' then '📷 ' || coalesce(nullif(m->>'legenda', ''), 'Foto')
        else coalesce(m->>'texto', '') end;
    end if;
  end loop;

  if v_novas > 0 then
    update suporte.conversas
       set ultima_msg = left(v_resumo, 200), ultima_direcao = 'in', ultima_em = now(),
           nao_lidas = nao_lidas + v_novas, status = 'aberta'
     where conv = v_conv;
  end if;

  return jsonb_build_object('ok', true, 'novas', v_novas);
end
$fn$;

-- ---------------------------------------------------------------- saída (painel → app)

create or replace function suporte.r_out(b jsonb) returns jsonb
language plpgsql as $fn$
declare
  v_ap text := coalesce(b->>'aparelho', '');
  v_botoes text[];
  v_itens jsonb;
  v_rapido boolean;
begin
  insert into suporte.aparelhos (aparelho, dados, visto_em)
  values (v_ap, coalesce(b->'status', '{}'::jsonb), now())
  on conflict (aparelho) do update set dados = excluded.dados, visto_em = now();

  if jsonb_typeof(b->'botoes') = 'array' then
    v_botoes := array(select jsonb_array_elements_text(b->'botoes'));
    update suporte.conversas
       set tem_botao = (conv = any(v_botoes))
     where aparelho = v_ap and tem_botao is distinct from (conv = any(v_botoes));
  end if;

  update suporte.mensagens m
     set status = 'falhou', erro = 'celular_nao_confirmou', atualizado_em = now()
    from suporte.conversas c
   where c.conv = m.conv and c.aparelho = v_ap and m.direcao = 'out'
     and m.status = 'enviando' and m.tentativas >= 5 and m.atualizado_em < now() - interval '90 seconds';

  with alvo as (
    select m.id
      from suporte.mensagens m
      join suporte.conversas c on c.conv = m.conv
     where m.direcao = 'out' and c.aparelho = v_ap
       and (m.status = 'pendente' or (m.status = 'enviando' and m.atualizado_em < now() - case when m.tipo = 'imagem' then interval '3 minutes' else interval '90 seconds' end))
     order by m.id
     limit 5
       for update of m skip locked
  ), upd as (
    update suporte.mensagens m
       set status = 'enviando', tentativas = m.tentativas + 1, atualizado_em = now()
      from alvo
     where m.id = alvo.id
    returning m.id, m.conv, m.texto, m.tipo
  )
  select coalesce(jsonb_agg(jsonb_build_object('id', upd.id::text, 'conv', upd.conv, 'texto', upd.texto, 'numero', c.numero, 'nome', c.nome, 'tipo', upd.tipo,
           'midia', case when upd.tipo = 'imagem' then (select jsonb_build_object('mime', d.mime, 'b64', d.dados) from suporte.midias d where d.mensagem_id = upd.id order by d.id limit 1) end) order by upd.id), '[]'::jsonb)
    into v_itens
    from upd join suporte.conversas c on c.conv = upd.conv;

  select exists(select 1 from suporte.aparelhos where aparelho = '_painel' and painel_visto_em > now() - interval '10 minutes') into v_rapido;

  return jsonb_build_object('ok', true, 'itens', v_itens, 'rapido', v_rapido);
end
$fn$;

-- ---------------------------------------------------------------- código de serviço da Frenet pro rastreio

create or replace function suporte.servico_frenet(p_transp text, p_codigo text, p_url text) returns text
language sql immutable as $fn$
  select case
    when coalesce(p_transp, '') ~ '_LOG_DRPOFF$' then 'LOG_DRPOFF'
    when coalesce(p_transp, '') ~ '_JTE_INT$' then 'JTE_INT'
    when coalesce(p_transp, '') ~ '_F_3$' then 'F_3'
    when coalesce(p_transp, '') ~ '_\d{5}(_BACKUP)?$' then substring(p_transp from '_(\d{5})(?:_BACKUP)?$')
    when coalesce(p_url, '') ~ '/JAD/' or coalesce(p_codigo, '') ~ '^\d{9}$' then 'F_3'
    when coalesce(p_url, '') ~ '/JTE/' or coalesce(p_codigo, '') ~ '^888\d{12}$' then 'JTE_INT'
    when coalesce(p_url, '') ~ '/COR/' or coalesce(p_codigo, '') ~ '^[A-Z]{2}\d{9}BR$' then '03298'
    when coalesce(p_url, '') ~* '(loggi|/LOG/)' then 'LOG_DRPOFF'
    else null end
$fn$;

-- ---------------------------------------------------------------- nome da cliente no CRM (pro contato que não está salvo no celular)

create or replace function suporte.nome_crm(p_numero text) returns text
language plpgsql stable as $fn$
declare
  v_num text := regexp_replace(coalesce(p_numero, ''), '\D', '', 'g');
  v_alts text[];
  v_nome text;
begin
  if length(v_num) < 12 then return null; end if;
  v_alts := array[v_num];
  if length(v_num) = 13 and substr(v_num, 5, 1) = '9' then v_alts := v_alts || (substr(v_num, 1, 4) || substr(v_num, 6)); end if;
  if length(v_num) = 12 then v_alts := v_alts || (substr(v_num, 1, 4) || '9' || substr(v_num, 5)); end if;
  select nullif(trim(c.nome), '') into v_nome from public.clientes c
   where c.telefone = any(v_alts) and c.mesclado_em is null
   order by c.ultimo_pedido_at desc nulls last limit 1;
  return v_nome;
exception when others then
  return null;
end
$fn$;

-- ---------------------------------------------------------------- resumo pra lista: é cliente? pedido a caminho? (aba Clientes)

create or replace function suporte.crm_resumo(p_numero text) returns jsonb
language plpgsql stable as $fn$
declare
  v_num text := regexp_replace(coalesce(p_numero, ''), '\D', '', 'g');
  v_alts text[];
  v_r jsonb;
begin
  if length(v_num) < 12 then return jsonb_build_object('pedidos', 0); end if;
  v_alts := array[v_num];
  if length(v_num) = 13 and substr(v_num, 5, 1) = '9' then v_alts := v_alts || (substr(v_num, 1, 4) || substr(v_num, 6)); end if;
  if length(v_num) = 12 then v_alts := v_alts || (substr(v_num, 1, 4) || '9' || substr(v_num, 5)); end if;
  select jsonb_build_object(
           'pedidos', count(*) filter (where p.pago),
           'ultimo', max(p.paid_at),
           'situacao', case
              when bool_or(p.status = 'Em transporte' and p.created_at_yampi > now() - interval '90 days') then 'a_caminho'
              when bool_or(p.status in ('Faturado', 'Pagamento aprovado') and p.created_at_yampi > now() - interval '60 days') then 'preparando'
              when count(*) filter (where p.pago) > 0 then 'cliente'
              when bool_or(p.status = 'Aguardando pagamento' and p.created_at_yampi > now() - interval '7 days') then 'aguardando_pagamento'
            end)
    into v_r
    from public.clientes c
    join public.pedidos p on p.cliente_id = c.id
   where c.telefone = any(v_alts) and c.mesclado_em is null;
  return coalesce(v_r, jsonb_build_object('pedidos', 0));
exception when others then
  return jsonb_build_object('pedidos', 0);
end
$fn$;

-- ---------------------------------------------------------------- card da cliente (lê o CRM, não escreve nada lá)

create or replace function suporte.cliente_crm(p_numero text) returns jsonb
language plpgsql as $fn$
declare
  v_num text := regexp_replace(coalesce(p_numero, ''), '\D', '', 'g');
  v_alts text[];
  v_cli jsonb;
  v_id bigint;
  v_ped jsonb;
begin
  if length(v_num) < 12 then return null; end if;
  v_alts := array[v_num];
  if length(v_num) = 13 and substr(v_num, 5, 1) = '9' then v_alts := v_alts || (substr(v_num, 1, 4) || substr(v_num, 6)); end if;
  if length(v_num) = 12 then v_alts := v_alts || (substr(v_num, 1, 4) || '9' || substr(v_num, 5)); end if;

  select c.id, jsonb_build_object(
           'id', c.id, 'nome', c.nome, 'telefone', c.telefone, 'email', c.email::text,
           'cidade', c.cidade, 'uf', c.uf, 'pedidos', c.pedidos_count, 'ltv', c.ltv,
           'rfm', c.rfm_classe, 'primeiro_pedido', c.primeiro_pedido_at, 'ultimo_pedido', c.ultimo_pedido_at,
           'optout', c.optout, 'ficha', c.crm_ficha)
    into v_id, v_cli
    from public.clientes c
   where c.telefone = any(v_alts) and c.mesclado_em is null
   order by c.ultimo_pedido_at desc nulls last
   limit 1;

  if v_id is null then return null; end if;

  select coalesce(jsonb_agg(x order by x->>'criado' desc), '[]'::jsonb) into v_ped
    from (
      select jsonb_build_object(
               'numero', p.numero, 'status', p.status, 'pago', p.pago, 'valor', p.valor_total,
               'metodo', p.metodo_pagamento, 'criado', p.created_at_yampi, 'pago_em', p.paid_at,
               'enviado', p.enviado_at, 'entregue', p.entregue_at, 'previsao', p.previsao_entrega,
               'transportadora', p.transportadora, 'rastreio', p.payload->>'track_code', 'rastreio_url', p.payload->>'track_url',
               'servico', suporte.servico_frenet(p.transportadora, p.payload->>'track_code', p.payload->>'track_url'),
               'rastreio_cache', (select jsonb_build_object('dados', r.dados, 'em', r.atualizado_em) from suporte.rastreio_cache r where r.codigo = p.payload->>'track_code'),
               'itens', (select coalesce(jsonb_agg(jsonb_build_object('t', i->>'title', 'q', i->>'qty')), '[]'::jsonb) from jsonb_array_elements(coalesce(p.itens, '[]'::jsonb)) i)
             ) x
        from public.pedidos p
       where p.cliente_id = v_id
       order by p.created_at_yampi desc nulls last
       limit 5
    ) s;

  return v_cli || jsonb_build_object('ultimos_pedidos', v_ped, 'eventos', (
    select coalesce(jsonb_agg(x order by x->>'em' desc), '[]'::jsonb) from (
      select jsonb_build_object('tipo', e.tipo, 'etapa', e.etapa, 'variant', e.variant,
               'msg', left(e.payload->>'mensagem', 180), 'via', e.payload->>'via', 'em', e.occurred_at) x
        from public.eventos e
       where e.cliente_id = v_id and e.tipo not in ('wa_enfileirado', 'wa_descartado')
       order by e.occurred_at desc
       limit 40) t));
exception when others then
  return jsonb_build_object('erro', 'crm_indisponivel: ' || sqlerrm);
end
$fn$;

-- ---------------------------------------------------------------- painel

create or replace function suporte.r_painel(b jsonb) returns jsonb
language plpgsql as $fn$
declare
  v_acao text := coalesce(b->>'acao', 'convs');
  v_conv text := coalesce(b->>'conv', '');
  v_busca text := nullif(trim(coalesce(b->>'busca', '')), '');
  v_txt text;
  v_id bigint;
  v_r jsonb;
  v_c record;
begin
  insert into suporte.aparelhos (aparelho, painel_visto_em) values ('_painel', now())
  on conflict (aparelho) do update set painel_visto_em = now();

  if v_acao = 'convs' then
    update suporte.conversas set crm = suporte.crm_resumo(numero), crm_em = now()
     where conv in (select conv from suporte.conversas
                     where status = 'aberta' and numero <> '' and (crm_em is null or crm_em < now() - interval '3 hours')
                     order by ultima_em desc limit 15);
    select coalesce(jsonb_agg(jsonb_build_object(
             'conv', c.conv, 'nome', c.nome, 'numero', c.numero, 'ultima_msg', c.ultima_msg,
             'ultima_direcao', c.ultima_direcao, 'ultima_em', c.ultima_em, 'nao_lidas', c.nao_lidas,
             'status', c.status, 'tem_botao', c.tem_botao, 'aparelho', c.aparelho, 'espera_desde', c.espera_desde, 'nome_crm', c.nome_crm, 'crm', c.crm)
             order by case when coalesce(b->>'filtro', '') in ('esperando', 'clientes') then c.espera_desde end asc nulls last, c.ultima_em desc), '[]'::jsonb)
      into v_r
      from (
        select c.*,
               case when c.ultima_direcao = 'in' and c.status = 'aberta' then (
                 select min(m.criado_em) from suporte.mensagens m
                  where m.conv = c.conv and m.direcao = 'in'
                    and m.criado_em > coalesce((select max(o.criado_em) from suporte.mensagens o where o.conv = c.conv and o.direcao = 'out' and o.status <> 'cancelada'), '-infinity'::timestamptz)
               ) end as espera_desde
          from suporte.conversas c
         where (coalesce(b->>'filtro', 'abertas') = 'todas'
                or (coalesce(b->>'filtro', 'abertas') = 'abertas' and c.status = 'aberta')
                or (b->>'filtro' = 'esperando' and c.status = 'aberta' and c.ultima_direcao = 'in')
                or (b->>'filtro' = 'clientes' and c.status = 'aberta' and coalesce((c.crm->>'pedidos')::int, 0) > 0))
           and (v_busca is null or c.nome ilike '%' || v_busca || '%' or coalesce(c.nome_crm, '') ilike '%' || v_busca || '%' or c.numero like '%' || regexp_replace(v_busca, '\D', '', 'g') || '%' and regexp_replace(v_busca, '\D', '', 'g') <> '')
         order by c.ultima_em desc
         limit 300
      ) c;
    return jsonb_build_object(
      'ok', true,
      'convs', v_r,
      'agora', now(),
      'aparelhos', (select coalesce(jsonb_agg(jsonb_build_object('aparelho', a.aparelho, 'visto_em', a.visto_em,
                      'segundos', extract(epoch from now() - a.visto_em)::int, 'dados', a.dados)), '[]'::jsonb)
                      from suporte.aparelhos a where a.aparelho <> '_painel'),
      'nao_lidas', (select coalesce(sum(nao_lidas), 0) from suporte.conversas where status = 'aberta'),
      'esperando', (select count(*) from suporte.conversas where status = 'aberta' and ultima_direcao = 'in'),
      'clientes_esperando', (select count(*) from suporte.conversas where status = 'aberta' and ultima_direcao = 'in' and coalesce((crm->>'pedidos')::int, 0) > 0));
  end if;

  if v_acao = 'respostas' then
    return jsonb_build_object('ok', true, 'respostas',
      (select coalesce(jsonb_agg(jsonb_build_object('id', r.id::text, 'atalho', r.atalho, 'texto', r.texto) order by r.ordem, r.atalho), '[]'::jsonb) from suporte.respostas r));
  end if;

  if v_acao = 'resposta_salvar' then
    v_txt := trim(coalesce(b->>'texto', ''));
    if v_txt = '' or trim(coalesce(b->>'atalho', '')) = '' then return jsonb_build_object('ok', false, 'erro', 'atalho_e_texto_obrigatorios'); end if;
    if coalesce(b->>'id', '') ~ '^\d+$' then
      update suporte.respostas set atalho = lower(regexp_replace(trim(b->>'atalho'), '\s+', '-', 'g')), texto = v_txt, atualizado_em = now() where id = (b->>'id')::bigint;
    else
      insert into suporte.respostas (atalho, texto) values (lower(regexp_replace(trim(b->>'atalho'), '\s+', '-', 'g')), v_txt)
      on conflict (atalho) do update set texto = excluded.texto, atualizado_em = now();
    end if;
    return jsonb_build_object('ok', true);
  end if;

  if v_acao = 'resposta_apagar' then
    if coalesce(b->>'id', '') !~ '^\d+$' then return jsonb_build_object('ok', false, 'erro', 'id_invalido'); end if;
    delete from suporte.respostas where id = (b->>'id')::bigint;
    return jsonb_build_object('ok', true);
  end if;

  if v_acao = 'ocorrencias' then
    select coalesce(jsonb_agg(c), '[]'::jsonb) into v_r
      from (select distinct regexp_replace(x, '[^A-Za-z0-9]', '', 'g') c
              from jsonb_array_elements_text(coalesce(b->'codigos', '[]'::jsonb)) x
             where length(regexp_replace(x, '[^A-Za-z0-9]', '', 'g')) >= 6
             limit 6) t;
    if jsonb_array_length(v_r) = 0 then return jsonb_build_object('ok', true, 'ocorrencias', '[]'::jsonb); end if;
    return jsonb_build_object('ok', true, 'ocorr_codigos', v_r);
  end if;

  if v_acao = 'rastreio' then
    v_txt := nullif(trim(coalesce(b->>'codigo', '')), '');
    if v_txt is null then return jsonb_build_object('ok', false, 'erro', 'sem_codigo'); end if;
    if not coalesce((b->>'forcar')::boolean, false) then
      select jsonb_build_object('ok', true, 'codigo', r.codigo, 'em', r.atualizado_em, 'cache', true) || r.dados into v_r
        from suporte.rastreio_cache r where r.codigo = v_txt and r.atualizado_em > now() - interval '20 minutes';
      if v_r is not null then return v_r; end if;
    end if;
    select jsonb_build_object('ShippingServiceCode', suporte.servico_frenet(p.transportadora, p.payload->>'track_code', p.payload->>'track_url'),
                              'TrackingNumber', v_txt, 'OrderNumber', p.numero)
      into v_r
      from public.pedidos p where p.payload->>'track_code' = v_txt
     order by p.created_at_yampi desc nulls last limit 1;
    if v_r is null then
      v_r := jsonb_build_object('ShippingServiceCode', suporte.servico_frenet(nullif(b->>'transportadora', ''), v_txt, nullif(b->>'url', '')), 'TrackingNumber', v_txt);
    end if;
    if v_r->>'ShippingServiceCode' is null then return jsonb_build_object('ok', false, 'erro', 'transportadora_desconhecida', 'codigo', v_txt); end if;
    return jsonb_build_object('ok', true, 'codigo', v_txt, 'consulta', v_r);
  end if;

  if v_acao = 'chat' then
    select * into v_c from suporte.conversas where conv = v_conv;
    if not found then return jsonb_build_object('ok', false, 'erro', 'conversa_nao_encontrada'); end if;
    if coalesce((b->>'ler')::boolean, false) and v_c.nao_lidas > 0 then
      update suporte.conversas set nao_lidas = 0 where conv = v_conv;
    end if;
    select coalesce(jsonb_agg(x order by (x->>'criado_em')::timestamptz, (x->>'id')::bigint), '[]'::jsonb) into v_r
      from (
        select jsonb_build_object(
                 'id', m.id::text, 'direcao', m.direcao, 'tipo', m.tipo, 'texto', m.texto, 'transcricao', m.transcricao,
                 'legenda', m.legenda, 'duracao', m.duracao, 'status', m.status, 'erro', m.erro, 'autor', m.autor,
                 'criado_em', m.criado_em, 'enviado_em', m.enviado_em,
                 'midias', (select coalesce(jsonb_agg(jsonb_build_object('id', d.id::text, 'mime', d.mime) order by d.id), '[]'::jsonb) from suporte.midias d where d.mensagem_id = m.id)
               ) x
          from (select * from suporte.mensagens where conv = v_conv order by criado_em desc, id desc limit 400) m
      ) s;
    return jsonb_build_object(
      'ok', true,
      'conversa', jsonb_build_object('conv', v_c.conv, 'nome', v_c.nome, 'nome_crm', v_c.nome_crm, 'numero', v_c.numero, 'status', v_c.status, 'tem_botao', v_c.tem_botao),
      'mensagens', v_r,
      'cliente', case when coalesce((b->>'crm')::boolean, true) then suporte.cliente_crm(v_c.numero) end);
  end if;

  if v_acao = 'enviar' then
    v_txt := trim(coalesce(b->>'texto', ''));
    if v_txt = '' and coalesce(b->'midia'->>'b64', '') = '' then return jsonb_build_object('ok', false, 'erro', 'texto_vazio'); end if;
    if length(v_txt) > 4000 then return jsonb_build_object('ok', false, 'erro', 'texto_grande_demais'); end if;
    if length(coalesce(b->'midia'->>'b64', '')) > 4500000 then return jsonb_build_object('ok', false, 'erro', 'foto_grande_demais'); end if;
    select * into v_c from suporte.conversas where conv = v_conv;
    if not found then return jsonb_build_object('ok', false, 'erro', 'conversa_nao_encontrada'); end if;
    if coalesce(b->'midia'->>'b64', '') <> '' and length(v_c.numero) < 12 then return jsonb_build_object('ok', false, 'erro', 'sem_numero_pra_foto'); end if;
    insert into suporte.mensagens (conv, direcao, tipo, texto, legenda, status, autor, evento)
    values (v_conv, 'out',
            case when coalesce(b->'midia'->>'b64', '') <> '' then 'imagem' else 'texto' end,
            v_txt,
            case when coalesce(b->'midia'->>'b64', '') <> '' then nullif(v_txt, '') end,
            'pendente', nullif(b->>'autor', ''), nullif(b->>'idLocal', ''))
    on conflict (evento) do nothing
    returning id into v_id;
    if v_id is null then
      select id into v_id from suporte.mensagens where evento = b->>'idLocal';
    else
      if coalesce(b->'midia'->>'b64', '') <> '' then
        insert into suporte.midias (mensagem_id, mime, dados) values (v_id, coalesce(nullif(b->'midia'->>'mime', ''), 'image/jpeg'), b->'midia'->>'b64');
      end if;
      update suporte.conversas
         set ultima_msg = left(case when coalesce(b->'midia'->>'b64', '') <> '' then '📷 ' || coalesce(nullif(v_txt, ''), 'Foto') else v_txt end, 200),
             ultima_direcao = 'out', ultima_em = now(), nao_lidas = 0
       where conv = v_conv;
    end if;
    return jsonb_build_object('ok', true, 'id', v_id::text);
  end if;

  if v_acao = 'reenviar' then
    if coalesce(b->>'id', '') !~ '^\d+$' then return jsonb_build_object('ok', false, 'erro', 'id_invalido'); end if;
    update suporte.mensagens set status = 'pendente', tentativas = 0, erro = null, atualizado_em = now()
     where id = (b->>'id')::bigint and direcao = 'out' and status in ('falhou', 'sem_notificacao');
    return jsonb_build_object('ok', true);
  end if;

  if v_acao = 'cancelar' then
    if coalesce(b->>'id', '') !~ '^\d+$' then return jsonb_build_object('ok', false, 'erro', 'id_invalido'); end if;
    update suporte.mensagens set status = 'cancelada', atualizado_em = now()
     where id = (b->>'id')::bigint and direcao = 'out' and status in ('pendente', 'falhou', 'sem_notificacao');
    return jsonb_build_object('ok', true);
  end if;

  if v_acao = 'status' then
    update suporte.conversas set status = case when b->>'status' = 'resolvida' then 'resolvida' else 'aberta' end,
           nao_lidas = case when b->>'status' = 'resolvida' then 0 else nao_lidas end
     where conv = v_conv;
    return jsonb_build_object('ok', true);
  end if;

  if v_acao = 'midia' then
    if coalesce(b->>'id', '') !~ '^\d+$' then return jsonb_build_object('ok', false, 'erro', 'id_invalido'); end if;
    select coalesce(jsonb_build_object('mime', d.mime, 'b64', d.dados), '{}'::jsonb) into v_r
      from suporte.midias d where d.id = (b->>'id')::bigint;
    return jsonb_build_object('ok', v_r is not null, 'midia', v_r);
  end if;

  return jsonb_build_object('ok', false, 'erro', 'acao_invalida');
end
$fn$;

-- ---------------------------------------------------------------- limpeza (fotos e áudios 7 dias, texto 1 ano)

create or replace function suporte.r_limpar() returns jsonb
language plpgsql as $fn$
declare v_d int; v_m int; v_c int;
begin
  delete from suporte.midias where criado_em < now() - interval '7 days';
  get diagnostics v_d = row_count;
  delete from suporte.mensagens where criado_em < now() - interval '1 year';
  get diagnostics v_m = row_count;
  delete from suporte.conversas c
   where c.ultima_em < now() - interval '1 year'
     and not exists (select 1 from suporte.mensagens m where m.conv = c.conv);
  get diagnostics v_c = row_count;
  delete from suporte.rastreio_cache where atualizado_em < now() - interval '7 days';
  return jsonb_build_object('ok', true, 'midias_apagadas', v_d, 'mensagens_apagadas', v_m, 'conversas_apagadas', v_c);
end
$fn$;

-- ---------------------------------------------------------------- porta única chamada pelo n8n

create or replace function suporte.api(p jsonb) returns jsonb
language plpgsql as $fn$
declare r text := coalesce(p->>'rota', '');
begin
  if r = 'painel' and not exists (
    select 1 from suporte.config
     where chave = 'painel_token_sha256'
       and valor = encode(sha256(convert_to(coalesce(p->>'token', ''), 'UTF8')), 'hex')
  ) then
    return jsonb_build_object('ok', false, 'erro', 'nao_autorizado');
  end if;
  if r = 'in' then return suporte.r_in(coalesce(p->'body', '{}'::jsonb)); end if;
  if r = 'out' then return suporte.r_out(coalesce(p->'body', '{}'::jsonb)); end if;
  if r = 'painel' then return suporte.r_painel(coalesce(p->'body', '{}'::jsonb)); end if;
  if r = 'limpar' then return suporte.r_limpar(); end if;
  if r = 'rastreio_salvar' then
    insert into suporte.rastreio_cache (codigo, dados, atualizado_em)
    values (p->'body'->>'codigo', coalesce(p->'body'->'dados', '{}'::jsonb), now())
    on conflict (codigo) do update set dados = excluded.dados, atualizado_em = now();
    return jsonb_build_object('ok', true, 'codigo', p->'body'->>'codigo', 'em', now(), 'cache', false) || coalesce(p->'body'->'dados', '{}'::jsonb);
  end if;
  return jsonb_build_object('ok', false, 'erro', 'rota_invalida');
end
$fn$;

-- ---------------------------------------------------------------- preenche o nome do CRM nas conversas que já existem
update suporte.conversas set nome_crm = suporte.nome_crm(numero) where nome_crm is null and numero <> '';
update suporte.conversas set crm = suporte.crm_resumo(numero), crm_em = now() where status = 'aberta' and numero <> '';

-- ---------------------------------------------------------------- senha do painel
-- Troque TROQUE_PELO_TOKEN_DO_PAINEL pelo token do painel e rode esta linha (o banco guarda só o hash):
-- insert into suporte.config (chave, valor) values ('painel_token_sha256', encode(sha256(convert_to('TROQUE_PELO_TOKEN_DO_PAINEL', 'UTF8')), 'hex'))
--   on conflict (chave) do update set valor = excluded.valor;
