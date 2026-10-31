package com.skillmesh.api.common;

/**
 * 把接口传来的字符串变成枚举。
 *
 * 认不出来的取值是调用方的错，报 400 而不是 500：以前这些值直接进数据库，
 * 撞上 CHECK 约束才炸，错误信息里只有一句约束名。
 *
 * 匹配不区分大小写：接口里的值一律小写，收到大写的也当同一个。
 */
public final class EnumParams {

    private EnumParams() {}

    /**
     * 取枚举值。认不出来报 400。
     *
     * @param type 枚举类；常量名就是它在数据库与接口里的值（大小写不敏感）
     * @param value 调用方传来的原始值
     * @param field 字段名，错误信息里用
     */
    public static <E extends Enum<E>> E require(Class<E> type, String value, String field) {
        E parsed = parse(type, value);
        if (parsed == null) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                field + " 取值不对：" + value + "（可用：" + values(type) + "）");
        }
        return parsed;
    }

    /** 认不出来的给 null，由调用方自己决定怎么报。 */
    public static <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        for (E candidate : type.getEnumConstants()) {
            if (candidate.name().equalsIgnoreCase(normalized)) {
                return candidate;
            }
        }
        return null;
    }

    /** 可用取值清单，错误信息里用。 */
    public static <E extends Enum<E>> String values(Class<E> type) {
        return String.join(" / ", java.util.Arrays.stream(type.getEnumConstants())
            .map(Enum::name)
            .map(name -> name.toLowerCase(java.util.Locale.ROOT))
            .toList());
    }
}
