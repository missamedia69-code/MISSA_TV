package com.missa.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.missa.tv.ui.adaptive.rememberDeviceProfile
import com.missa.tv.ui.common.DeviceDiagnosticScreen
import com.missa.tv.ui.theme.MissaTvTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Activité unique de l'application.
 *
 * Elle porte les deux filtres d'intention (LAUNCHER et LEANBACK_LAUNCHER) : la
 * même activité sert donc sur téléphone, tablette et Android TV. Le choix de
 * l'interface se fait ensuite à l'exécution, à partir du [DeviceProfile].
 *
 * `configChanges` empêche la recréation de l'activité lors d'une rotation ou
 * d'un redimensionnement : le lecteur vidéo n'est ainsi jamais interrompu.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val deviceProfile = rememberDeviceProfile()

            MissaTvTheme(deviceProfile = deviceProfile) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    // Écran provisoire : il sert à vérifier que la détection
                    // d'appareil est correcte sur les différents matériels.
                    // Il sera remplacé par le routeur de navigation adaptatif.
                    DeviceDiagnosticScreen(deviceProfile = deviceProfile)
                }
            }
        }
    }
}
