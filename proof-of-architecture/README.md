# Cortana — Chantier 0.5 : Proof of Architecture

Module Android autonome, séparé de tout futur module "vraie app Cortana", dédié
à un unique objectif : prouver sur la Samsung Galaxy Tab A11 réelle de William,
entièrement depuis l'APK, que le mécanisme `HermesRuntime` (embarquer un
interpréteur Python natif, installer Hermes, valider ses extensions natives,
démarrer le backend headless, parler JSON-RPC/WebSocket, survivre à un
kill+restart, et confirmer SQLite en mode WAL) tient réellement debout.

Ce module n'est pas la version finale de Cortana. Voir le document de tâche
`CORTANA_CHANTIER_0.5_DIRECTIVES_CLAUDE_CODE.md` (fourni par William dans la
conversation ayant produit cette PR) pour le contexte complet et les décisions
actées à trois (William, Claude architecture, ChatGPT).

**Nom de paquet verrouillé : `com.williamguillon.cortana`.** Utilisé partout
(namespace/applicationId Gradle, manifest, autorité du `FileProvider`) — voir
`CORTANA_DIRECTIVES_CLAUDE_CODE_APPLICATION_INSTALLABLE.md` (document qui
remplace, pour tout ce qui concerne le livrable, la logique "PR + rapport de
diagnostic" ci-dessous par "APK installable publié en release GitHub", produit
via GitHub Actions puisque cet environnement n'a ni tablette ni SDK Android
locaux). Ce nom ne doit plus être rediscuté ni changé sans une raison
bloquante réelle (le bootstrap Python de la Phase 2 en dépend).

## Documents de contexte

Cette PR est la suite de trois documents (produits et validés par William +
ChatGPT avant tout code) :

1. `CORTANA_ANDROID_RAPPORT_ARCHITECTURE_CLAUDE.md` — rapport d'architecture
   complet sur le dépôt upstream `NousResearch/hermes-agent`.
2. `CORTANA_CHANTIER_0.5_PLAN_PROOF_OF_ARCHITECTURE_V2.md` — protocole de test
   détaillé.
3. `CORTANA_CHANTIER_0.5_DIRECTIVES_CLAUDE_CODE.md` — la spec d'implémentation
   traduite en tâches, suivie ici.

Ces trois documents n'étaient pas présents dans le dépôt au moment de cette PR
(ils vivaient dans la conversation Cowork qui a produit les directives) — à
ajouter par William dans `docs/` s'il souhaite les garder versionnés à côté du
code qu'ils spécifient.

## Écarts constatés par rapport à la spec (à documenter, pas à improviser)

En inspectant le vrai dépôt avant d'écrire du code (§3 de la spec), deux points
précis de la spec initiale se sont révélés inexacts pour ce dépôt réel :

- **Pas de RPC `client.capabilities`.** Le protocole `tui_gateway` réel (voir
  `tui_gateway/ws.py`) ne fait pas de handshake client→serveur nommé
  `client.capabilities`. À la connexion WebSocket, c'est le **serveur** qui
  pousse un évènement `gateway.ready` (frame `{"jsonrpc":"2.0","method":"event",
  "params":{"type":"gateway.ready","payload":{...}}}`). L'étape "Tester
  WebSocket" de l'écran technique (§4.4 point 6) a donc été implémentée comme
  "se connecter et attendre `gateway.ready`" plutôt que d'envoyer un appel
  `client.capabilities` qui n'existe pas côté serveur.
- **Pas de fichier `apps/shared/src/gateway-contract.openrpc.json`** dans ce
  dépôt. Les noms de méthodes et la forme des payloads ont donc été vérifiés
  directement dans l'implémentation serveur (`tui_gateway/methods_session.py`,
  `tui_gateway/methods_prompt.py`, `tui_gateway/server.py`,
  `tui_gateway/ws.py`) plutôt que depuis ce fichier de référence.
- **Pas de `constraints-termux.txt`** au niveau racine. L'extra `termux` existe
  bien dans `pyproject.toml` (`hermes-agent[termux]`), mais le fichier de
  contraintes référencé par la commande `pip install -e ".[termux]" -c
  constraints-termux.txt` du plan v2 n'existe pas encore dans le dépôt. Le code
  de `TermuxLikeHermesRuntime` construit la commande de façon défensive
  (n'ajoute `-c constraints-termux.txt` que si le fichier existe réellement
  dans `HERMES_HOME`) plutôt que de supposer sa présence.

Les noms de variables d'environnement provider (`OPENAI_API_KEY`,
`ANTHROPIC_API_KEY`, `OPENROUTER_API_KEY`, `OPENAI_BASE_URL`,
`ANTHROPIC_BASE_URL`) ont en revanche été confirmés exacts dans
`hermes_cli/auth.py` et `hermes_cli/config_defaults.py`.

## Ce qui n'est PAS inclus dans cette PR (gap assumé, pas improvisé)

Le mécanisme `nativeLibraryDir` décrit au §4.1 de la spec suppose des binaires
natifs déjà compilés pour Android/Bionic (interpréteur Python de type
Termux-bootstrap, linker, outils bas niveau) pour chaque ABI cible. **Ces
binaires ne sont pas fournis par cette PR** : les produire nécessite soit un
bootstrap Termux existant (paquets `.deb` Termux pour `arm64-v8a`) soit une
cross-compilation dédiée, ce qui dépasse ce que ce spike peut produire en tant
que changement de code — c'est un artefact binaire, pas du code source, et
cette session n'a ni accès à un appareil d'exécution Android/Bionic ni à un
export existant de ce bootstrap.

`app/src/main/jniLibs/` et `app/src/main/assets/runtime_payload/` sont donc
livrés comme **squelettes documentés** (voir leurs `README.md` respectifs) :
la structure, le nommage attendu et le point d'extraction sont en place, mais
les fichiers binaires eux-mêmes doivent être ajoutés séparément avant que
`./gradlew assembleDebug` puisse produire un APK qui démarre réellement un
interpréteur Python sur l'appareil.

**Conséquence directe** : aucune étape de la chaîne d'acceptation (§7 de la
spec) n'a pu être exécutée sur un appareil réel par cette session — il n'existe
pas de Samsung Galaxy Tab A11 ni d'émulateur Android dans cet environnement
d'exécution (pas d'`ANDROID_HOME`, pas de device connecté). Voir le rapport de
diagnostic simulé et le tableau de statut dans la description de la PR pour le
détail complet, honnête, de ce qui a et n'a pas été vérifié.

## Build

**En local**, si vous avez un SDK Android :

```
cd proof-of-architecture
./gradlew :app:assembleDebug
```

Non exécuté avec succès depuis cet environnement de développement (pas de SDK
Android configuré : `ANDROID_HOME` est vide ici, et le proxy réseau de ce
bac à sable a fini par répondre `429 Too Many Requests` en pleine résolution
de l'arbre de dépendances de l'AGP lors d'une tentative). C'est précisément
pour ça que le build réel passe par CI (voir ci-dessous), pas par cet
environnement.

**En CI** (méthode retenue pour produire un APK réellement installable) : le
workflow `.github/workflows/build-debug-apk.yml` installe le JDK et le SDK
Android en ligne de commande sur un runner GitHub Actions, lance
`./gradlew assembleDebug`, et — s'il réussit — publie l'APK en asset d'une
release GitHub taguée `spike-apk-YYYYMMDD-HHmm`, téléchargeable directement
depuis le navigateur d'une tablette (aucun PC, aucun ADB). S'il échoue, le job
échoue visiblement avec le log complet accessible depuis l'onglet Actions du
dépôt. Voir la description de la PR pour le lien vers le run le plus récent et
son résultat réel.
