package ch.leico.declic

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/** Connexion au débogage sans fil du téléphone lui-même (comme Shizuku, mais intégré à Déclic). */
class AdbManager private constructor(ctx: Context) : AbsAdbConnectionManager() {

    private val pk: PrivateKey
    private val crt: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val keyFile = File(ctx.filesDir, "adb.key")
        val crtFile = File(ctx.filesDir, "adb.crt")
        var k: PrivateKey? = null
        var c: Certificate? = null
        if (keyFile.exists() && crtFile.exists()) {
            try {
                k = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                c = crtFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            } catch (_: Exception) {
                k = null
                c = null
            }
        }
        if (k == null || c == null) {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            val kp = kpg.generateKeyPair()
            val name = X500Name("CN=Declic")
            val now = System.currentTimeMillis()
            val builder = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now), Date(now - 86_400_000L),
                Date(now + 30L * 365 * 86_400_000L), name, kp.public
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
            c = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            k = kp.private
            keyFile.writeBytes(k.encoded)
            crtFile.writeBytes(c.encoded)
        }
        pk = k!!
        crt = c!!
    }

    override fun getPrivateKey(): PrivateKey = pk

    override fun getCertificate(): Certificate = crt

    override fun getDeviceName(): String = "Declic"

    companion object {
        @Volatile private var inst: AdbManager? = null

        fun get(ctx: Context): AdbManager =
            inst ?: synchronized(this) { inst ?: AdbManager(ctx.applicationContext).also { inst = it } }
    }
}
