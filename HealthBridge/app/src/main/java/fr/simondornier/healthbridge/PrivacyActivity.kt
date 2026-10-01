package fr.simondornier.healthbridge

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

class PrivacyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            textSize = 17f
            setPadding(48, 48, 48, 48)
            text = """
                Confidentialité — Health Bridge

                Health Bridge lit uniquement les catégories Santé Connect que vous autorisez.

                Les données restent sur votre téléphone et sont exportées vers le dossier que vous choisissez avec le sélecteur de fichiers Android. L'application n'utilise aucun serveur propriétaire, aucune publicité et aucun compte tiers.

                Les exports contiennent les identifiants et la source d'origine Santé Connect afin de permettre la déduplication des mesures provenant de plusieurs applications.

                Vous pouvez révoquer les autorisations Santé Connect à tout moment dans les paramètres Android et retirer l'accès au dossier en supprimant les données de l'application.
            """.trimIndent()
        }
        setContentView(ScrollView(this).apply { addView(text) })
    }
}
