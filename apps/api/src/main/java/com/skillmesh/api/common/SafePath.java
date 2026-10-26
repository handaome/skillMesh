package com.skillmesh.api.common;

import java.util.regex.Pattern;

/**
 * 相对路径的安全闸：一条路径能不能当「目标目录下的相对路径」用。
 *
 * 路径最终会变成 zip 的 entry 名，而安装器把 entry 名当成目标目录下的相对路径——
 * 放进 ".." 就是往目标目录外写文件。「能不能当相对路径用」这道判定在扫描、
 * 同步上传、版本编辑三条路上都要做，条件散着写迟早有一处漏掉或改歪。
 *
 * 两种毛病要分开说：绝对路径根本不是相对路径（Windows 上 {@code C:\x}、
 * {@code \\server\share\x}、{@code \x} 都算），穿越是相对路径但含 ".." 段
 * （{@code ../x}、{@code a/../b}）。处置方式不一样——绝对路径有的地方规整掉就行，
 * 穿越一律拒绝——所以判定结果不能糊成一个布尔。
 */
public final class SafePath {

    /**
     * Windows 盘符：单字母加冒号，后面必须跟分隔符。
     *
     * 跟分隔符是必须的：{@code C:evil.txt} 是盘符相对路径（以 C 盘当前目录为基准），
     * 不是绝对路径；不跟分隔符一起判，还会把 {@code a:b.md} 这类文件名也误伤。
     */
    private static final Pattern DRIVE = Pattern.compile("^[A-Za-z]:[/\\\\].*");

    private SafePath() {}

    /** 路径的性质。 */
    public enum Kind {
        /** 干净的相对路径。 */
        RELATIVE,
        /** 绝对路径：{@code /x}、{@code \x}、{@code C:\x}、{@code \\server\share\x}。 */
        ABSOLUTE,
        /** 含 ".." 路径段，能走到目标目录外面去。 */
        TRAVERSAL
    }

    /**
     * 给路径定性。
     *
     * null 与空串算「相对」：没有路径就没什么可逃的，空路径由调用方自己判
     * （同步上传要拒空路径，版本编辑要报「文件路径不能为空」，两处口径不同）。
     *
     * 判定前先去掉首尾空白：Windows 会把段尾的空格裁掉（{@code ".. "} 落到
     * 文件系统上就是 {@code ".."}），版本编辑那边也是先 trim 再判。三处必须判同一串，
     * 否则同一个路径在扫描/同步/版本编辑会得出不同结论。
     */
    public static Kind classify(String path) {
        if (path == null) {
            return Kind.RELATIVE;
        }
        String trimmed = path.trim();
        if (trimmed.isEmpty()) {
            return Kind.RELATIVE;
        }
        if (hasTraversalSegment(trimmed)) {
            return Kind.TRAVERSAL;
        }
        return isAbsoluteForm(trimmed) ? Kind.ABSOLUTE : Kind.RELATIVE;
    }

    /** 是不是绝对路径。 */
    public static boolean isAbsolute(String path) {
        return classify(path) == Kind.ABSOLUTE;
    }

    /** 会不会走出目标目录：含 ".." 路径段。 */
    public static boolean isTraversal(String path) {
        return classify(path) == Kind.TRAVERSAL;
    }

    /**
     * 能不能当相对路径用：既不是绝对路径，也不含穿越段。
     *
     * 两种毛病都要挡——绝对路径根本不是相对路径，".." 能一路走到目标目录外面去。
     */
    public static boolean escapes(String path) {
        return classify(path) != Kind.RELATIVE;
    }

    /**
     * 绝对路径的各种形态。
     *
     * - {@code /x}、{@code //server/share/x}：POSIX 绝对路径（双斜杠是 UNC）
     * - {@code \x}、{@code \\server\share\x}：Windows 的根相对路径与 UNC
     * - {@code C:\x}、{@code C:/x}：Windows 盘符
     *
     * 反斜杠要一起看：Windows 上 {@code \x} 就是根下的 x，
     * 而安装器把 entry 名转成本机路径时反斜杠同样是分隔符。
     */
    private static boolean isAbsoluteForm(String path) {
        char first = path.charAt(0);
        if (first == '/' || first == '\\') {
            return true;
        }
        return DRIVE.matcher(path).matches();
    }

    /**
     * 有没有 ".." 路径段。
     *
     * 只有整段就是 ".." 才算穿越：{@code foo..bar.md} 是普通文件名，
     * 按子串判会把合法的文件也拦下来（拦下就发不出这一版，用户只能改文件名）。
     * 分隔符按两种都算——Windows 上反斜杠也是路径分隔符。
     */
    private static boolean hasTraversalSegment(String path) {
        for (String segment : path.split("[/\\\\]")) {
            if ("..".equals(segment)) {
                return true;
            }
        }
        return false;
    }
}
