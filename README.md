# Aldebaran

> **Aldebaran** é uma gigante laranja que fica a cerca de 65 anos-luz da Terra. Seu nome vem
> do árabe *al-Dabarān*, "o seguidor": ela parece seguir o aglomerado das Plêiades pelo céu.
> Apesar disso, não faz parte do aglomerado das Híades, com o qual costuma ser confundida,
> e é uma das estrelas mais brilhantes da constelação de Touro.

Um Spring Cloud Config Server para estudos: **uma instância**, um repositório Git privado
e os perfis `dev` e `prod` dos microserviços. A API é publicada somente no IP privado da
Tailscale do servidor Linux.

Java 21 · Spring Boot 4.1.2 · Spring Cloud 2025.1.4 (Config 5.0.6) · Maven · Docker Compose.
As versões são gerenciadas pelo parent do Boot e pelo BOM do Cloud, mantendo a verificação de compatibilidade.

## Como funciona

```text
IntelliJ / microserviços Docker
             │
             │ HTTP pela rede privada — /identity/dev ou /identity/prod
             ▼
     Aldebaran :8888
             │
             │ Git por HTTPS, usuário + token
             ▼
  Um repositório Git privado
```

O Config Server expõe configurações através dos controllers oficiais do Spring.
O backend **Git** clona o repositório, consulta a branch e atualiza seu cache.
O backend **Native** leria arquivos locais; este projeto utiliza exclusivamente Git.

O servidor não escolhe um ambiente de implantação para distribuir: atende DEV e PROD
simultaneamente. O cliente informa a aplicação e o perfil no pedido.
Toda a escolha de arquivos, precedência e descriptografia fica com o Spring Cloud Config.

O código de produção tem somente:

```text
src/main/java/br/api/universum/configserver/
├── ConfigServerApplication.java
└── config/SecurityConfiguration.java
src/main/resources/application.yml
examples/config-repo/             # referência para o outro repositório; não vai para a imagem final
src/test/java/                   # integração real com Git, HTTP e Config Client
Dockerfile
compose.yml
docker/healthcheck.sh
.env.example
docs/VERIFICACAO.md
```

Dependências de produção: `spring-cloud-config-server`, `spring-boot-starter-security`
e `spring-boot-starter-actuator`. Nos testes: `spring-boot-starter-test` e
`spring-cloud-starter-config`. Não há banco, armazenamento externo, serviço de segredos
ou implementação própria de repositório/criptografia.

## 1. Prepare o repositório privado de configurações

Crie um repositório Git privado separado do código do Aldebaran e copie os arquivos de
[examples/config-repo](examples/config-repo) para sua raiz:

```text
application.yml
identity-dev.yml
identity-prod.yml
gateway-dev.yml
gateway-prod.yml
finance-service-dev.yml
finance-service-prod.yml
```

Os exemplos usam valores fictícios, URLs `.invalid` e comentários explicando onde inserir
senhas criptografadas. Não contêm segredos reais nem ciphertext utilizável.
O Polaris é o Spring Cloud Gateway e seus exemplos não incluem datasource próprio.
Substitua os parâmetros conforme seu laboratório e faça commit/push na branch do ambiente: `dev` ou `prod`.
Cada serviço pede a branch com o mesmo nome do seu perfil (`spring.cloud.config.label`),
então `/identity/dev/dev` lê a branch `dev` e `/identity/prod/prod` lê a `prod`.

`application.yml` tem propriedades comuns e não sensíveis.
Para `/identity/dev`, o Spring combina o global com `identity-dev.yml`;
o arquivo específico tem maior prioridade. Arquivos `application-dev.yml` ou
`identity.yml` também podem ser usados conforme as regras oficiais.
Perfis múltiplos, labels e formatos YAML/Properties continuam disponíveis.

`GET /identity/default` é permitido: o Config Data faz essa consulta inicial antes de buscar
o perfil ativo. Não é necessário forçar `spring.cloud.config.profile` no cliente.

`accept-empty=false` retorna 404 quando nenhuma fonte é encontrada.
Um nome desconhecido ainda pode receber o global `application.yml`; este é o comportamento
oficial, sem validação personalizada de nomes de aplicações.

### Token do GitHub

Crie preferencialmente um **fine-grained personal access token**, selecionando o proprietário
correto e somente o repositório privado de configurações. Para clonagem/leitura, conceda
**Contents: Read-only**. Verifique se a organização exige aprovação ou autorização adicional.

Configure:

- `CONFIG_GIT_URI`: URL HTTPS, por exemplo `https://github.com/universum-stellarum/SEU_REPO_PRIVADO.git`.
- `CONFIG_GIT_USERNAME`: usuário GitHub associado ao token.
- `CONFIG_GIT_TOKEN`: token usado como senha pelo transporte Git oficial.

Não coloque token na URL. Não desabilite a validação TLS do Git.
O token, a senha administrativa e a chave não pertencem ao repositório de configurações.
Ao expirar/rotacionar o token, atualize o ambiente do Aldebaran e recrie o container.

## 2. Configure as variáveis do Aldebaran

Copie `.env.example` para `.env` local ou preencha Environment no Dokploy.

| Variável | Uso |
| --- | --- |
| `CONFIG_SERVER_BIND_IP` | IPv4 da Tailscale realmente atribuído ao host Linux; obrigatório no Compose |
| `CONFIG_GIT_URI` | Repositório Git privado, por HTTPS |
| `CONFIG_GIT_USERNAME` | Usuário do Git |
| `CONFIG_GIT_TOKEN` | Token para leitura do Git |
| `ENCRYPT_KEY` | Chave simétrica persistente para os valores `{cipher}` |
| `CONFIG_ADMIN_USERNAME` | Administrador dos endpoints de criptografia |
| `CONFIG_ADMIN_PASSWORD` | Senha administrativa |
| `SERVER_PORT` | Porta interna e publicada; padrão 8888 |
| `CONFIG_GIT_DEFAULT_LABEL` | Branch usada quando o cliente não informa label; use `dev`. Sem fallback para `master` |

`ENCRYPT_KEY` é reconhecida diretamente pelo binding oficial como `encrypt.key`.
Não é preciso duplicar a chave em YAML ou criar uma classe Java.
Gere uma chave forte, por exemplo com `openssl rand -hex 32`, e guarde-a no Environment do Dokploy
e em backup seguro separado. A perda dessa chave impede recuperar os valores criptografados.

O `.env` contém **somente o bootstrap do Aldebaran**. Segredos dos microserviços ficam
criptografados no repositório Git. Nunca versione o `.env`.
Valores com caracteres especiais devem usar aspas simples no formato dotenv.
O Compose usa `environment:` para encaminhar explicitamente cada variável; um arquivo
`.env` ao lado do Compose, sozinho, não injeta tudo no processo.

## 3. Implante uma instância no Dokploy

O host deve ter Docker e Tailscale em funcionamento, com a interface de rede do host.
Este Compose pressupõe Tailscale no Linux, não um endereço disponível somente dentro
de um container/sidecar ou em modo userspace.

Antes do deploy, no host:

```bash
tailscale status
tailscale ip -4
ip -4 addr show dev tailscale0
```

Use em `CONFIG_SERVER_BIND_IP` o endereço presente nessa interface.
`100.64.10.1` é apenas ilustrativo: não está fixado na aplicação.
Não use `0.0.0.0`, um IP público ou o endereço Tailscale do seu computador.

No Dokploy:

1. Crie **um** recurso Docker Compose para o repositório `universum-stellarum/Aldebaran`.
2. Selecione `compose.yml` no modo Docker Compose, não Docker Stack/Swarm.
3. Preencha Environment com as variáveis acima.
4. Faça deploy com build. Não configure domínio público, proxy público ou Cloudflare Tunnel.

Para reproduzir no host Linux:

```bash
docker compose config --quiet
docker compose up -d --build
docker compose ps
```

Para construir apenas a imagem: `docker build -t config-server:local .`.

O mapeamento de porta usa `host_ip: ${CONFIG_SERVER_BIND_IP}`, sem fallback para todas as interfaces.
Se o IP não existir no host, corrija a interface antes de continuar; não troque por `0.0.0.0`.
Mantenha o serviço Tailscale ativo antes de iniciar o container, inclusive após reiniciar o host.

A imagem final usa JRE 21 e UID/GID 10001. O Compose mantém restart policy, health check,
filesystem somente leitura, capabilities removidas e ausência de privilégios.
O clone/cache Git fica em `/tmp/config-server-git`, dentro do tmpfs gravável.
O cache é descartável; recriar o container provoca um novo clone.
Os exemplos são usados somente no estágio de build/testes, não copiados para a imagem final.
Não é necessário compartilhar a rede Docker dos microserviços: eles usarão o IP privado do host.

## 4. Teste a rede privada

No host, confirme a publicação específica sem imprimir as variáveis secretas:

```bash
docker inspect "$(docker compose ps -q config-server)" --format '{{json .HostConfig.PortBindings}}'
curl --fail http://SEU_IP_TAILSCALE:8888/actuator/health
curl --fail http://SEU_IP_TAILSCALE:8888/identity/dev
```

Repita o curl no computador conectado à Tailscale antes de iniciar o cliente no IntelliJ.
No PowerShell, use `curl.exe`.

Teste também **a partir da rede do container cliente**, pois Docker → IP Tailscale do host
depende de roteamento e regras de firewall/NAT:

```bash
docker run --rm --network NOME_DA_REDE_DO_CLIENTE curlimages/curl:latest \
  --fail --connect-timeout 5 http://SEU_IP_TAILSCALE:8888/actuator/health
```

Se não conectar, confira `tailscale status`, `ip route`, regras de acesso da tailnet,
regras do firewall e o encaminhamento/NAT do Docker. Não desabilite indiscriminadamente o firewall.
Verifique tanto host→serviço quanto container→host; sucesso no host não garante sucesso no container.

Restrinja a porta 8888 às origens necessárias nas regras de rede e da tailnet.
A publicação por IP não substitui a análise das regras do Docker/firewall nem impede acesso
de outros containers/usuários confiáveis do host. Não conecte o serviço a um proxy público.

## 5. Criptografia nativa

O Aldebaran usa a criptografia e a descriptografia do Spring Cloud Config, habilitadas por padrão.
O servidor lê `{cipher}...` do Git e entrega o valor descriptografado ao cliente.

Endpoints oficiais administrativos:

| Endpoint | Operação |
| --- | --- |
| `POST /encrypt` | Recebe texto e devolve ciphertext, sem o prefixo `{cipher}` |
| `POST /decrypt` | Recebe ciphertext e devolve texto |
| `POST /encrypt/{application}/{profiles}` e `/decrypt/{application}/{profiles}` | Variantes oficiais; usam a mesma chave simétrica |
| `GET /encrypt/status` e `/key` | Informações da criptografia; também exigem administrador |

Para um exemplo fictício no Bash, defina o endereço e o usuário administrativo no seu terminal:

```bash
CONFIG_SERVER_URL=http://SEU_IP_TAILSCALE:8888
CONFIG_ADMIN_USERNAME=SEU_USUARIO_ADMIN

printf '%s' 'valor-ficticio-para-estudo' | \
  curl --fail --silent --show-error --user "$CONFIG_ADMIN_USERNAME" \
  --header 'Content-Type: text/plain' --data-binary @- \
  "$CONFIG_SERVER_URL/encrypt" > cipher.txt

curl --fail --silent --show-error --user "$CONFIG_ADMIN_USERNAME" \
  --header 'Content-Type: text/plain' --data-binary @cipher.txt \
  "$CONFIG_SERVER_URL/decrypt"
```

O curl solicita a senha administrativa. O resultado do segundo comando deve ser
`valor-ficticio-para-estudo`. Não coloque senhas reais em argumentos, histórico, logs ou tickets.

Copie **somente o ciphertext** de `cipher.txt` para o YAML remoto, com prefixo e aspas:

```yaml
spring:
  datasource:
    password: '{cipher}COLE_AQUI_O_CIPHERTEXT_REAL'
```

Faça commit/push no repositório privado e consulte `/identity/dev`.
A resposta contém a senha em texto claro; confira em terminal privado.
O cliente não precisa de `ENCRYPT_KEY`.

Sem credenciais administrativas, `/encrypt` e `/decrypt` retornam 401.
Não há cadastro de usuário por aplicação. Todos os endpoints de criptografia,
inclusive variantes e `/key/**`, exigem o administrador.

Se um valor não puder ser descriptografado, o comportamento oficial pode remover a propriedade
e devolvê-la como `invalid.<nome>`. Isso não implica necessariamente erro HTTP ou falha do cliente.
Os microserviços devem validar suas próprias propriedades obrigatórias; não há validador personalizado no Aldebaran.

## 6. Configure os clientes com três variáveis

No microserviço, adicione `org.springframework.cloud:spring-cloud-starter-config`
e o BOM Cloud compatível com seu Spring Boot. Não adicione a dependência de **servidor** ao cliente.

No IntelliJ, preencha as variáveis da Run Configuration:

```dotenv
SPRING_APPLICATION_NAME=identity
SPRING_PROFILES_ACTIVE=dev
SPRING_CONFIG_IMPORT=configserver:http://SEU_IP_TAILSCALE:8888
```

Não é necessário `bootstrap.yml`, senha administrativa, token Git ou chave de criptografia.
O perfil `default` será consultado normalmente antes do perfil ativo.
O import obrigatório, **sem `optional:`**, faz a inicialização falhar se o servidor estiver indisponível.

Em Docker, encaminhe explicitamente as mesmas três variáveis ao processo do microserviço:

```yaml
environment:
  SPRING_APPLICATION_NAME: identity
  SPRING_PROFILES_ACTIVE: dev
  SPRING_CONFIG_IMPORT: configserver:http://SEU_IP_TAILSCALE:8888
```

Use o mesmo endereço na execução local pelo IntelliJ e nos containers.
`localhost`/127.0.0.1 dentro de um container aponta para o próprio container.
Para PROD, altere somente `SPRING_PROFILES_ACTIVE=prod`.
Nomes e aliases Docker não definem o perfil Spring.

Exemplos de consultas sem credenciais:

```bash
curl --fail http://SEU_IP_TAILSCALE:8888/identity/dev
curl --fail http://SEU_IP_TAILSCALE:8888/identity/prod
curl --fail http://SEU_IP_TAILSCALE:8888/gateway/dev
```

## Atualizações e limites

O servidor clona o Git na inicialização (`clone-on-start=true`) e usa os mecanismos
oficiais de fetch nas consultas; o refresh rate padrão é zero.
Após commit/push, uma nova consulta pode obter a alteração sem reiniciar o Aldebaran.
Clientes já iniciados mantêm seus valores: reinicie o microserviço para recarregá-los.
Não há Cloud Bus nem mecanismo próprio de atualização.

Mudanças de token, senha administrativa ou chave exigem recriar o container com o novo ambiente.
Rotacionar `ENCRYPT_KEY` também exige recriptografar os valores; preservar apenas o ciphertext
sem a chave não é backup suficiente. Guarde Git, chave e bootstrap do Dokploy com acesso restrito.

**Leitura de configurações não exige autenticação HTTP. Qualquer cliente que consiga acessar
a API privada pode consultar configurações e segredos de outras aplicações e de DEV e PROD.**
Essa escolha depende da confiança nos dispositivos/usuários da VPN e nas regras do host.
A senha administrativa protege as operações de criptografia, não isola os dados entre clientes.

HTTP simples é uma decisão para este laboratório, no trajeto privado protegido pela Tailscale.
O trecho local Docker→host depende da segurança do próprio host/rede Docker.
Para outras redes, usuários ou requisitos, use HTTPS e autenticação/autorização apropriadas.

Se o servidor estiver indisponível, novos clientes com import obrigatório não iniciam;
clientes já iniciados podem continuar com a configuração que carregaram.
Não há alta disponibilidade. O cache Git é descartável e não substitui backup nem
garante funcionamento após reinício sem acesso ao Git.
O health check usa o endpoint oficial e não garante a validade de todas as propriedades dos clientes.

## Testes

```bash
./mvnw clean verify
docker build -t config-server:local .
```

No Windows, use `.\mvnw.cmd clean verify`.

Os testes criam um repositório Git local com JGit e o expõem somente em TCP loopback.
O Config Server usa seu backend Git real para clone e fetch.
São testados ambos os perfis, propriedades compartilhadas, precedência, formatos oficiais,
`{cipher}`, endpoints administrativos e clientes Spring reais em JVMs separadas.
Os clientes recebem apenas as três variáveis de bootstrap.
Não é necessário GitHub, token verdadeiro ou Tailscale para a suíte.

Resultados realmente executados e limitações do ambiente: [docs/VERIFICACAO.md](docs/VERIFICACAO.md).

## Referências oficiais

- [Spring Cloud 2025.1.4 e compatibilidade com Boot 4.1.2](https://spring.io/blog/2026/10/23/spring-cloud-2025-1-4-has-been-released/)
- [Backend Git](https://docs.spring.io/spring-cloud-config/reference/server/environment-repository/git-backend.html)
- [Criptografia e descriptografia](https://docs.spring.io/spring-cloud-config/reference/server/encryption-and-decryption.html)
- [Gerenciamento da chave](https://docs.spring.io/spring-cloud-config/reference/server/key-management.html)
- [Spring Cloud Config Client](https://docs.spring.io/spring-cloud-config/reference/client.html)
- [Tokens de acesso do GitHub](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens)
- [Publicação de portas Docker](https://docs.docker.com/get-started/docker-concepts/running-containers/publishing-ports/)
