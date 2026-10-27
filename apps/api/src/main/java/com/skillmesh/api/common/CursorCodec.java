package com.skillmesh.api.common;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

/**
 * 分页游标的编解码：把「时间 + id」编成一段不透明的字符串。
 *
 * 技能列表与邀请列表都用这套游标，形状必须一致——客户端拿到的 next 游标
 * 要能原样传回给下一次请求。
 *
 * 游标是客户端给的东西，坏了是调用方的错：解码失败报 400，不以 500 的
 * 形式把内部异常暴露出去。
 */
public final class CursorCodec {

    private CursorCodec() {}

    /** 游标里的两个成分：时间戳与 id。 */
    public record Cursor(OffsetDateTime time, UUID id) {}

    /**
     * 三个成分的游标：两个时间戳与 id。
     *
     * 审核队列的排序是「提交时间、创建时间、id」三级：只带一个时间戳，
     * 同一秒提交的两版顺序会不定，翻页可能漏掉或重复。
     */
    public record Cursor3(OffsetDateTime time, OffsetDateTime time2, UUID id) {}

    /**
     * 游标里的两个成分：时间戳与自增 id。
     *
     * 审计流水的主键是 bigserial，不是 uuid——{@link Cursor} 装不下。
     * 形状一样（时间|id），但解码分开做：同一个端点只会用其中一种。
     */
    public record NumericCursor(OffsetDateTime time, Long id) {}

    /**
     * 没给游标时的解码结果：时间与 id 都空着。
     *
     * 「没给游标」是正常的第一页请求，不是错误——所以这里给一个空游标，
     * 各服务直接用它的两个字段去查，不用各写一遍判空。
     */
    public static Cursor empty() {
        return new Cursor(null, null);
    }

    /** 同 {@link #empty()}，但 id 是自增的。 */
    public static NumericCursor emptyNumeric() {
        return new NumericCursor(null, null);
    }

    /** 同 {@link #empty()}，但有三个成分。 */
    public static Cursor3 empty3() {
        return new Cursor3(null, null, null);
    }

    /**
     * 解码游标；没给游标就是空游标。
     *
     * 游标坏了照样报 400——那是调用方的错，不该被当成「第一页」放过去。
     */
    public static Cursor decodeOrEmpty(String cursor) {
        return cursor == null || cursor.isBlank() ? empty() : decode(cursor);
    }

    /** 同 {@link #decodeOrEmpty(String)}，但 id 是自增的。 */
    public static NumericCursor decodeNumericOrEmpty(String cursor) {
        return cursor == null || cursor.isBlank() ? emptyNumeric() : decodeNumeric(cursor);
    }

    /** 同 {@link #decodeOrEmpty(String)}，但有三个成分。 */
    public static Cursor3 decode3OrEmpty(String cursor) {
        return cursor == null || cursor.isBlank() ? empty3() : decode3(cursor);
    }

    /**
     * 把时间与 id 编成游标。
     *
     * 时间用 toString 的形式（{@link OffsetDateTime#toString()}）——带偏移量的
     * ISO 格式，解析回来是同一个瞬间。
     */
    public static String encode(OffsetDateTime time, UUID id) {
        return encodeRaw(time + "|" + id);
    }

    /** 把两个时间与 id 编成游标。 */
    public static String encode3(OffsetDateTime time, OffsetDateTime time2, UUID id) {
        return encodeRaw(time + "|" + time2 + "|" + id);
    }

    /** 把时间与自增 id 编成游标。 */
    public static String encodeNumeric(OffsetDateTime time, long id) {
        return encodeRaw(time + "|" + id);
    }

    /**
     * 解码游标。
     *
     * 游标坏了、不是 base64、时间或 id 解析不了，都报同一个错：调用方只需要
     * 知道「这个游标不能用」，不关它具体坏在哪。
     */
    public static Cursor decode(String cursor) {
        try {
            String[] parts = new String(decodeRaw(cursor), StandardCharsets.UTF_8).split("\\|", 2);
            return new Cursor(OffsetDateTime.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException e) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "cursor 格式不对");
        }
    }

    /** 解码三成分游标。 */
    public static Cursor3 decode3(String cursor) {
        try {
            String[] parts = new String(decodeRaw(cursor), StandardCharsets.UTF_8).split("\\|", 3);
            if (parts.length != 3) {
                throw new IllegalArgumentException("不是三段游标");
            }
            return new Cursor3(OffsetDateTime.parse(parts[0]), OffsetDateTime.parse(parts[1]),
                UUID.fromString(parts[2]));
        } catch (RuntimeException e) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "cursor 格式不对");
        }
    }

    /** 解码带自增 id 的游标。 */
    public static NumericCursor decodeNumeric(String cursor) {
        try {
            String[] parts = new String(decodeRaw(cursor), StandardCharsets.UTF_8).split("\\|", 2);
            return new NumericCursor(OffsetDateTime.parse(parts[0]), Long.parseLong(parts[1]));
        } catch (RuntimeException e) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "cursor 格式不对");
        }
    }

    private static String encodeRaw(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] decodeRaw(String cursor) {
        return Base64.getUrlDecoder().decode(cursor);
    }
}
