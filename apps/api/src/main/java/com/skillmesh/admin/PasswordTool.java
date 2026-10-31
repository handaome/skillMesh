package com.skillmesh.admin;

import java.io.BufferedReader;
import java.io.Console;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 急救通道：重置内置账号的密码。
 *
 * 为什么要有它：站点管理员是「第一个注册出来的内置账号」，密码忘了就没人能进后台；
 * 服务端也没有改密码的接口（管理员能给别的账号建号、禁用，改不了密码）。
 * 这个工具直接改库里的密码哈希，前提是**还握着一台能连上数据库的机器**。
 *
 * 用法：
 * <pre>
 * bash infra/scripts/reset-password.sh list
 * bash infra/scripts/reset-password.sh reset &lt;用户名&gt;            # 交互输入新密码（终端支持时不回显）
 * bash infra/scripts/reset-password.sh reset &lt;用户名&gt; --generate # 随机生成一个，写到本地文件里
 * </pre>
 *
 * 它不启动应用：应用起不来的时候恰恰是最需要它的时候（密码忘了就登不进去，
 * 迁移历史对不上也起不来）。所以这里只借 Spring 把数据源配好（凭据读根目录的 .env），
 * 不跑 Flyway、不跑 JPA 校验、不开 HTTP 端口。
 *
 * **它住在 com.skillmesh.admin（不在 com.skillmesh.api 里）**：主应用扫的是
 * com.skillmesh.api，扫到这里就会把下面这个「排除 Flyway/JPA」的 Config 带进主应用，
 * 主应用于是不建表也不校验 schema。
 */
public class PasswordTool {

    /** BCrypt 的默认强度，与 LocalAuthService 一致（否则两边的哈希对不上）。 */
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    /** 密码最短 8 位、最长 72 位：72 是 BCrypt 的硬上限，与注册同一条规则。 */
    private static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 72;

    /** 随机生成时用的字母表：去掉 l/I/o/0/1 这些容易看错的字符。 */
    private static final String ALPHABET =
        "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private static final int GENERATED_LENGTH = 20;

    /** 生成的密码写到这儿：只在这个用户自己读得到的地方落一次盘。 */
    private static final Path PASSWORD_FILE =
        Path.of(System.getProperty("user.home"), ".skillmesh-data", "admin-password.txt");

    private static final SecureRandom RANDOM = new SecureRandom();

    public static void main(String[] args) {
        if (args.length < 1) {
            usage();
            System.exit(2);
        }
        String command = args[0];
        String username = null;
        boolean generate = false;
        boolean enable = false;
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if ("--generate".equals(arg)) {
                generate = true;
            } else if ("--enable".equals(arg)) {
                enable = true;
            } else if (username == null) {
                username = arg;
            } else {
                usage();
                System.exit(2);
            }
        }
        // 命令行工具：不启动 Web 容器（8080 上可能正跑着服务）、不跑 Flyway、不跑 JPA 校验。
        System.setProperty("spring.main.banner-mode", "off");
        System.setProperty("spring.main.web-application-type", "none");
        // Windows 上 System.out 默认走系统 ANSI 代码页（GBK），中文出来是乱码。
        // 这个工具的输出全是给人看的，按 UTF-8 写。
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true,
            StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true,
            StandardCharsets.UTF_8));
        try (ConfigurableApplicationContext context = SpringApplication.run(Config.class)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            if ("list".equals(command)) {
                list(jdbc);
                return;
            }
            if ("reset".equals(command) && username != null) {
                reset(jdbc, username, generate, enable);
                return;
            }
            usage();
            System.exit(2);
        } catch (Exception e) {
            System.err.println("失败：" + e.getMessage());
            System.exit(1);
        }
    }

    /** 内置账号一览：用户名、角色、状态、有没有密码。 */
    private static void list(JdbcTemplate jdbc) {
        List<String> rows = jdbc.query(
            "select username, role, status, (password_hash is not null), "
                + "to_char(created_at, 'YYYY-MM-DD HH24:MI') "
                + "from users where username is not null order by created_at",
            (rs, rowNum) -> String.format("  %-16s %-6s %-8s %-6s %s",
                rs.getString("username"), rs.getString("role"), rs.getString("status"),
                rs.getBoolean(4) ? "有密码" : "无密码", rs.getString(5)));
        System.out.println("内置账号（用户名 / 角色 / 状态 / 密码 / 建号时间）：");
        if (rows.isEmpty()) {
            System.out.println("  （一个都没有）");
            return;
        }
        rows.forEach(System.out::println);
    }

    /** 重置密码：先确认账号在，再写哈希，写完当场验一遍。 */
    private static void reset(JdbcTemplate jdbc, String username, boolean generate, boolean enable)
            throws Exception {
        List<Account> found = jdbc.query(
            "select id, username, role, status from users where lower(username) = lower(?)",
            (rs, rowNum) -> new Account(rs.getObject("id", UUID.class), rs.getString("username"),
                rs.getString("role"), rs.getString("status")),
            username);
        if (found.isEmpty()) {
            System.err.println("没有这个用户名：" + username);
            System.err.println("现成的内置账号：");
            list(jdbc);
            System.exit(1);
        }
        Account account = found.get(0);

        String password = generate ? generatePassword() : promptPassword();
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            System.err.println("密码要 " + MIN_LENGTH + " 到 " + MAX_LENGTH
                + " 位（72 是 BCrypt 的硬上限，超了会被截断）");
            System.exit(1);
        }

        String status = enable ? "active" : account.status();
        jdbc.update("update users set password_hash = ?, status = ?, updated_at = now() "
                + "where id = ?",
            ENCODER.encode(password), status, account.id());

        // 当场验一遍：写进去的哈希得真能验过，不然等于没改，还以为改了。
        String stored = jdbc.queryForObject("select password_hash from users where id = ?",
            String.class, account.id());
        if (stored == null || !ENCODER.matches(password, stored)) {
            System.err.println("写入后校验没过，密码没改成——别拿它登录，先查库");
            System.exit(1);
        }
        System.out.println("已重置 " + account.username() + " 的密码（角色 " + account.role()
            + "，状态 " + status + "），并已校验通过。");
        if ("disabled".equals(account.status()) && !enable) {
            System.out.println("注意：这个账号还是 disabled，登不进去——要一起启用就加 --enable。");
        }
        if (generate) {
            writePasswordFile(password);
            System.out.println("新密码已写到 " + PASSWORD_FILE + "（只写这一次，用完删掉）。");
        }
    }

    /** 交互输入新密码。终端支持时不回显。 */
    private static String promptPassword() {
        Console console = System.console();
        if (console != null) {
            System.out.print("新密码（不回显）：");
            System.out.flush();
            char[] input = console.readPassword();
            System.out.println();
            return input == null ? null : new String(input);
        }
        System.out.println("当前终端不支持隐藏输入（管道/重定向/某些模拟终端），"
            + "输入会显示在屏幕上。");
        System.out.print("新密码：");
        System.out.flush();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            return reader.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    /** 随机生成一个密码。 */
    private static String generatePassword() {
        StringBuilder out = new StringBuilder(GENERATED_LENGTH);
        for (int i = 0; i < GENERATED_LENGTH; i++) {
            out.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }

    /** 把生成的密码落到跑这个命令的人自己的家目录里。 */
    private static void writePasswordFile(String password) throws Exception {
        Files.createDirectories(PASSWORD_FILE.getParent());
        Files.writeString(PASSWORD_FILE, password + System.lineSeparator(),
            StandardCharsets.UTF_8);
    }

    private static void usage() {
        System.err.println("用法：");
        System.err.println("  PasswordTool list                      看有哪些内置账号");
        System.err.println("  PasswordTool reset <用户名>             交互输入新密码");
        System.err.println("  PasswordTool reset <用户名> --generate  随机生成一个，写到本地文件");
        System.err.println("  PasswordTool reset <用户名> --enable    顺便把 disabled 的账号启用");
    }

    /** 账号：id、用户名、角色、状态。 */
    private record Account(UUID id, String username, String role, String status) {
    }

    /**
     * 只借 Spring 把数据源配好，其余一概不要。
     *
     * 它是静态内部类，不参与组件扫描——主应用扫到它就会连带把
     * 「排除 Flyway 与 JPA」带进主应用，主应用于是不建表、也不校验 schema。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {FlywayAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class, JpaRepositoriesAutoConfiguration.class,
        SecurityAutoConfiguration.class})
    static class Config {
    }
}
