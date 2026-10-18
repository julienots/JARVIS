# JARVIS — assistant vocal Android par SuperEssence

![Aperçu de la sphère](docs/sphere-preview.png)

*Rendu réel des shaders de la sphère (veille, écoute, réflexion, style Or).*

Jarvis est un assistant vocal Android en français. Il a la forme d'une **sphère 3D en relief qui bouge**
et réagit à votre voix. Vous lui parlez naturellement : il répond à voix haute, affiche des mini-cartes
par-dessus vos applications et agit sur le téléphone avec les fonctions officielles d'Android.
Au démarrage, l'application affiche la signature du studio **SuperEssence**.

---

## 1. Installation (sans savoir coder)

1. Sur GitHub, ouvrez l'onglet **Actions**, puis la dernière exécution réussie (✓ verte) de « Android ».
2. En bas de la page, téléchargez l'artefact **jarvis-debug-apk** (un fichier zip), puis décompressez-le : vous obtenez `app-debug.apk`.
3. Copiez `app-debug.apk` sur le téléphone et ouvrez-le. Android demande d'autoriser l'installation depuis cette source : acceptez.
4. Ouvrez **Jarvis**. L'écran SuperEssence s'affiche, puis un guide en 6 étapes vous accompagne.

> Les nouvelles versions s'installent par-dessus l'ancienne : vos réglages sont conservés.

## 2. Première configuration pas à pas

| Étape | Ce qu'il faut faire | Pourquoi |
|---|---|---|
| Micro | « Autoriser le micro » | Pour vous entendre |
| Notifications | « Autoriser » | Rappels, minuteurs, indicateur d'écoute |
| Écoute « Jarvis » | Cocher la case puis « Activer l'écoute » (facultatif) | Parler sans toucher le téléphone |
| IA (recommandé) | Réglages → Intelligence artificielle → coller une clé → Enregistrer → Tester | Répondre à toutes les questions |
| Tout autoriser | Réglages → Autorisations → « Tout autoriser » | Accorde d'un coup tout ce qu'il faut pour automatiser le téléphone |
| Bulle flottante | Réglages → Autorisations → « Affichage par-dessus » | Sphère et mini-écrans par-dessus les autres applis |
| Batterie | Réglages → Batterie → « Retirer la limitation » | Empêcher Android de couper l'écoute |
| Hors ligne (option) | Réglages → Voix → « Télécharger le modèle » (41 Mo) | Mot de réveil 100 % local, sans Internet |

### Obtenir une clé d'IA gratuite
- **Google Gemini** : https://aistudio.google.com/apikey (gratuit avec quotas)
- **Groq** : https://console.groq.com/keys (gratuit avec limites, très rapide)
- **Mistral** : https://console.mistral.ai/api-keys (essai gratuit limité)
- **Claude (Anthropic)** : https://console.anthropic.com/settings/keys (payant à l'usage)
- **ChatGPT (OpenAI)** : https://platform.openai.com/api-keys (payant à l'usage ; l'abonnement ChatGPT Plus n'inclut pas l'API)
- **Compatible OpenAI** : OpenRouter, ou un serveur **Ollama / LM Studio** sur votre ordinateur (gratuit, réseau local)

Activez plusieurs fournisseurs : si l'un tombe en panne ou atteint son quota, Jarvis **bascule automatiquement**
sur le suivant (l'ordre se règle avec les flèches). Les noms de modèles sont modifiables si un fournisseur en publie de nouveaux.

## 3. Ce que vous pouvez dire

| Exemple | Résultat |
|---|---|
| « Jarvis, quelle heure est-il ? » | Heure en grand + réponse vocale |
| « Lance un minuteur de 5 minutes » | Minuteur dans l'Horloge + compte à rebours flottant |
| « Réveille-moi à 7 h 30 » | Alarme dans l'Horloge |
| « Rappelle-moi dans 10 minutes de sortir le linge » | Notification de rappel |
| « Ajoute du pain à ma liste » / « Montre mes prochaines étapes » | Liste de tâches locale |
| « Donne-moi la météo à Lyon » | Carte météo (Open-Meteo, sans clé) |
| « Cherche la tour Eiffel » | Résumé Wikipédia avec lien vers la source |
| « Ouvre Spotify » / « Ouvre mes réglages Wi-Fi » | Ouvre l'application / l'écran de réglages |
| « Allume la lampe », « Monte le volume », « Chanson suivante » | Lampe, volume, lecteur multimédia |
| « Appelle maman » / « Envoie un message à Paul pour lui dire… » | **Confirmation demandée**, puis l'appel est lancé / le SMS envoyé (si vous l'avez autorisé), sinon préparé |
| « Lis mes notifications » | Lecture (si l'accès est activé) |
| « Cherche Paul dans mon téléphone » / « Trouve le fichier contrat » | Applis, contacts, fichiers et agenda trouvés, affichés dans un mini-écran, **sans quitter l'appli en cours** |
| « Qu'est-ce que j'ai de prévu demain ? » | Événements de l'agenda |
| « Lis l'écran » / « Résume cette page » | Lit ou résume (avec l'IA) le texte de l'appli affichée |
| « Baisse la luminosité » · « Active ne pas déranger » | Luminosité, mode Ne pas déranger |
| « Retour » · « Va à l'accueil » · « Fais une capture d'écran » · « Verrouille le téléphone » | Gestes système (service d'accessibilité) |
| « Résume ce que je viens de demander » | Résumé de la session |
| « Ferme le panneau » · « Cache-toi » · « Arrête-toi » · « Arrête l'écoute » | Contrôle de l'interface et du micro |
| Toute autre question | Réponse de l'IA configurée |

Après une réponse, Jarvis réécoute quelques secondes (conversation continue, réglable). Le silence le remet en veille.
Pendant qu'il parle, dire « Jarvis » l'interrompt (avec le modèle hors ligne).

## 4. La sphère et les mini-écrans

Rendu OpenGL ES 2.0 : une sphère déformée en temps réel par un bruit 3D, avec halo, anneaux orbitaux et particules.
- **Couleur et mouvement selon l'état** : veille (cyan calme), réveil (flash), écoute (relief qui suit votre voix),
  réflexion (violet tourbillonnant), action (or), confirmation (ambre), erreur (rouge), micro coupé (gris).
- **Sans fond** : seule la sphère est dessinée (rendu OpenGL dans une TextureView transparente), aucun carré noir derrière.
- **Interactive** : touchez-la pour parler, faites-la tourner du doigt.
- **Hors de l'appli** : seule la sphère flotte par-dessus vos applications et s'anime quand Jarvis écoute et parle.
  Touchez-la pour parler, faites-la glisser pour la déplacer, appui long pour la fermer.
  Option « Sphère toujours visible » dans Réglages → Apparence et bulle.
- **Mini-écrans** : les réponses et résultats s'ouvrent dans une petite fenêtre séparée, que l'on déplace par sa poignée,
  épingle ou ferme ; elle disparaît seule après quelques secondes. Ces fenêtres ne prennent jamais le clavier :
  vous continuez d'utiliser l'application en dessous.
- Quatre styles (Hologramme, Or SuperEssence, Nébuleuse, Émeraude), mode économie (20 images/s),
  et respect du réglage Android « Supprimer les animations ». Si OpenGL est indisponible, une version 2D prend le relais.

## 5. Autorisations et leur utilité

| Autorisation | Utilité | Obligatoire ? |
|---|---|---|
| `RECORD_AUDIO` | Vous entendre | Pour la voix |
| `POST_NOTIFICATIONS` | Rappels, minuteurs, indicateur d'écoute | Recommandée |
| `FOREGROUND_SERVICE_MICROPHONE` | Écoute du mot « Jarvis » en arrière-plan | Pour l'écoute permanente |
| `SYSTEM_ALERT_WINDOW` | Bulle flottante + ouvrir des applis à la voix depuis l'arrière-plan | Recommandée |
| `SCHEDULE_EXACT_ALARM` | Rappels à la minute près | Facultative |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Éviter l'arrêt de l'écoute | Facultative |
| `READ_CONTACTS` | Trouver un numéro (« appelle Paul »), recherche de contacts | Facultative |
| `CALL_PHONE` / `SEND_SMS` | Appeler / envoyer le SMS directement, **après votre « oui »** | Facultative |
| `READ_CALENDAR` | Agenda, recherche d'événements | Facultative |
| `ACCESS_COARSE/FINE_LOCATION` | Météo là où vous êtes | Facultative |
| `READ_MEDIA_*` | Retrouver photos, vidéos, musiques par leur nom | Facultative |
| `WRITE_SETTINGS` | Luminosité | Facultative |
| `ACCESS_NOTIFICATION_POLICY` | Mode Ne pas déranger | Facultative |
| Service d'accessibilité | Gestes système, lecture / résumé de l'écran | Facultative |
| `QUERY_ALL_PACKAGES` | Retrouver et ouvrir toutes vos applications | Automatique |
| Accès aux notifications | « Lis mes notifications » | Facultative |
| `SET_ALARM` | Minuteurs et alarmes de l'Horloge | Automatique |
| `INTERNET` | IA, météo, recherche, modèle hors ligne | Automatique |
| `RECEIVE_BOOT_COMPLETED` | Reprogrammer rappels et routines | Automatique |

Le **service d'accessibilité** est facultatif. Activé, il sert uniquement aux gestes demandés à la voix et à la lecture de l'écran
**quand vous le demandez** (« lis l'écran ») ; les champs de mot de passe sont ignorés et rien n'est enregistré.

## 6. Limites d'Android (à connaître)

Jarvis va aussi loin qu'Android le permet, mais certaines limites sont imposées par le système et ne peuvent pas être contournées :

- **Écoute permanente** : Android exige une notification visible et peut arrêter le service (surtout chez Xiaomi, Huawei, Oppo, Samsung).
  Réglages → Batterie donne les conseils pour votre marque.
- **Après un redémarrage**, Android 14+ interdit de relancer le micro automatiquement : une notification permet de le faire en un geste.
- **Ouvrir une application depuis l'arrière-plan** est bloqué sans l'autorisation « Affichage par-dessus ». Sinon, Jarvis envoie une notification à toucher.
- **Wi-Fi, Bluetooth, mode avion** : depuis Android 10, une application ne peut plus les activer seule. Jarvis ouvre le bon panneau, vous touchez l'interrupteur.
- **Appels et SMS** : toujours après votre « oui ». Sans les autorisations `CALL_PHONE` / `SEND_SMS`, ils sont seulement préparés.
- **Paiements, achats, virements, suppression de données, sécurité** : refusés.
- **Applications bancaires, mots de passe, écran verrouillé** : non accessibles.
- **Reconnaissance Android** : selon le téléphone, l'audio peut passer par les serveurs du fournisseur (souvent Google).
  Le modèle hors ligne Vosk évite cela.

## 7. Confidentialité

- Pas d'enregistrement audio. Historique de conversation **désactivé par défaut** (activable, stocké uniquement sur le téléphone, effaçable).
- Clés API **chiffrées** avec l'Android Keystore, jamais écrites dans le code ni dans le dépôt, exclues des sauvegardes.
- **Mode privé** : micro coupé, bulle fermée, aucune requête réseau.
- Journal local des actions, consultable et effaçable.
- Aucune donnée n'est envoyée à SuperEssence.

## 8. Dépannage

| Problème | Solution |
|---|---|
| « Je n'entends rien » / pas de réponse vocale | Réglages → Voix → « Synthèse vocale Android » : installez une voix française |
| « Aucun service de reconnaissance » | Installez l'application Google, ou téléchargez le modèle hors ligne |
| L'écoute « Jarvis » s'arrête | Réglages → Batterie → retirer la limitation + conseils fabricant |
| « La clé API a été refusée » | Vérifiez la clé et le nom du modèle, puis « Tester » |
| Quota atteint | Activez un second fournisseur : la bascule est automatique |
| La bulle n'apparaît pas | Réglages → Autorisations → « Affichage par-dessus » |
| Le mot « Jarvis » est mal reconnu | Modèle hors ligne, ou changez de mot de réveil dans Réglages → Voix |

L'écran **Réglages → Diagnostic** indique ce qui fonctionne sur votre téléphone.

---

## Pour les développeurs

### Architecture

```
core/   Kotlin pur (testé sur JVM, sans Android)
  intent/   IntentParser : commandes françaises → JarvisIntent (nombres en lettres, heures, durées)
  wake/     WakeWordMatcher : détection tolérante de « Jarvis » dans une transcription
  state/    AssistantStateMachine : veille → réveil → écoute → réflexion → action → réponse
  policy/   ActionPolicy (confirmations, interdictions), ConfirmationParser (oui/non)
  ai/       AiProtocol (Gemini, Claude, OpenAI, Groq, Mistral, compatible OpenAI), AiOrchestrator (bascule),
            AiActionExtractor (actions proposées par l'IA, repassées par la politique de sécurité)
  brain/    AssistantBrain : orchestration, mémoire courte, résumé, routines
  routine/  Routine : modèle, planification, JSON
  info/     Open-Meteo, Wikipédia
app/    Android (Jetpack Compose)
  runtime/SessionController   Cycle de session vocale, cartes, conversation continue
  voice/    TtsManager, AndroidSpeechInput, VoskModelManager, moteurs de mot de réveil (Vosk / Android)
  service/  JarvisService (premier plan « microphone »), accessibilité, tuile, alarmes, démarrage, notifications
  actions/  AndroidActionExecutor (API officielles), AppLauncher, Scheduler
  overlay/  Sphère flottante sans fond + mini-écran séparé (déplaçables, épinglables, non bloquants)
  sphere/   Rendu OpenGL ES 2.0 de la sphère dans une TextureView transparente + repli 2D
  ui/       Écran SuperEssence, guide de démarrage, Accueil, Assistant, Routines, Réglages, Autorisations, Diagnostic
```

### Compiler

Prérequis : JDK 17 et SDK Android (API 35).

```bash
./gradlew :core:test           # tests unitaires du cœur
./gradlew :app:assembleDebug   # APK : app/build/outputs/apk/debug/app-debug.apk
./gradlew :core:test -PcoreOnly=true   # cœur uniquement, sans SDK Android
```

La CI GitHub Actions (`.github/workflows/android.yml`) exécute les tests, compile l'APK et le publie en artefact.

`keystore/debug.keystore` est une clé de **débogage** (mot de passe public `android`) pour installer les mises à jour par-dessus.
Ne l'utilisez pas pour une publication sur le Play Store.

### Ajouter un fournisseur d'IA
Ajouter une entrée à `AiProviderType` ; si l'API est compatible OpenAI, rien d'autre n'est nécessaire.
Sinon, ajouter la construction de requête et la lecture de réponse dans `AiProtocol`.
