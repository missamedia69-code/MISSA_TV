package com.missa.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.missa.tv.ui.AppRoot
import dagger.hilt.android.AndroidEntryPoint

/**
 * Unique activité de l'application.
 *
 * Elle porte les deux points d'entrée (téléphone et Android TV) et délègue tout
 * le reste à Compose. Les changements de configuration sont déclarés dans le
 * manifeste : la rotation ne recrée donc pas l'activité, et l'écran de lecture
 * n'est pas interrompu par un simple pivotement du téléphone.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AppRoot(onExit = { finish() })
        }
    }
}
