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
     * 把时间与 id 编成游标。
     *
     * 时间用 toString 的形式（{@link OffsetDateTime#toString()}）——带偏移量的
     * ISO 格式，解析回来是同一个瞬间。
     */
    public static String encode(OffsetDateTime time, UUID id) {
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

    private static String encodeRaw(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] decodeRaw(String cursor) {
        return Base64.getUrlDecoder().decode(cursor);
    }
}
