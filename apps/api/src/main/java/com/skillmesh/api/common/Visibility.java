package com.skillmesh.api.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;

/**
 * 可见性：谁看得见这个 Skill / 集合。
 *
 * 与别的状态枚举不同，这里的常量名是大写：'private' 与 'public' 是 Java 关键字，
 * 不能拿来做标识符。大写就得自己映射到数据库里的小写值——
 * 映射在 {@link DbConverter} 里，接口那边用 {@link #value()} 出同一份小写值。
 *
 * 其余状态枚举没有这一层：常量名本身就是数据库里存的值。
 */
public enum Visibility {

    /** 只有属主自己看得见。 */
    PRIVATE("private"),

    /** 团队内可见。 */
    TEAM("team"),

    /** 组织内可见。 */
    ORGANIZATION("organization"),

    /** 谁都能看见。 */
    PUBLIC("public");

    /** 数据库与接口里用的值。 */
    private final String value;

    /** 接口与数据库里用的值，顺序跟 {@link #values()} 一致。 */
    private static final List<String> WIRE_VALUES =
        java.util.Arrays.stream(values()).map(Visibility::value).toList();

    Visibility(String value) {
        this.value = value;
    }

    /** 数据库与接口里用的值：'private' / 'team' / 'organization' / 'public'。 */
    @JsonValue
    public String value() {
        return value;
    }

    /**
     * 按接口里的值找可见性。
     *
     * 认不出来就是 400：调用方给了个没人认识的可见性，
     * 以前它会一路进数据库，撞上 CHECK 约束才炸，错误信息里只有一句约束名。
     *
     * 忽略大小写与首尾空格：查询参数那边 {@link EnumParams} 也是这么认的，
     * 两边口径不一样的话，同一个值换个入口就 400。
     */
    @JsonCreator
    public static Visibility of(String value) {
        if (value != null) {
            String normalized = value.trim();
            for (Visibility candidate : values()) {
                if (candidate.value.equalsIgnoreCase(normalized)) {
                    return candidate;
                }
            }
        }
        throw new IllegalArgumentException(value + "（可用：" + String.join(" / ", WIRE_VALUES) + "）");
    }

    /** 数据库里的值与枚举之间来回。 */
    @Converter
    public static class DbConverter implements AttributeConverter<Visibility, String> {

        @Override
        public String convertToDatabaseColumn(Visibility attribute) {
            return attribute == null ? null : attribute.value;
        }

        @Override
        public Visibility convertToEntityAttribute(String dbData) {
            if (dbData == null) {
                return null;
            }
            for (Visibility candidate : values()) {
                if (candidate.value.equals(dbData)) {
                    return candidate;
                }
            }
            // 库里存了没人认识的值：不猜，返回 null 让上层按「没有可见性」处理。
            return null;
        }
    }

    /**
     * 取值必须与常量名对得上（只差大小写）。
     *
     * 查询参数那边 {@link EnumParams} 是按常量名认的，错误提示里的可用值清单
     * 也是把常量名转小写拼出来的。哪天加一个名字与取值对不上的常量
     * （比如 ORG 存 organization），搜索接口就会把 organization 判成非法值，
     * 而提示里列出来的又是另一份清单——错到没人查得出原因。
     * 这里盯着：对不上就别让这个类加载成功。
     */
    static {
        for (Visibility candidate : values()) {
            if (!candidate.value.equalsIgnoreCase(candidate.name())) {
                throw new IllegalStateException("Visibility 的取值与常量名对不上：" + candidate);
            }
        }
    }
}
