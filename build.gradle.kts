// Plugins Kotlin chargés une seule fois pour tous les modules (versions dans settings.gradle.kts).
plugins {
    id("org.jetbrains.kotlin.jvm") apply false
    id("org.jetbrains.kotlin.android") apply false
    id("org.jetbrains.kotlin.plugin.compose") apply false
}
