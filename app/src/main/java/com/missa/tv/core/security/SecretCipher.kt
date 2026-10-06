package com.missa.tv.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.missa.tv.core.log.MissaLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8

/**
 * Chiffrement des données sensibles stockées sur l'appareil.
 *
 * Interface séparée de l'implémentation pour deux raisons : les tests utilisent
 * une doublure sans magasin de clés, et l'algorithme peut évoluer sans toucher
 * aux couches qui stockent.
 */
interface SecretCipher {

    /** Chiffre un texte. Renvoie `null` si le chiffrement échoue. */
    fun encrypt(plainText: String): String?

    /** Déchiffre un texte produit par [encrypt]. Renvoie `null` si illisible. */
    fun decrypt(cipherText: String): String?
}

/**
 * Chiffrement AES-GCM avec une clé conservée par le magasin de clés d'Android.
 *
 * La clé ne quitte jamais le magasin de clés matériel : elle n'est ni écrite
 * dans le fichier de préférences, ni sauvegardée, ni extractible de l'appareil.
 * Le contenu stocké est donc inutilisable pour qui lirait le fichier.
 *
 * Chaque chiffrement utilise un vecteur d'initialisation aléatoire, conservé en
 * tête du message : chiffrer deux fois le même texte donne deux résultats
 * différents, ce qui empêche de déduire une valeur en comparant deux fichiers.
 */
class AndroidKeystoreCipher : SecretCipher {

    private val keyStore: KeyStore? by lazy {
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        }.getOrElse { erreur ->
            MissaLog.e("Magasin de clés indisponible", erreur)
            null
        }
    }

    private val key: SecretKey? by lazy {
        val magasin = keyStore ?: return@lazy null
        runCatching {
            (magasin.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
                ?: genererCle(magasin)
        }.getOrElse { erreur ->
            MissaLog.e("Clé de chiffrement inaccessible", erreur)
            null
        }
    }

    private fun genererCle(magasin: KeyStore): SecretKey {
        val generateur = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generateur.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // Aucune authentification utilisateur : l'application doit
                // pouvoir rafraîchir sa configuration en arrière-plan.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        val cle = generateur.generateKey()
        MissaLog.i("Clé de chiffrement créée dans le magasin de clés")
        return cle
    }

    override fun encrypt(plainText: String): String? {
        val cle = key ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, cle)
            val chiffre = cipher.doFinal(plainText.encodeUtf8().toByteArray())
            // Vecteur d'initialisation concaténé au message chiffré.
            (cipher.iv + chiffre).base64()
        }.getOrElse { erreur ->
            MissaLog.e("Chiffrement impossible", erreur)
            null
        }
    }

    override fun decrypt(cipherText: String): String? {
        val cle = key ?: return null
        val octets = cipherText.decodeBase64() ?: return null
        if (octets.size <= IV_SIZE_BYTES) return null

        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = okio.Buffer().write(octets, 0, IV_SIZE_BYTES).readByteArray()
            cipher.init(
                Cipher.DECRYPT_MODE,
                cle,
                GCMParameterSpec(TAG_LENGTH_BITS, iv),
            )
            val clair = cipher.doFinal(octets.toByteArray(), IV_SIZE_BYTES, octets.size - IV_SIZE_BYTES)
            clair.decodeToString()
        }.getOrElse { erreur ->
            // Un déchiffrement qui échoue n'est pas une panne : la clé a pu être
            // invalidée ou le fichier remplacé. L'appelant repart des valeurs
            // par défaut.
            MissaLog.w("Déchiffrement impossible, données ignorées", erreur)
            null
        }
    }

    private fun ByteArray.base64(): String = okio.ByteString.of(*this).base64()

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "missa_tv_config_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** Taille du vecteur d'initialisation recommandée pour GCM. */
        const val IV_SIZE_BYTES = 12

        /** Longueur du tag d'authentification, en bits. */
        const val TAG_LENGTH_BITS = 128
    }
}
