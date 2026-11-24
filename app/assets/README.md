# 克隆包签名密钥（公开材料）

`clone.key`（PKCS#8 DER）与 `clone.crt`（X.509 DER）是**给克隆出来的 APK 签名**用的钥匙。

它和 `release.jks`（给「双开管家」这个 App 本身签名）不是一回事：

| 文件 | 用途 | 是否入库 |
|---|---|---|
| `app/release.jks` | 给 TvDualManager.apk 签名 | ❌ 不入库，自行保管 |
| `app/assets/clone.key` | 给克隆出来的应用签名 | ✅ 入库 |

**为什么这把钥匙可以公开**：它被编译进 APK 的 `assets/` 里，任何人反编译发布包都能拿到；
它的作用只是让多个克隆包之间有稳定的签名（同一把钥匙 → 克隆包之间可以互相覆盖升级）。
它不保护任何服务端资源，也不代表任何官方身份。

想换成自己的钥匙：

```bash
keytool -genkeypair -keystore my.p12 -storetype PKCS12 -alias clone \
  -keyalg RSA -keysize 2048 -validity 20000 \
  -storepass <你的口令> -keypass <你的口令> -dname "CN=My Clone"

openssl pkcs12 -in my.p12 -nocerts -nodes -passin pass:<你的口令> \
  | openssl pkcs8 -topk8 -nocrypt -outform DER -out app/assets/clone.key
openssl pkcs12 -in my.p12 -nokeys -clcerts -passin pass:<你的口令> \
  | openssl x509 -outform DER -out app/assets/clone.crt
```

换钥匙后，**已装的克隆包无法再被新克隆覆盖**（签名不一致），需要先卸载。
