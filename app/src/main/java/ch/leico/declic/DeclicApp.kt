package ch.leico.declic

import android.app.Application
import org.conscrypt.Conscrypt
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.security.Security

class DeclicApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            HiddenApiBypass.addHiddenApiExemptions("L")
        } catch (_: Throwable) {
        }
        try {
            // TLS 1.3 nécessaire à l'association du débogage sans fil
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        } catch (_: Throwable) {
        }
        Core.init(this)
    }
}
