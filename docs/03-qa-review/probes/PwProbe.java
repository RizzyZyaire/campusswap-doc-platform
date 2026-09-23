import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 探针：验证 Spring Security 的 BCryptPasswordEncoder 能否校验
 * 我们库里由 Hutool BCrypt 生成的 $2b$10$ 哈希（只读，不改库）。
 */
public class PwProbe {

    public static void main(String[] args) {
        String raw = args[0];
        String hash = args[1];
        BCryptPasswordEncoder enc = new BCryptPasswordEncoder();
        System.out.println("hash.prefix      = " + hash.substring(0, 7));
        System.out.println("matches(existing)= " + enc.matches(raw, hash));
        String fresh = enc.encode(raw);
        System.out.println("spring.new.prefix= " + fresh.substring(0, 7));
        System.out.println("matches(new)     = " + enc.matches(raw, fresh));
        System.out.println("upgradeEncoding  = " + enc.upgradeEncoding(hash));
    }
}
