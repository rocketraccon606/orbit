package app.orbit.tv

import android.content.Context
import app.orbit.tv.catalog.CatalogManager
import app.orbit.tv.player.PlaybackController
import app.orbit.tv.settings.Prefs
import app.orbit.tv.userdata.UserDatabase
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

class AppContainer(context: Context) {
    val okHttp: OkHttpClient = buildClient()

    val prefs = Prefs(context)
    val catalog = CatalogManager(context.filesDir, context.cacheDir, prefs, okHttp)
    val users = UserDatabase(context)
    val playback = PlaybackController(context.applicationContext, okHttp)

    fun onBackground() = playback.stopAndRelease()

    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
        if (BuildConfig.DEBUG) {
            // Emulator traffic can pass through a host antivirus that re-signs TLS
            // with a root the TV image does not trust. Release builds stay strict.
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ssl = SSLContext.getInstance("TLS")
            ssl.init(null, arrayOf(trustAll), SecureRandom())
            builder.sslSocketFactory(ssl.socketFactory, trustAll)
            builder.hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }
}
