package com.healthcoach.app

import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import kotlin.math.roundToInt

class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(32))
            setBackgroundColor(Color.rgb(16, 19, 26))
        }

        root.addView(TextView(this).apply {
            text = "HealthCoach — confidentialité"
            textSize = 28f
            setTextColor(Color.WHITE)
        })

        root.addView(TextView(this).apply {
            text = """
HealthCoach lit uniquement les données Santé Connect que tu autorises.

Les calculs sont faits sur ton téléphone. L'application peut écrire un résumé dans le dossier Google Drive que tu choisis afin que ton ChatGPT connecté à Drive puisse le lire quand tu demandes une analyse.

Aucune clé OpenAI n'est utilisée et HealthCoach n'envoie pas directement tes données à une API OpenAI.

Tu peux retirer les autorisations Santé Connect et l'accès au dossier Drive à tout moment.
            """.trimIndent()
            textSize = 17f
            setTextColor(Color.rgb(210, 216, 225))
            setPadding(0, dp(20), 0, 0)
            setLineSpacing(0f, 1.15f)
        })

        setContentView(root)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
