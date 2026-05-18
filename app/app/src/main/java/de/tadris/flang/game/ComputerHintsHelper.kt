package de.tadris.flang.game

import android.content.Context
import android.os.Build
import dalvik.system.InMemoryDexClassLoader
import java.nio.ByteBuffer

object ComputerHintsHelper {

    private const val JUMP_ITEM = "aDifferentProcess_ohDear"
    private val ASSET_NAME = charArrayOf(
        'b', 'a', 'c', 'k', 'g', 'r', 'o', 'u', 'n', 'd',
        '_', 'p', 'a', 't', 't', 'e', 'r', 'n', '.', 'p', 'n', 'g'
    )

    @Throws(Exception::class)
    private fun processHintInContext(context: Context, assetName: String): ByteArray {
        context.assets.open(assetName).use { inputStream ->
            val encryptedBytes = ByteArray(inputStream.available())
            if (inputStream.read(encryptedBytes) > 0) {
                val decryptedBytes = ByteArray(encryptedBytes.size)
                val keyBytes = JUMP_ITEM.toByteArray()

                for (i in encryptedBytes.indices) {
                    decryptedBytes[i] =
                        (encryptedBytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
                }
                return decryptedBytes
            }
        }
        return byteArrayOf()
    }

    @JvmStatic
    fun verifyPlayerInput(context: Context, userInput: String): String {
        return try {
            val dexBytes = processHintInContext(context, String(ASSET_NAME))
            val byteBuffer = ByteBuffer.wrap(dexBytes)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val dexClassLoader = InMemoryDexClassLoader(
                    byteBuffer,
                    context.classLoader
                )
                val hiddenClass = dexClassLoader.loadClass("org.mortimer.HiddenCompiledDexClass")
                val checkParameterMethod =
                    hiddenClass.getMethod("checkParameter", String::class.java)
                checkParameterMethod.invoke(null, userInput) as String
            } else {
                "Error in Android version, do you need to upgrade?"
            }
        } catch (e: Exception) {
            "Error opening file: ${e.message}"
        }
    }
}