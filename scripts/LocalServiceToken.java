import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.spec.*;
import java.time.Instant;
import java.util.*;
import java.nio.charset.StandardCharsets;

/**
 * 第二十八章本机教学签发工具，使用 JDK RSA，不需要调用模型或外部认证网站。
 * 私钥和短期令牌只写 .local；接收服务只读公钥。生产环境请使用真实身份提供方。
 */
public class LocalServiceToken {
    static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
        Path local = root.resolve(".local");
        Files.createDirectories(local);
        Files.setPosixFilePermissions(local, PosixFilePermissions.fromString("rwx------"));
        Path privateFile = local.resolve("receiver-signing.pk8");
        Path publicFile = local.resolve("receiver-signing.pub");
        if (Files.exists(privateFile) != Files.exists(publicFile)) {
            throw new IllegalStateException("本地签发密钥不完整，请恢复原文件；未覆盖密钥");
        }
        if (!Files.exists(privateFile)) {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(3072);
            var pair = generator.generateKeyPair();
            writePrivate(privateFile, pair.getPrivate().getEncoded());
            String pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'})
                    .encodeToString(pair.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----\n";
            writePrivate(publicFile, pem.getBytes(StandardCharsets.US_ASCII));
        }
        var key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(privateFile)));
        Instant now = Instant.now();
        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
        String claims = """
                {"iss":"urn:yunshan:local-service-issuer","sub":"yunshan-customer-service",
                "aud":"after-sale-receiver","scope":"after-sale.ingest after-sale.reconcile","jti":"%s",
                "iat":%d,"nbf":%d,"exp":%d}
                """.formatted(UUID.randomUUID(), now.getEpochSecond(), now.minusSeconds(5).getEpochSecond(), now.plusSeconds(8*3600).getEpochSecond());
        String input = URL.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + URL.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        var signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key);
        signature.update(input.getBytes(StandardCharsets.US_ASCII));
        String token = input + "." + URL.encodeToString(signature.sign());
        // 只覆盖本章配置，不替换第27章的演示凭证，也不把令牌打印到 stdout。
        String sender = "REMOTE_AFTER_SALE_ENDPOINT=http://127.0.0.1:18083/integration/after-sales/applications\n"
                + "REMOTE_AFTER_SALE_TOKEN=" + token + "\n";
        writePrivate(local.resolve("inbox-sender.properties"), sender.getBytes(StandardCharsets.UTF_8));
        String receiver = "app.local-jwt.public-key=" + publicFile.toUri() + "\n"
                + "SERVICE_TOKEN_ISSUER=urn:yunshan:local-service-issuer\n";
        writePrivate(local.resolve("receiver-jwt.properties"), receiver.getBytes(StandardCharsets.UTF_8));
        System.out.println("已生成本机 RS256 服务令牌（8小时），仅写入 .local；重启发送方加载新令牌。");
    }

    /** 临时文件以0600创建，原子替换避免服务读到半个令牌。 */
    static void writePrivate(Path path, byte[] data) throws Exception {
        Path temporary = Files.createTempFile(path.getParent(), ".receiver-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            Files.write(temporary, data);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
