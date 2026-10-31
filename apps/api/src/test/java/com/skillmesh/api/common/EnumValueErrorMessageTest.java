package com.skillmesh.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillmesh.api.auth.UserStatus;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

/**
 * 枚举取值不对时，错误提示要指向对的字段。
 *
 * 状态字段改成枚举之后，取值不对的请求体不再由应用代码检查，
 * 而是 Jackson 反序列化时抛异常——而这个异常原先的提示是
 * 「请求体读不出来，检查编码和 JSON 格式」：JSON 格式好好的，
 * 提示把人引到错的方向。
 *
 * 这里用真实的 Jackson 转换器复现那条异常链：
 * 提示里得带上是哪个字段、收到了什么值。
 */
class EnumValueErrorMessageTest {

    private final ObjectMapper json = new ObjectMapper();

    /** 让 Jackson 真读一遍，拿到它抛的那条异常链。 */
    private Throwable readFailure(Class<?> type, String body) {
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(json);
        HttpInputMessage message = new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public HttpHeaders getHeaders() {
                return new HttpHeaders();
            }
        };
        try {
            converter.read(type, message);
        } catch (Exception e) {
            return e;
        }
        throw new AssertionError("本该反序列化失败");
    }

    /** 自定义转换方法的枚举（Visibility 有 @JsonCreator）。 */
    record VisibilityHolder(Visibility visibility) {}

    /** 走 Jackson 内置枚举反序列化的枚举。 */
    record StatusHolder(UserStatus status) {}

    @Test
    void 可见性取值不对时提示里要带上字段名和收到的值() {
        Throwable failure = readFailure(VisibilityHolder.class, "{\"visibility\":\"secret\"}");

        String detail = GlobalExceptionHandler.badEnumValue(failure);

        assertThat(detail).contains("visibility");
        assertThat(detail).contains("secret");
    }

    @Test
    void 内置枚举取值不对时提示里也要带上字段名和可选值() {
        Throwable failure = readFailure(StatusHolder.class, "{\"status\":\"ACTIVE\"}");

        String detail = GlobalExceptionHandler.badEnumValue(failure);

        assertThat(detail).contains("status").contains("active");
    }

    @Test
    void json真的坏了就说读不出来而不是取值不对() {
        Throwable failure = readFailure(StatusHolder.class, "{不是 json");

        assertThat(GlobalExceptionHandler.badEnumValue(failure)).isNull();
    }
}
