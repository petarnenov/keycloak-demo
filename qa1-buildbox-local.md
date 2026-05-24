# QA1 build 1:1 локално (buildbox контейнер)

Документ за репродуциране на qa1/qa2/qa3 build-а на GeoWealth локално с подменения buildbox image. Целта е **същият toolchain, същите скриптове, същата последователност** като при `deploy_env.groovy` → `Deploy Cloud` стейджа в Jenkins.

## Какво всъщност симулираме

На production qa1 деплой се случва следното (от `gwm1/jenkins/jobs/deploy/deploy_env.groovy`, stages `Deploy Cloud` + `gitSyncSnippet`):

1. Jenkins агент в `oraclelinux:10` k8s pod
2. SSH към `qa1.geowealth.com` като `geowealth`
3. `cd ~/geo`
4. `git fetch --all --tags --prune` + reset/checkout на branch-а
5. Подмяна на `struts.xml` и `buildReactAppDocker.sh` с QA вариантите
6. `./gradlew -Ptarget=qa1 --no-daemon --console=plain fullpush`
7. След успех — `readRemoteBuildInfo` дърпа `/geowealth/adviser/current/build/buildinfo.txt` от хоста

`fullpush` вътре прави build → docker/podman build → push към OCIR (`iad.ocir.io/geowealthocip/*`) → scp/ssh към `qa1app`/`qa1web` → remote restart.

В OKE qa-X-deployement-helm-чартовете (`gwm1/deployment/qa-{1,2,3}-deployement-helm`) има отделен `buildbox` Job, който прави почти същото от рамките на OKE кластъра, с PVC кешове и SSH wrappers. Той е референтът, който заимстваме за локалния setup.

Референтни файлове:
- Build pipeline: `gwm1/jenkins/jobs/deploy/deploy_env.groovy:1023-1073` (Deploy Cloud stage)
- SSH helper: `gwm1/jenkins/vars/sshRunLogged.groovy:30-46`
- Git sync: `gwm1/jenkins/jobs/deploy/deploy_env.groovy:61-85` (`gitSyncSnippet`)
- Buildbox image: `gwm1/deployment/oci_oke_deployments/Dockerfile.buildbox`
- Buildbox Job script: `gwm1/deployment/qa-3-deployement-helm/templates/job.yaml`
- Buildbox env vars: `gwm1/deployment/qa-3-deployement-helm/values.yaml`

## Какво ти трябва преди да започнеш

| Изискване | Минимум | Препоръчително |
|---|---|---|
| RAM | 16 GB (build-ът заявява 12-16 Gi) | 32 GB+ |
| CPU | 6+ ядра (заявка 6, лимит 8) | 8+ ядра |
| Disk | 40 GB свободни (само `localbuild`) | 80 GB+ свободни |
| OS | macOS / Linux с podman или Docker | — |
| Достъп | SSH ключ към `gitlab.com:gwm1/geowealth` | — |
| Достъп (за deploy) | SSH ключ `jenkins-private-key` + OCIR push креденшъли (опционално, виж по-долу) | — |

### Подробна разбивка на disk consumption

| Компонент | Размер | Бележки |
|---|---|---|
| `geowealth-buildbox:local` image | ~2 GB | Oracle Linux 9 + Corretto 17 + podman + dev tools |
| Git mirror (bare) | 3–6 GB | Цялата история на geowealth монорепото |
| Workspace clone (shared с mirror) | 1–2 GB | `--shared` hard-link-ва objects от mirror-а — много по-малко от full clone |
| Gradle cache (`~/.gradle`) | 8–15 GB | Расте във времето; първи build добавя ~2-3 GB |
| Node `node_modules` + Vite cache | 1–3 GB | Per React app |
| Build артефакти (`build/`) | 2–5 GB | WAR, jars, image layers |
| Podman image cache (при `fullpush`) | 5–20 GB | Layers на app image-ите, multi-stage build остатъци |

### Сценарии на достатъчност на disk

- **`localbuild` only (препоръчителното за локално)** → 20–30 GB peak. **40 GB е спокойно.**
- **`fullpush` (с docker image build стъпка)** → 30–45 GB peak. **40 GB е на ръба** — може да опре до `no space left` при втори build преди cleanup.

### Cleanup команди (за машини с тесен диск)

```bash
# Преди всеки build
podman image prune -f
podman builder prune -f       # ако ползваш fullpush

# Седмично housekeeping
rm -rf ~/geowealth/cache/gradle/caches/transforms-*
rm -rf ~/geowealth/code/build           # старите артефакти
df -h ~/geowealth                       # провери преди следващ build
```

### Червени флагове на тесен диск (≤ 40 GB свободни)

- Не дръж паралелно няколко workspace-а (`~/geowealth/code` + клонинги за други бранчове). Gradle cache-ът е shared, но всеки `build/` директория е отделна.
- **macOS APFS local snapshots и Time Machine** ядат "свободно" място невидимо. Бърз cleanup:
  ```bash
  tmutil thinlocalsnapshots / 999999999999 1
  ```
- **Podman machine VM на macOS има свой собствен disk.** 40 GB на хоста ≠ 40 GB вътре в machine-а. Build-ът се случва вътре в machine-а — ако VM-ът е с 30 GB, ще се чупи там, не на хоста. Виж `--disk-size` по-долу.

### macOS — иницииализирай podman machine веднъж

```bash
# За удобни 40 GB host setup-и (само localbuild):
podman machine init --cpus 6 --memory 16384 --disk-size 60

# За пълен fullpush с резерв:
podman machine init --cpus 6 --memory 16384 --disk-size 80

podman machine start
export DOCKER_HOST="unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')"
```

> **Внимание:** `--disk-size` не може да се увеличи на стартирана machine без `podman machine rm` + re-init (унищожава всички локални image-и и volumes). Избери размер с резерв на старта.

## Структура на работна директория

Mirror layout-а от buildbox Job-а (`/build`, `/cache/git`, `/cache/gradle`, `/deploy-secret`, `/ssh-gitlab`):

```
~/geowealth/
├── code/                          # workspace (mount → /build/current)
├── cache/
│   ├── git/geowealth.git/        # bare mirror (mount → /cache/git/geowealth)
│   └── gradle/                    # Gradle user home (mount → /build/gradle-cache)
├── secrets/
│   ├── gitlab-ssh/
│   │   ├── ssh-privatekey         # private key за clone (chmod 600)
│   │   └── known_hosts            # gitlab.com host key
│   └── deploy-ssh/
│       ├── deploy_key             # private key за qa1app/qa1web (chmod 600)
│       └── known_hosts            # host keys на qa1app/qa1web
└── bin/
    └── run-buildbox.sh            # wrapper за podman run
```

Създай скелета:

```bash
mkdir -p ~/geowealth/{code,cache/git,cache/gradle,secrets/gitlab-ssh,secrets/deploy-ssh,bin}
chmod 700 ~/geowealth/secrets/gitlab-ssh ~/geowealth/secrets/deploy-ssh
```

## Стъпка 1: Build на buildbox image-а

Imageто `iad.ocir.io/geowealthocip/buildbox:latest` идва от `gwm1/deployment/oci_oke_deployments/Dockerfile.buildbox`. Билдни го локално:

```bash
git clone git@gitlab.com:gwm1/deployment/oci_oke_deployments.git /tmp/oke
cd /tmp/oke
podman build -f Dockerfile.buildbox -t geowealth-buildbox:local .
```

Съдържание на `Dockerfile.buildbox` (за справка, не го редактирай):

```dockerfile
FROM oraclelinux:9

SHELL ["/bin/bash", "-lc"]

RUN rpm --import https://yum.corretto.aws/corretto.key && \
    curl -fsSL -o /etc/yum.repos.d/corretto.repo https://yum.corretto.aws/corretto.repo && \
    dnf -y install \
      java-17-amazon-corretto-devel \
      git rsync git-lfs openssh-clients ca-certificates \
      openssl-devel wget curl gzip bzip2 unzip tar findutils podman podman-docker which \
      dos2unix hostname procps-ng gettext \
      && dnf -y clean all

RUN groupadd -g 500 geowealth && \
    useradd -u 10001 -g geowealth -m -s /bin/bash geowealth && \
    mkdir -p /home/geowealth/code /home/geowealth/bin/Sencha/Cmd /cache/git /cache/gradle /cache/sencha /build && \
    chown -R geowealth:geowealth /home/geowealth /cache /build

ENV HOME=/home/geowealth \
    JAVA17_HOME=/usr/lib/jvm/java-17-amazon-corretto \
    JAVA8_HOME=/usr/lib/jvm/jre-1.8.0-openjdk \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

RUN alternatives --set java ${JAVA17_HOME}/bin/java || true

USER geowealth
WORKDIR /home/geowealth

CMD ["/bin/bash"]
```

## Стъпка 2: SSH ключове (gitlab + deploy)

### GitLab SSH ключ (за clone)

Ако нямаш — генерирай и добави към GitLab profile:

```bash
ssh-keygen -t ed25519 -f ~/geowealth/secrets/gitlab-ssh/ssh-privatekey -N ""
ssh-keyscan gitlab.com > ~/geowealth/secrets/gitlab-ssh/known_hosts
chmod 600 ~/geowealth/secrets/gitlab-ssh/ssh-privatekey
cat ~/geowealth/secrets/gitlab-ssh/ssh-privatekey.pub
# → добави го като SSH key в gitlab.com → User Settings → SSH Keys
```

### Deploy SSH ключ (за qa1app/qa1web)

Този трябва да е `jenkins-private-key` секретът от Jenkins. Дръпни го от Jenkins credentials store (нужни са admin права) или поискай от DevOps:

```bash
# Поставяш ключа ръчно тук:
cp /path/to/jenkins-private-key ~/geowealth/secrets/deploy-ssh/deploy_key
chmod 600 ~/geowealth/secrets/deploy-ssh/deploy_key

# Прибави host keys на qa1 хостовете (само ако ще правиш истински deploy локално)
ssh-keyscan qa1app qa1web > ~/geowealth/secrets/deploy-ssh/known_hosts 2>/dev/null || true
```

Ако правиш само build (без deploy към реалните хостове) — постави празен deploy_key file, защото buildbox script-ът exit-ва с code 10 ако го няма:

```bash
touch ~/geowealth/secrets/deploy-ssh/deploy_key
chmod 600 ~/geowealth/secrets/deploy-ssh/deploy_key
touch ~/geowealth/secrets/deploy-ssh/known_hosts
```

Или ползвай `localbuild` варианта (виж Стъпка 6.B), който не изисква deploy_key.

## Стъпка 3: Mirror clone (синхронизация с upstream)

Buildbox Job-ът чете от `/cache/git/geowealth`. Това е bare mirror, който се update-ва периодично от CronJob в OKE (`gwm1/deployment/oci_oke_deployments/k8s/cronjobs/git-mirror-cronjob.yaml`). Локално трябва да го поддържаш ръчно.

### `scripts/sync-mirror.sh`

```bash
#!/usr/bin/env bash
# Поддържа bare mirror на gwm1/geowealth.
# Стартирай при първоначална настройка и преди всеки нов build, ако искаш да си на върха.
set -euo pipefail

MIRROR_DIR="${MIRROR_DIR:-$HOME/geowealth/cache/git/geowealth.git}"
REMOTE_URL="${REMOTE_URL:-git@gitlab.com:gwm1/geowealth.git}"

export GIT_SSH_COMMAND="ssh -i $HOME/geowealth/secrets/gitlab-ssh/ssh-privatekey \
  -o IdentitiesOnly=yes -o UserKnownHostsFile=$HOME/geowealth/secrets/gitlab-ssh/known_hosts \
  -o StrictHostKeyChecking=yes"

if [ ! -d "${MIRROR_DIR}/objects" ]; then
  echo "[sync-mirror] първоначален bare clone (отнема няколко минути)..."
  git clone --mirror "${REMOTE_URL}" "${MIRROR_DIR}"
  exit 0
fi

echo "[sync-mirror] обновяване на mirror..."
git -C "${MIRROR_DIR}" fetch --all --tags --prune --prune-tags
echo "[sync-mirror] готово ($(git -C "${MIRROR_DIR}" rev-list --all --count) commits)"
```

Запиши, направи executable, изпълни:

```bash
mkdir -p ~/geowealth/scripts
# (постави горния файл там)
chmod +x ~/geowealth/scripts/sync-mirror.sh
~/geowealth/scripts/sync-mirror.sh
```

## Стъпка 4: SSH wrappers (вътре в контейнера)

Buildbox Job-ът създава `bin/ssh` и `bin/scp` обвивки, които сочат на `/deploy-secret/deploy_key` и налагат правилните опции. Това гарантира, че Gradle deploy task-овете използват правилния ключ без значение какви опции подават.

### `scripts/setup-ssh-wrappers.sh`

Този скрипт се изпълнява **вътре в контейнера** при стартиране (или включи го в entrypoint). Източник: `gwm1/deployment/qa-3-deployement-helm/templates/job.yaml`.

```bash
#!/usr/bin/env bash
set -euo pipefail

BASE_DIR="/build"
BIN_DIR="$BASE_DIR/bin"
DEPLOY_KNOWN_HOSTS="/tmp/deploy_known_hosts"

mkdir -p "$BIN_DIR"

# 1. Подготви known_hosts от seed-а
if [ -s /deploy-secret/known_hosts ]; then
  cp /deploy-secret/known_hosts "$DEPLOY_KNOWN_HOSTS"
else
  : > "$DEPLOY_KNOWN_HOSTS"
fi

# 2. Auto-scan на хостове, които липсват
if command -v ssh-keyscan >/dev/null 2>&1; then
  for h in ${DEPLOY_APP_HOSTS:-} ${DEPLOY_WEB_HOSTS:-}; do
    if [ -n "${h}" ] && ! ssh-keygen -F "$h" -f "$DEPLOY_KNOWN_HOSTS" >/dev/null 2>&1; then
      ssh-keyscan -H "$h" >> "$DEPLOY_KNOWN_HOSTS" 2>/dev/null || true
    fi
  done
fi

# 3. Решаваме строго проверяване ако имаме поне един mapped host
KNOWN_HOSTS_OPTS="-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null"
if [ -s "$DEPLOY_KNOWN_HOSTS" ]; then
  for h in ${DEPLOY_WEB_HOSTS:-} ${DEPLOY_APP_HOSTS:-}; do
    if [ -n "${h}" ] && ssh-keygen -F "$h" -f "$DEPLOY_KNOWN_HOSTS" >/dev/null 2>&1; then
      KNOWN_HOSTS_OPTS="-o StrictHostKeyChecking=yes -o UserKnownHostsFile=$DEPLOY_KNOWN_HOSTS"
      break
    fi
  done
fi
export SSH_KNOWN_HOSTS_OPTS="$KNOWN_HOSTS_OPTS"

SCP_VERBOSE="${SCP_VERBOSE:-}"
SSH_VERBOSE="${SSH_VERBOSE:-}"
if [ "${SCP_DEBUG:-false}" = "true" ]; then SCP_VERBOSE="-vvv"; fi
if [ "${SSH_DEBUG:-false}" = "true" ]; then SSH_VERBOSE="-vvv"; fi

# 4. ssh wrapper
printf '%s\n' '#!/bin/sh' \
  'exec /usr/bin/ssh -i /deploy-secret/deploy_key -o IdentitiesOnly=yes $SSH_KNOWN_HOSTS_OPTS '"$SSH_VERBOSE"' "$@"' \
  > "$BIN_DIR/ssh"
chmod +x "$BIN_DIR/ssh"

# 5. scp wrapper
printf '%s\n' '#!/bin/sh' \
  'exec /usr/bin/scp -i /deploy-secret/deploy_key -o IdentitiesOnly=yes $SSH_KNOWN_HOSTS_OPTS '"$SCP_VERBOSE"' "$@"' \
  > "$BIN_DIR/scp"
chmod +x "$BIN_DIR/scp"

export PATH="$BIN_DIR:$PATH"
echo "[setup-ssh-wrappers] $BIN_DIR/ssh и $BIN_DIR/scp готови (PATH първенство)"
```

## Стъпка 5: Подготовка на workspace (git sync + file swaps)

Това възпроизвежда `gitSyncSnippet` (deploy_env.groovy:61-85) + struts/react swaps (deploy_env.groovy:1042-1045).

### `scripts/prepare-workdir.sh`

```bash
#!/usr/bin/env bash
# Изпълнява се ВЪТРЕ в контейнера. Подготвя /build/current от mirror-а с дадения branch.
set -euo pipefail

WORK_DIR="${WORK_DIR:-/build/current}"
MIRROR_REPO="${MIRROR_REPO:-/cache/git/geowealth}"
TARGET_REF_RAW="${TARGET_REF:-master}"

# Sanitize ref (както в buildbox Job-а)
TARGET_REF="$(printf '%s' "$TARGET_REF_RAW" | tr -d '*`' | sed 's/^ *//; s/ *$//')"
if [ -z "$TARGET_REF" ]; then
  echo "[prepare-workdir] TARGET_REF е празен (raw='$TARGET_REF_RAW')" >&2
  exit 5
fi

# Mirror sanity
if [ ! -d "$MIRROR_REPO/objects" ]; then
  echo "[prepare-workdir] mirror липсва на $MIRROR_REPO — стартирай sync-mirror.sh първо" >&2
  exit 4
fi

# Resolve SHA срещу mirror-а
if ! git -C "$MIRROR_REPO" show-ref --verify --quiet "refs/heads/${TARGET_REF}" \
  && ! git -C "$MIRROR_REPO" show-ref --verify --quiet "refs/remotes/origin/${TARGET_REF}"; then
  echo "[prepare-workdir] ref '${TARGET_REF}' не съществува в mirror-а" >&2
  exit 5
fi

SHA=$(git -C "$MIRROR_REPO" rev-parse --verify "${TARGET_REF}" 2>/dev/null \
  || git -C "$MIRROR_REPO" rev-parse --verify "refs/remotes/origin/${TARGET_REF}")

echo "[prepare-workdir] checkout на ${TARGET_REF} (@${SHA:0:10}) в ${WORK_DIR}"

# Изчисти и наново clone (shared, hard-link обектите от mirror-а)
mkdir -p "$WORK_DIR"
rm -rf "$WORK_DIR"/* "$WORK_DIR"/.[!.]* "$WORK_DIR"/..?* 2>/dev/null || true
git clone --no-checkout --shared "$MIRROR_REPO" "$WORK_DIR"

cd "$WORK_DIR"
git config --global --add safe.directory "$WORK_DIR"
git checkout -B "${TARGET_REF}" "$SHA"

# === QA-environment file swaps (от deploy_env.groovy Deploy Cloud stage) ===
echo "[prepare-workdir] подмяна на struts.xml с testenv варианта"
if [ -f src/main/resources/struts-testenv.xml ]; then
  rm -f src/main/resources/struts.xml
  mv -f src/main/resources/struts-testenv.xml src/main/resources/struts.xml
else
  echo "[prepare-workdir] WARN: src/main/resources/struts-testenv.xml липсва — пропускам swap"
fi

echo "[prepare-workdir] подмяна на buildReactAppDocker.sh с QA варианта"
if [ -f WebContent/react/app/buildReactAppQA.sh ]; then
  rm -f WebContent/react/app/buildReactAppDocker.sh
  mv -f WebContent/react/app/buildReactAppQA.sh WebContent/react/app/buildReactAppDocker.sh
else
  echo "[prepare-workdir] WARN: WebContent/react/app/buildReactAppQA.sh липсва — пропускам swap"
fi

echo "[prepare-workdir] workspace готов на ${WORK_DIR}"
```

## Стъпка 6: Старт на контейнера

### `bin/run-buildbox.sh` (на хоста)

Това е podman wrapper-ът. Mount-ва същите volumes като OKE chart-а (`gwm1/deployment/qa-3-deployement-helm/templates/job.yaml` `volumes` секцията).

```bash
#!/usr/bin/env bash
# Стартира локалния buildbox container и (опционално) изпълнява build.
set -euo pipefail

ROOT="${ROOT:-$HOME/geowealth}"
IMAGE="${IMAGE:-geowealth-buildbox:local}"
TARGET_REF="${TARGET_REF:-master}"
BUILD_TARGET="${BUILD_TARGET:-qa1}"
BUILD_MODE="${BUILD_MODE:-localbuild}"     # localbuild | fullpush | flyway | shell
DEPLOY_APP_HOSTS="${DEPLOY_APP_HOSTS:-}"   # напр. qa1app — само за fullpush
DEPLOY_WEB_HOSTS="${DEPLOY_WEB_HOSTS:-}"   # напр. qa1web — само за fullpush
SLACK_WEBHOOK_URL="${SLACK_WEBHOOK_URL:-}" # опционално
EXTRA_ENV=()

# Sanity
for d in "$ROOT/cache/git/geowealth.git" "$ROOT/cache/gradle" \
         "$ROOT/secrets/gitlab-ssh" "$ROOT/secrets/deploy-ssh" "$ROOT/scripts"; do
  [ -e "$d" ] || { echo "[run-buildbox] липсва: $d" >&2; exit 2; }
done

# Sock на podman за nested docker build (както DOCKER_HOST в values.yaml)
PODMAN_SOCK=""
if [ -S "/run/podman/podman.sock" ]; then
  PODMAN_SOCK="/run/podman/podman.sock"
elif [ -S "$XDG_RUNTIME_DIR/podman/podman.sock" ]; then
  PODMAN_SOCK="$XDG_RUNTIME_DIR/podman/podman.sock"
fi

EXEC_CMD="bash"
if [ "$BUILD_MODE" != "shell" ]; then
  EXEC_CMD="bash -lc '\
    export PATH=/build/bin:\$PATH && \
    /scripts/setup-ssh-wrappers.sh && \
    /scripts/prepare-workdir.sh && \
    /scripts/run-gradle.sh'"
fi

PODMAN_ARGS=(
  run --rm -it
  --userns=keep-id
  --security-opt label=disable
  --privileged                               # podman-in-podman за docker builds
  -e "TARGET_REF=${TARGET_REF}"
  -e "BUILD_MODE=${BUILD_MODE}"
  -e "BUILD_TARGET=${BUILD_TARGET}"
  -e "DEPLOY_APP_HOSTS=${DEPLOY_APP_HOSTS}"
  -e "DEPLOY_WEB_HOSTS=${DEPLOY_WEB_HOSTS}"
  -e "DEPLOY_USER_APP=geowealth"
  -e "DEPLOY_USER_WEB=tomcat"
  -e "REMOTE_PATH=/geowealth/adviser/current/"
  -e "GRADLE_USER_HOME=/build/gradle-cache"
  -e "GRADLE_OPTS=-Xmx6g -XX:MaxMetaspaceSize=1g -XX:+UseParallelGC -Dfile.encoding=UTF-8"
  -e "JAVA_TOOL_OPTIONS=-XX:+UseParallelGC -Dfile.encoding=UTF-8"
  -e "NODE_OPTIONS=--max_old_space_size=4096"
  -e "LANG=en_US.UTF-8"
  -e "ORG_GRADLE_PROJECT_org_gradle_daemon=true"
  -e "ORG_GRADLE_PROJECT_org_gradle_parallel=true"
  -e "ORG_GRADLE_PROJECT_org_gradle_workers_max=8"
  -e "ORG_GRADLE_PROJECT_org_gradle_configureondemand=true"
  -e "ORG_GRADLE_PROJECT_org_gradle_caching=true"
  -e "ORG_GRADLE_PROJECT_org_gradle_configuration_cache=true"
  -e "ORG_GRADLE_PROJECT_org_gradle_configuration_cache_problems=warn"
  -e "SLACK_WEBHOOK_URL=${SLACK_WEBHOOK_URL}"
  -e "HOME=/build"
  -v "$ROOT/cache/git/geowealth.git:/cache/git/geowealth:ro,Z"
  -v "$ROOT/cache/gradle:/build/gradle-cache:Z"
  -v "$ROOT/secrets/deploy-ssh:/deploy-secret:ro,Z"
  -v "$ROOT/secrets/gitlab-ssh:/ssh-gitlab:ro,Z"
  -v "$ROOT/scripts:/scripts:ro,Z"
)

if [ -n "$PODMAN_SOCK" ]; then
  PODMAN_ARGS+=( -v "${PODMAN_SOCK}:/run/podman/podman.sock" -e "DOCKER_HOST=unix:///run/podman/podman.sock" )
fi

PODMAN_ARGS+=( "$IMAGE" )

if [ "$BUILD_MODE" = "shell" ]; then
  exec podman "${PODMAN_ARGS[@]}" bash
else
  exec podman "${PODMAN_ARGS[@]}" bash -lc "
    export PATH=/build/bin:\$PATH
    /scripts/setup-ssh-wrappers.sh
    /scripts/prepare-workdir.sh
    /scripts/run-gradle.sh
  "
fi
```

Make executable и тествай shell-а:

```bash
chmod +x ~/geowealth/bin/run-buildbox.sh
BUILD_MODE=shell ~/geowealth/bin/run-buildbox.sh
# трябва да отвори bash prompt вътре в buildbox-а
```

### `scripts/run-gradle.sh`

Самата Gradle команда. Източник: `deploy_env.groovy:1041-1049` (за `fullpush`) и `deploy_env.groovy:1096-1104` (за `localbuild`).

```bash
#!/usr/bin/env bash
set -euo pipefail

WORK_DIR="${WORK_DIR:-/build/current}"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-/build/gradle-cache}"
BUILD_TARGET="${BUILD_TARGET:-qa1}"
BUILD_MODE="${BUILD_MODE:-localbuild}"

# Symlink HOME/.gradle към cached gradle home (както в buildbox Job-а)
mkdir -p "$GRADLE_USER_HOME"
if [ ! -e "$HOME/.gradle" ]; then
  ln -sfn "$GRADLE_USER_HOME" "$HOME/.gradle"
fi

# Експортирай DEPLOY_*_HOSTS като Gradle properties (както в buildbox Job-а)
export ORG_GRADLE_PROJECT_DEPLOY_APP_HOSTS="${DEPLOY_APP_HOSTS:-}"
export ORG_GRADLE_PROJECT_DEPLOY_WEB_HOSTS="${DEPLOY_WEB_HOSTS:-}"

cd "$WORK_DIR"

case "$BUILD_MODE" in
  fullpush)
    TASK="fullpush"
    ;;
  localbuild)
    TASK="localbuild"
    ;;
  flyway)
    TASK="flywayRun"
    ;;
  *)
    echo "[run-gradle] непознат BUILD_MODE: $BUILD_MODE" >&2
    exit 3
    ;;
esac

GRADLE_DEBUG_FLAGS="${GRADLE_DEBUG_FLAGS:---stacktrace --info}"
LOG_FILE="$WORK_DIR/gradle_${TASK}.log"

echo "[run-gradle] cwd=$WORK_DIR target=$BUILD_TARGET task=$TASK"
echo "[run-gradle] DEPLOY_APP_HOSTS=${DEPLOY_APP_HOSTS:-} DEPLOY_WEB_HOSTS=${DEPLOY_WEB_HOSTS:-}"

set +e
./gradlew --no-daemon --console=plain \
  --gradle-user-home "$GRADLE_USER_HOME" \
  -Ptarget="${BUILD_TARGET}" \
  clean "$TASK" $GRADLE_DEBUG_FLAGS 2>&1 | tee "$LOG_FILE"
RC=${PIPESTATUS[0]}
set -e

if [ "$RC" -ne 0 ]; then
  echo "[run-gradle] FAILED rc=$RC log=$LOG_FILE"
  awk '/FAILURE: Build failed with an exception\./{p=1} p{print} /^BUILD FAILED in /{if(p) exit}' "$LOG_FILE" | tail -120
  exit "$RC"
fi

echo "[run-gradle] SUCCESS rc=$RC log=$LOG_FILE"
```

## Стъпка 7: Изпълнение

### 7.A — Build only (без deploy) — препоръчително локално

`localbuild` task-ът прави същия build като `fullpush`, но НЕ push-ва към OCIR и не SSH-ва към qa1app/qa1web. Това е същият task, който `Deploy Local QA` стейджа ползва за qa4-qa11.

```bash
TARGET_REF=master \
BUILD_TARGET=qa1 \
BUILD_MODE=localbuild \
~/geowealth/bin/run-buildbox.sh
```

### 7.B — Full build + deploy към реален qa1 (изисква creds)

Само ако имаш и:
- `~/geowealth/secrets/deploy-ssh/deploy_key` = валиден jenkins-private-key
- OCIR creds login: `podman login iad.ocir.io -u <user> -p <token>`
- Мрежов достъп до qa1app + qa1web

```bash
TARGET_REF=master \
BUILD_TARGET=qa1 \
BUILD_MODE=fullpush \
DEPLOY_APP_HOSTS=qa1app \
DEPLOY_WEB_HOSTS=qa1web \
~/geowealth/bin/run-buildbox.sh
```

### 7.C — Само shell за debug

```bash
BUILD_MODE=shell ~/geowealth/bin/run-buildbox.sh
# вътре в контейнера ръчно:
/scripts/setup-ssh-wrappers.sh
TARGET_REF=master /scripts/prepare-workdir.sh
BUILD_MODE=localbuild BUILD_TARGET=qa1 /scripts/run-gradle.sh
```

## Стъпка 8: Verification (както `readRemoteBuildInfo` го прави)

След успешен build, `deploy_env.groovy:88-118` дърпа buildinfo от remote хоста и архивира. Локалният еквивалент:

```bash
# вътре в контейнера, в /build/current
cat build/release/etc/buildinfo.properties 2>/dev/null \
  || find . -name 'buildinfo*' -type f
```

Извън контейнера — артефактите са в `~/geowealth/code/build/`:

```bash
ls -la ~/geowealth/code/build/libs/      # JARs
ls -la ~/geowealth/code/build/release/   # release bundle (буде ползван от fullpush deploy step)
```

## Env vars референция (1:1 с qa-3 values.yaml)

| Var | Стойност | Източник |
|---|---|---|
| `TARGET_REF` | `master` (или твоят branch) | values.yaml envPlain |
| `BUILD_MODE` | `fullpush` / `localbuild` / `flyway` | values.yaml envPlain |
| `DEPLOY_APP_HOSTS` | `qa1app` (празно за localbuild) | values.yaml envPlain |
| `DEPLOY_WEB_HOSTS` | `qa1web` (празно за localbuild) | values.yaml envPlain |
| `DEPLOY_USER_APP` | `geowealth` | values.yaml envPlain |
| `DEPLOY_USER_WEB` | `tomcat` | values.yaml envPlain |
| `REMOTE_PATH` | `/geowealth/adviser/current/` | values.yaml envPlain |
| `GRADLE_USER_HOME` | `/build/gradle-cache` | values.yaml envPlain |
| `GRADLE_OPTS` | `-Xmx6g -XX:MaxMetaspaceSize=1g -XX:+UseParallelGC -Dfile.encoding=UTF-8` | values.yaml envPlain |
| `JAVA_TOOL_OPTIONS` | `-XX:+UseParallelGC -Dfile.encoding=UTF-8` | values.yaml envPlain |
| `NODE_OPTIONS` | `--max_old_space_size=4096` | values.yaml envPlain |
| `ORG_GRADLE_PROJECT_org_gradle_daemon` | `true` | values.yaml envPlain |
| `ORG_GRADLE_PROJECT_org_gradle_parallel` | `true` | values.yaml envPlain |
| `ORG_GRADLE_PROJECT_org_gradle_workers_max` | `8` | values.yaml envPlain |
| `ORG_GRADLE_PROJECT_org_gradle_configureondemand` | `true` | values.yaml envPlain |
| `ORG_GRADLE_PROJECT_org_gradle_caching` | `true` | values.yaml envPlain |
| `ORG_GRADLE_PROJECT_org_gradle_configuration_cache` | `true` | values.yaml envPlain |
| `ORG_GRADLE_PROJECT_org_gradle_configuration_cache_problems` | `warn` | values.yaml envPlain |
| `DOCKER_HOST` | `unix:///run/podman/podman.sock` | values.yaml envPlain |
| `HOME` | `/build` | values.yaml envPlain |
| `LANG` | `en_US.UTF-8` | buildbox Job script |
| `SLACK_WEBHOOK_URL` | от Slack webhook secret (опционално) | values.yaml envFrom |

## Volume mounts (1:1 с buildbox Job)

| Контейнер path | Хост path | Mode | Назначение |
|---|---|---|---|
| `/cache/git/geowealth` | `~/geowealth/cache/git/geowealth.git` | ro | Bare mirror за бърз clone |
| `/build/gradle-cache` | `~/geowealth/cache/gradle` | rw | Gradle user home (`.gradle/caches`) |
| `/build` | (emptyDir в OKE, persistent локално) | rw | Workspace + кеш |
| `/deploy-secret` | `~/geowealth/secrets/deploy-ssh` | ro | SSH key + known_hosts за qaXapp/qaXweb |
| `/ssh-gitlab` | `~/geowealth/secrets/gitlab-ssh` | ro | SSH key за git clone от gitlab.com |
| `/run/podman/podman.sock` | host podman socket | rw | Nested image builds в `fullpush` |
| `/scripts` | `~/geowealth/scripts` | ro | Локални helper скриптове |

## Какво пропускаме спрямо OKE buildbox-а

| Production buildbox | Локален еквивалент |
|---|---|
| OCIR pull на image-а | Локален podman build |
| PVC `cache-git-pvc` | bind mount `~/geowealth/cache/git/...` |
| PVC `cache-gradle-pvc` | bind mount `~/geowealth/cache/gradle` |
| Secret `deploy-ssh` (k8s) | bind mount `~/geowealth/secrets/deploy-ssh` |
| Secret `gitlab-ssh` (k8s) | bind mount `~/geowealth/secrets/gitlab-ssh` |
| hostPath `/run/podman/podman.sock` | bind mount на host podman socket |
| ServiceAccount `geowealth-buildbox` | n/a (само podman, не k8s API) |
| Job backoffLimit/restartPolicy | `--rm` (single shot) |
| Slack webhook secret | `SLACK_WEBHOOK_URL` env var (опционално) |
| Git mirror CronJob | ръчно `sync-mirror.sh` |
| qa-N-deployement-helm release | n/a |

## Гочи и решения

| Проблем | Симптом | Решение |
|---|---|---|
| Гладен Gradle heap | OOM при build | Увеличи `GRADLE_OPTS=-Xmx10g` (имай 12GB+ за контейнера) |
| React build OOM | `JavaScript heap out of memory` | Дръж `NODE_OPTIONS=--max_old_space_size=4096` (или по-високо) |
| Sencha CMD missing | gradle task падна на Sencha step | Билд-ват се локално чрез `dockerfiles/Dockerfile.sencha` — buildbox-ът има `/home/geowealth/bin/Sencha/Cmd` директория, но самата инсталация се прави динамично (виж sencha-install-cronjob в oci_oke_deployments/k8s/cronjobs) |
| fullpush иска OCIR push | `unauthorized: authentication required` | `podman login iad.ocir.io` ИЛИ ползвай `localbuild` |
| fullpush иска SSH до qa1app | timeout/permission denied | VPN/network достъп + правилен deploy_key ИЛИ ползвай `localbuild` |
| podman socket липсва | docker tasks падат | Стартирай `podman system service --time=0 unix:///run/podman/podman.sock &` или ползвай socket-а от podman machine |
| Линукс line endings | странни grep/awk failures | Buildbox-ът включва `dos2unix`; пускай `find . -type f -name '*.sh' -exec dos2unix {} \;` ако клонираш на Windows |
| `safe.directory` git warnings | `fatal: detected dubious ownership` | `prepare-workdir.sh` го добавя глобално; ако ползваш ръчно — `git config --global --add safe.directory /build/current` |
| First run е бавен | 30+ min Gradle download | След първи build cache-ът остава в `~/geowealth/cache/gradle` — следващите са 5-10 min |
| macOS userns | permission errors на mounted dirs | `--userns=keep-id` (вече е в wrapper-а); ако още има — provider-specific (Colima vs podman machine) |

## Ежедневен flow

```bash
# Refresh на mirror-а (sync upstream)
~/geowealth/scripts/sync-mirror.sh

# Build на твоя branch
TARGET_REF=feature/my-branch BUILD_TARGET=qa1 BUILD_MODE=localbuild \
  ~/geowealth/bin/run-buildbox.sh

# Проверка на артефактите
ls -la ~/geowealth/code/build/libs/
```

За iterative debug на самия build — отвори shell, направи промените в `/build/current` (или mount-ни own работен tree), и пускай `run-gradle.sh` многократно (Gradle cache-ът е persistent).

## Quick-reference: пълен `setup.sh`

Multi-command скрипт за първоначална настройка и периодичен maintenance:

```bash
#!/usr/bin/env bash
# setup.sh — инициализация и поддръжка на локалния buildbox setup
#
# Usage:
#   setup.sh                 # = setup.sh init
#   setup.sh init            # първоначална настройка (скеле, image, ключове)
#   setup.sh cleanup         # лек cleanup преди build (podman prune + старите артефакти)
#   setup.sh deep-cleanup    # агресивен cleanup (gradle transforms, node_modules, build/)
#   setup.sh disk            # покажи разпределението на disk usage
#   setup.sh help

set -euo pipefail
ROOT="${ROOT:-$HOME/geowealth}"
IMAGE="${IMAGE:-geowealth-buildbox:local}"

# ===== helpers =====

log()  { printf '[setup.sh] %s\n' "$*"; }
size() { du -sh "$1" 2>/dev/null | awk '{print $1}'; }

confirm() {
  local prompt="$1" reply
  read -r -p "$prompt [y/N] " reply
  [[ "$reply" =~ ^[Yy]$ ]]
}

# ===== init =====

cmd_init() {
  log "1/6 скеле в $ROOT"
  mkdir -p "$ROOT"/{code,cache/git,cache/gradle,secrets/gitlab-ssh,secrets/deploy-ssh,scripts,bin}
  chmod 700 "$ROOT"/secrets/{gitlab-ssh,deploy-ssh}

  log "2/6 buildbox image ($IMAGE)"
  if ! podman image exists "$IMAGE"; then
    TMP=$(mktemp -d)
    git clone --depth 1 git@gitlab.com:gwm1/deployment/oci_oke_deployments.git "$TMP"
    podman build -f "$TMP/Dockerfile.buildbox" -t "$IMAGE" "$TMP"
    rm -rf "$TMP"
  else
    log "    вече съществува — пропускам build"
  fi

  log "3/6 GitLab SSH ключ"
  if [ ! -f "$ROOT/secrets/gitlab-ssh/ssh-privatekey" ]; then
    ssh-keygen -t ed25519 -f "$ROOT/secrets/gitlab-ssh/ssh-privatekey" -N ""
    ssh-keyscan gitlab.com > "$ROOT/secrets/gitlab-ssh/known_hosts" 2>/dev/null
    echo "==> Добави този pub key в gitlab.com → User Settings → SSH Keys:"
    cat "$ROOT/secrets/gitlab-ssh/ssh-privatekey.pub"
    read -r -p "Натисни Enter след като добавиш ключа..."
  else
    log "    вече съществува — пропускам"
  fi

  log "4/6 stub deploy_key (за localbuild)"
  if [ ! -f "$ROOT/secrets/deploy-ssh/deploy_key" ]; then
    touch "$ROOT/secrets/deploy-ssh/deploy_key"
    chmod 600 "$ROOT/secrets/deploy-ssh/deploy_key"
    touch "$ROOT/secrets/deploy-ssh/known_hosts"
  else
    log "    вече съществува — пропускам"
  fi

  log "5/6 копирай скриптовете от документа в:"
  echo "    $ROOT/scripts/   (sync-mirror, setup-ssh-wrappers, prepare-workdir, run-gradle)"
  echo "    $ROOT/bin/       (run-buildbox)"
  echo "    и направи chmod +x на всички"

  log "6/6 следващи стъпки:"
  echo "    $ROOT/scripts/sync-mirror.sh                         # първоначален mirror"
  echo "    BUILD_MODE=shell $ROOT/bin/run-buildbox.sh           # smoke test"
  echo "    BUILD_MODE=localbuild $ROOT/bin/run-buildbox.sh      # реален build"
}

# ===== cleanup (light) =====
# Пускай преди всеки build на тесен диск.
cmd_cleanup() {
  log "podman dangling images"
  podman image prune -f || true

  log "podman builder cache (важно при fullpush)"
  podman builder prune -f 2>/dev/null || true

  log "стари build артефакти"
  if [ -d "$ROOT/code/build" ]; then
    log "    изтривам $ROOT/code/build ($(size "$ROOT/code/build"))"
    rm -rf "$ROOT/code/build"
  fi

  log "Gradle daemon (ако има stuck instance)"
  rm -rf "$ROOT/cache/gradle/daemon" 2>/dev/null || true

  log "готово. df -h $ROOT:"
  df -h "$ROOT" | awk 'NR<=2'
}

# ===== deep cleanup =====
# По-агресивно — пускай седмично или когато диска е критично пълен.
cmd_deep_cleanup() {
  cmd_cleanup

  log "Gradle transforms cache"
  rm -rf "$ROOT/cache/gradle/caches/transforms-"* 2>/dev/null || true

  log "Gradle build cache (force rebuild след това)"
  if confirm "Изтриваме build-cache? Следващият build ще е по-бавен."; then
    rm -rf "$ROOT/cache/gradle/caches/build-cache-"* 2>/dev/null || true
  fi

  log "node_modules в workspace-а"
  if [ -d "$ROOT/code" ]; then
    find "$ROOT/code" -type d -name node_modules -prune -print \
      | while read -r d; do log "    rm -rf $d ($(size "$d"))"; rm -rf "$d"; done
  fi

  log "podman: ВСИЧКИ непотребни image-и + volumes (НЕ image-а $IMAGE)"
  if confirm "Изтриваме всички dangling/непотребни podman ресурси?"; then
    podman system prune -af --volumes || true
    # Re-pull/rebuild на нашия image ще е нужен ако е изтрит
    if ! podman image exists "$IMAGE"; then
      log "    image $IMAGE също е изтрит — пусни 'setup.sh init' за rebuild"
    fi
  fi

  # macOS: thin APFS local snapshots
  if [[ "$(uname)" == "Darwin" ]]; then
    log "macOS APFS local snapshots"
    if confirm "Thin-вай local Time Machine snapshots? (изисква sudo за някои случаи)"; then
      tmutil thinlocalsnapshots / 999999999999 1 || true
    fi
  fi

  log "готово. df -h $ROOT:"
  df -h "$ROOT" | awk 'NR<=2'
}

# ===== disk usage report =====

cmd_disk() {
  log "Disk usage в $ROOT"
  printf '%-40s %10s\n' "PATH" "SIZE"
  for p in \
    "$ROOT/cache/git" \
    "$ROOT/cache/gradle" \
    "$ROOT/cache/gradle/caches" \
    "$ROOT/cache/gradle/caches/transforms-3" \
    "$ROOT/cache/gradle/caches/build-cache-1" \
    "$ROOT/code" \
    "$ROOT/code/build" \
    "$ROOT/code/node_modules"; do
    if [ -e "$p" ]; then
      printf '%-40s %10s\n' "${p#$HOME/}" "$(size "$p")"
    fi
  done
  echo
  log "Podman images:"
  podman images --format 'table {{.Repository}}:{{.Tag}}  {{.Size}}' 2>/dev/null | head -20
  echo
  log "Podman builder cache:"
  podman system df 2>/dev/null || true
  echo
  log "Free на mountpoint-а на $ROOT:"
  df -h "$ROOT" | awk 'NR<=2'
}

cmd_help() {
  sed -n '2,10p' "$0"
}

# ===== dispatch =====

case "${1:-init}" in
  init)          cmd_init ;;
  cleanup)       cmd_cleanup ;;
  deep-cleanup)  cmd_deep_cleanup ;;
  disk)          cmd_disk ;;
  help|-h|--help) cmd_help ;;
  *) echo "Unknown command: $1" >&2; cmd_help; exit 2 ;;
esac
```

### Cleanup cheat sheet

```bash
~/geowealth/setup.sh cleanup        # преди всеки build на тесен диск
~/geowealth/setup.sh disk           # бърз поглед колко място яде какво
~/geowealth/setup.sh deep-cleanup   # седмично или при no-space паника
```

## TL;DR команден ред

```bash
# Setup (еднократно)
~/geowealth/setup.sh                       # = setup.sh init
~/geowealth/scripts/sync-mirror.sh

# Build (всеки път)
TARGET_REF=master BUILD_TARGET=qa1 BUILD_MODE=localbuild ~/geowealth/bin/run-buildbox.sh
```
