# WA Suporte FC

App Android **só pro celular do suporte**. Ele lê as notificações do WhatsApp, manda cada mensagem da cliente pro
painel HTML e responde pelo botão "Responder" da notificação o que a atendente digitar no painel.
Não usa QR code nem aparelho conectado, então funciona mesmo com o WhatsApp restrito pra iniciar conversa
(responder continua liberado).

Separado do WA Bridge e do WA Sender (pacote `com.faceclean.wasuporte`), podem ficar instalados juntos.

```
cliente ─▶ WhatsApp do S21 ─▶ notificação ─▶ WA Suporte ─▶ n8n wa-suporte-in ─▶ Postgres (schema suporte)
                                                                                        │
atendente ◀─ painel HTML ◀──────── n8n wa-suporte-painel ◀──────────────────────────────┘
atendente digita ─▶ painel ─▶ fila no Postgres ─▶ app busca em wa-suporte-out ─▶ "Responder" da notificação ─▶ cliente
```

## O que tem

| Pasta | O quê |
|---|---|
| `app/` | App Android (Kotlin). Build automático pelo GitHub Actions → página **Releases** |
| `n8n/suporte.sql` | Schema `suporte` no Postgres do CRM: tabelas, regras e limpeza de 7 dias |
| `n8n/WA_Suporte_FC.json` | Fluxo n8n único (3 webhooks + limpeza de hora em hora) |
| `painel/index.html` | Painel da atendente |

- **Áudio:** acha o `.opus` que o WhatsApp baixou, transcreve na Groq (`whisper-large-v3-turbo`, igual ao WA Bridge) e manda o áudio pro painel pra ouvir.
- **Foto:** manda a foto (reduzida pra 1280px) pro painel.
- **CRM:** do lado do chat aparece a ficha da cliente puxada de `public.clientes` / `public.pedidos` pelo número (pedidos, status, rastreio, itens). Só leitura, não escreve nada no CRM.
- **7 dias:** mensagens, fotos e áudios com mais de 7 dias são apagados de hora em hora.
- **Nunca manda 2x:** cada resposta tem um id; o app guarda os ids já enviados.

## Instalar

### 1. Banco
No Postgres do CRM (banco `crm`), rode o arquivo [`n8n/suporte.sql`](n8n/suporte.sql) inteiro.
Ele só cria o schema `suporte`; não altera nenhuma tabela existente. Depois rode, trocando pelo token do painel:

```sql
insert into suporte.config (chave, valor)
values ('painel_token_sha256', encode(sha256(convert_to('TOKEN_DO_PAINEL', 'UTF8')), 'hex'))
on conflict (chave) do update set valor = excluded.valor;
```

### 2. n8n
1. Credenciais → **Header Auth** chamada `WA Suporte App`: Name `X-WS-Token`, Value = token do app.
2. Importe [`n8n/WA_Suporte_FC.json`](n8n/WA_Suporte_FC.json) → nos webhooks `App envia (in)` e `App busca (out)` escolha a credencial `WA Suporte App` → Postgres já vem com `CRM Postgres`.
3. Ative o fluxo. Execuções com sucesso **não são salvas** (o app e o painel consultam a cada poucos segundos).

### 3. Painel
Suba `painel/index.html` no Easypanel (projeto `sites`, serviço `web`), numa pasta difícil de adivinhar, ex.:
`/usr/share/nginx/html/suporte-2eeb849c/index.html` → `https://sites-web.sdrs1j.easypanel.host/suporte-2eeb849c/`.
Se hospedar em outro domínio, troque o **Allowed Origins** do webhook `Painel (api)`.
No primeiro acesso o painel pede o nome da atendente e o token do painel.

### 4. Celular (S21 FE)
1. Baixe o APK em **Releases** e instale.
2. Abra o app: o endereço dos webhooks já vem preenchido. Cole o **token do app** e o token da **Groq**. Marque **Ativar** → **Salvar** → **Testar conexão** (tem que dar `HTTP 200`).
3. Faça as permissões na ordem dos botões: configurações restritas, notificações, bateria, arquivos, contatos.
4. Samsung: Bateria → Limites de uso → **Apps nunca suspensos** → WA Suporte FC.
5. WhatsApp: notificações **ligadas com prévia**, e Download automático de fotos e áudios ligado (Wi-Fi e dados).
6. Deixe o celular no carregador.

## Limites honestos
- Só vê o que chega **depois** de instalado. Histórico antigo do WhatsApp não aparece.
- O que alguém responder **direto no celular** não aparece no painel.
- A resposta sai pelo botão da notificação. Se a notificação da conversa sumir (alguém abriu a conversa no celular
  ou limpou as notificações), o painel mostra ⚠ e a resposta só sai quando a cliente mandar outra mensagem.
- Se o WhatsApp estiver aberto na tela, ele não gera notificação. Não deixe o WA Sender disparando nesse celular.
- Painel manda só texto (sem foto/áudio de volta).
- Grupos são ignorados.
