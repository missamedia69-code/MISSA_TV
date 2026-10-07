package com.missa.tv.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Palette MISSA TV.
 *
 * L'interface est sombre par défaut : elle est d'abord pensée pour un écran de
 * télévision, et un fond sombre met en valeur la vidéo. Les contrastes
 * respectent le niveau AA (rapport >= 4,5:1 pour le texte courant).
 */

// Couleurs de marque
val MissaRed = Color(0xFFE23A2E)
val MissaRedDark = Color(0xFFB3261C)
val MissaBlue = Color(0xFF3D7DFF)

// Thème sombre
val MissaBackground = Color(0xFF0B0B0F)
val MissaSurface = Color(0xFF14141C)
val MissaSurfaceVariant = Color(0xFF26262F)
val MissaOnSurface = Color(0xFFF2F2F7)
val MissaOnSurfaceVariant = Color(0xFFA8A8B8)
val MissaError = Color(0xFFFF6B6B)
val MissaOnError = Color(0xFF3A0A0A)

// Thème clair (téléphone et tablette en mode clair)
val MissaBackgroundLight = Color(0xFFFAFAFC)
val MissaSurfaceLight = Color(0xFFFFFFFF)
val MissaSurfaceVariantLight = Color(0xFFE6E6EF)
val MissaOnSurfaceLight = Color(0xFF14141C)
val MissaOnSurfaceVariantLight = Color(0xFF4A4A5A)
val MissaErrorLight = Color(0xFFB3261C)
