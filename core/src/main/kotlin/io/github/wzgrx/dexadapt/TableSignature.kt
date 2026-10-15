package io.github.wzgrx.dexadapt

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * 共享适配表的签名（ECDSA P-256 + SHA-256；所有 Android 版本和 JDK 都自带，不用另带密码库）。
 * 开发者（CI）用私钥给生成的表签名，模块里只放公钥；下载来的表签名对不上一律不用。
 * 密钥写成 Base64：私钥 PKCS#8，公钥 X.509（SubjectPublicKeyInfo）。
 */
object TableSignature {
    private const val ALGORITHM = "SHA256withECDSA"

    data class KeyPair(val privateKey: String, val publicKey: String)

    fun generate(): KeyPair {
        val g = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val kp = g.generateKeyPair()
        return KeyPair(b64(kp.private.encoded), b64(kp.public.encoded))
    }

    /** 给 [content]（表文件的原始字节）签名，返回 Base64。 */
    fun sign(content: ByteArray, privateKey: String): String {
        val s = Signature.getInstance(ALGORITHM).apply { initSign(private(privateKey)); update(content) }
        return b64(s.sign())
    }

    /** [signature] 是不是 [publicKey] 对应的私钥给 [content] 签的。格式不对也返回 false。 */
    fun verify(content: ByteArray, signature: String, publicKey: String): Boolean = runCatching {
        val s = Signature.getInstance(ALGORITHM).apply { initVerify(public(publicKey)); update(content) }
        s.verify(Base64.getDecoder().decode(signature.trim()))
    }.getOrDefault(false)

    private fun private(key: String): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(key.trim())))
    private fun public(key: String): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(key.trim())))
    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
}
