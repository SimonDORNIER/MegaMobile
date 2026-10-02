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

                Les données sont lues localement depuis Santé Connect puis écrites uniquement dans les fichiers Google Drive que vous choisissez avec le sélecteur Android. L application n utilise aucun serveur Health Bridge, aucune publicité et aucun SDK publicitaire.

                Les exports contiennent les identifiants et la source d'origine Santé Connect afin de permettre la déduplication des mesures provenant de plusieurs applications.

                Le fichier de commande distante ne contient pas de données de santé : il sert uniquement à demander un rafraîchissement. Vous pouvez révoquer les autorisations Santé Connect à tout moment et supprimer les autorisations de fichiers en effaçant les données de l application.
            """.trimIndent()
        }
        setContentView(ScrollView(this).apply { addView(text) })
    }
}
