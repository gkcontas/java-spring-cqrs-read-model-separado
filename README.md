# CQRS com Modelo de Leitura Separado

Loja de pedidos com dois modelos: a escrita normalizada em PostgreSQL e a leitura desnormalizada em MongoDB, mantidas em sincronia por um fluxo de eventos.

A separação não é por gosto arquitetural. As duas perguntas que a aplicação realmente faz — "mostre este pedido" e "quanto este cliente gastou por categoria" — exigem, no modelo normalizado, três junções e uma agregação sobre todo o histórico. No modelo de leitura, as duas são uma busca por chave.

O que o diagrama de duas caixas não mostra é a parte difícil: a leitura fica atrás da escrita, e esse atraso é visível para quem acabou de clicar em salvar.

## Os dois modelos

```
ESCRITA (PostgreSQL)                        LEITURA (MongoDB)

customers ─< orders ─< order_items          order_views
                           >─ products      customer_dashboards
         │
         └─ domain_event (sequence) ──► projetor ──► checkpoint
```

O `domain_event` é gravado **na mesma transação** da alteração. A coluna `sequence` é um `BIGSERIAL` e sustenta três coisas de uma vez: a ordem em que o projetor consome, a posição onde ele marca progresso, e o número que o cliente pode citar para ler o que acabou de escrever.

## Tecnologias e bibliotecas

| | |
|---|---|
| Linguagem | Java 21 |
| Framework | Spring Boot 3.5 |
| Escrita | Spring Data JPA, PostgreSQL 16, Flyway |
| Leitura | Spring Data MongoDB, MongoDB 7 |
| Validação | Bean Validation |
| Build | Gradle Kotlin DSL (wrapper `gradlew`) |
| Testes | JUnit 5, AssertJ, Awaitility, Testcontainers |
| Apoio | Lombok nas entidades |

## Pré-requisitos

- JDK 21 ou superior
- Docker

## Como rodar

```bash
docker compose up -d
```

```bash
./gradlew bootRun
```

A API fica em `http://localhost:8080`. O MongoDB é publicado em **27018**, não na porta padrão, para não colidir com uma instância já rodando na máquina.

Para ter volume suficiente nas comparações:

```bash
curl -s -X POST "localhost:8080/load?customerCount=5&ordersPerCustomer=300"
```

## Endpoints

| Método | Rota | Lado | Descrição |
|---|---|---|---|
| `POST` | `/customers`, `/products` | escrita | Dados de referência |
| `POST` | `/orders` | escrita | Cria o pedido; devolve o id e a sequência |
| `POST` | `/orders/{id}/pay`, `/cancel` | escrita | Muda o status |
| `GET` | `/orders/{id}` | leitura | Resumo do pedido, do modelo de leitura |
| `GET` | `/orders/{id}?minSequence=N` | leitura | Espera a projeção alcançar `N` antes de responder |
| `GET` | `/customers/{id}/dashboard` | leitura | Painel pré-agregado |
| `GET` | `/projections/status` | — | Defasagem em eventos e em milissegundos |
| `POST` | `/projections/catch-up` | — | Roda o projetor agora |
| `POST` | `/projections/rebuild` | — | Apaga e reconstrói o modelo de leitura |
| `GET` | `/comparison/orders/{id}` | — | A mesma pergunta nos dois modelos, com os tempos |
| `GET` | `/comparison/dashboards/{id}` | — | Idem, para o painel |
| `POST` | `/load` | — | Carga sintética |

A criação de pedido responde **202** e devolve `{orderId, sequence}`. Não devolve o pedido: o que ela criou vive num modelo que ainda não foi atualizado.

## O ganho, medido

Com **1903 pedidos** de um mesmo cliente, três execuções seguidas de cada:

```
painel por categoria    escrita = 3731–5143 us    leitura = 490–532 us    7–10x
resumo do pedido        escrita = 2207–2476 us    leitura = 559–590 us    ~4x
```

O número isolado importa menos que a tendência. Com 300 pedidos o painel levava 2,4 ms pelo modelo de escrita; com 1903, passou de 3,7 ms. O modelo de leitura ficou em torno de 0,5 ms nos dois casos — **o custo da leitura não cresce com o histórico, o da escrita cresce**.

E é por isso que a comparação também diz quando o padrão *não* compensa: com poucos pedidos a diferença é ruído, e pagar por dois modelos para economizar um milissegundo é um mau negócio.

Os dois endpoints de comparação verificam que os dois lados devolvem o mesmo resultado. Um modelo de leitura rápido e errado não é otimização.

## A defasagem, visível

```bash
curl -s localhost:8080/projections/status
```

```json
{"writeSequence": 3103, "readCheckpoint": 3103, "lagEvents": 0, "lagMillis": 0}
```

`lagMillis` é a idade do evento mais antigo ainda não aplicado — a medida honesta, porque uma contagem de eventos pendentes não diz nada sobre quão velho é o dado que o usuário está olhando.

Consistência eventual sem instrumentação é consistência imprevisível: esse número é o que se coloca num alerta.

Na prática, com o projetor a cada 200 ms e lotes de 200 eventos, não consegui gerar carga suficiente por HTTP para a defasagem passar de zero. A janela existe e é demonstrada de forma determinística na suíte de testes, segurando o projetor.

## Ler o que acabou de escrever

A reclamação que o CQRS produz no primeiro dia em produção: o usuário salva e, ao recarregar, não vê. As respostas comuns são insatisfatórias — pedir para atualizar a página, ou ler do modelo de escrita e abrir mão de metade do benefício.

A saída aqui é a terceira. O comando devolve a sequência que gravou:

```json
{"orderId": "44082087-2605-4d15-9c0d-30c1493f318d", "sequence": 2702}
```

E a leitura pode exigi-la:

```bash
curl -s "localhost:8080/orders/44082087-2605-4d15-9c0d-30c1493f318d?minSequence=2702"
```

A consulta espera o projetor passar daquela posição antes de responder. A espera é limitada, normalmente invisível — o projetor costuma estar milissegundos atrás — e mantém a garantia exatamente onde ela é necessária, em vez de degradar todas as leituras do sistema. Quando o tempo limite estoura, a resposta é 503: o chamador decide entre um dado velho e um erro, e ambos são melhores que uma espera sem fim.

## Reconstrução

```bash
curl -s -X POST localhost:8080/projections/rebuild
```

```json
{"eventsApplied": 3104, "durationMillis": 2455}
```

Cerca de 1.260 eventos por segundo, incluindo a escrita no MongoDB. É a capacidade que justifica boa parte do padrão: mudar o formato de um documento, corrigir um bug numa projeção ou acrescentar um campo vira um deploy mais uma reconstrução, em vez de uma migração sobre dados que eram apenas derivados.

O teste de reconstrução compara os totais antes e depois e exige que sejam idênticos — é assim que se sabe que a projeção é determinística.

## Projeção idempotente e ordenada

Três propriedades, todas vindas da sequência e não dos eventos:

- **Ordenada** — os eventos são consumidos por sequência crescente, então uma mudança de status nunca chega antes do pedido a que pertence.
- **Idempotente** — o checkpoint avança depois do lote aplicado, e cada documento guarda a sequência que reflete. Reaplicar um evento é no-op em vez de contagem dobrada.
- **Reconstruível** — zerar o checkpoint reconstrói tudo.

O caso perigoso é o painel, cujos totais são mantidos incrementalmente: sem a verificação de sequência, um replay somaria os mesmos valores de novo e ninguém perceberia até um cliente reclamar. Há um teste exatamente para isso.

E o inverso também: ao reprocessar tudo, o `OrderPlaced` antigo (que diz "PLACED") chega depois de o status já estar "PAID". A comparação de versão impede que o modelo de leitura ande para trás.

## O custo

Nomeado, não escondido:

- **Dois bancos** para operar, monitorar e fazer backup.
- **Dois modelos** e o mapeamento entre eles; cada campo novo na tela é código em dois lugares.
- **Um projetor** que pode travar, atrasar ou ter bug — um componente a mais no caminho do dado.
- **Uma classe de bug nova**: o modelo de leitura divergir do de escrita, sem nada quebrar.
- **Duplicação deliberada**: o nome do cliente aparece em todos os pedidos dele. Se ele mudar o nome, os documentos antigos ficam com o antigo até uma projeção atualizá-los. Decidir se isso é aceitável é uma pergunta de produto — e o CQRS obriga a fazê-la em voz alta.

Vale quando a leitura domina o tráfego, as consultas de leitura são caras no modelo normalizado, e a consistência eventual é aceitável para aquele dado. Não vale porque é moderno.

## Testes

```bash
./gradlew test
```

18 testes, contra PostgreSQL e MongoDB reais em containers. O projetor agendado fica desligado na suíte inteira: cada teste roda `catchUp()` no momento que escolhe, e segurá-lo é a única forma de observar a janela em que a leitura está atrás.

| Classe | Testes | O que cobre |
|---|---|---|
| `WriteModelIntegrationTest` | 5 | Pedido e evento na mesma transação, dados desnormalizados no evento, sequência monotônica |
| `ProjectionIntegrationTest` | 7 | Construção das duas projeções, replay sem dobrar totais, evento fora de ordem, reconstrução |
| `ConsistencyIntegrationTest` | 4 | Leitura atrasada, espera por sequência, tempo limite estourado |
| `ModelAgreementIntegrationTest` | 2 | Os dois modelos respondendo a mesma coisa |

## Relação com os outros projetos

O canal entre os dois modelos aqui é uma tabela de eventos lida por polling, no mesmo processo. Levar esse fluxo para fora do processo com garantia de entrega é o assunto do [outbox transacional](../java-spring-outbox-publicacao-confiavel); os eventos entre módulos de um monólito estão no [monólito modular](../java-spring-modulith-monolito-modular).

Uma diferença que vale marcar: isto **não** é event sourcing. O estado continua sendo o modelo normalizado, e os eventos são apenas o canal para a leitura. Em event sourcing os eventos *são* o estado, e o modelo normalizado deixa de existir.
